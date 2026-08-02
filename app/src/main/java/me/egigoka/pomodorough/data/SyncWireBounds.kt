package me.egigoka.pomodorough.data

import java.time.Instant

internal object SyncWireBounds {
    const val MaxSafeInteger = 9_007_199_254_740_991L
    const val MaxClockSkewMs = 300_000L

    data class MutationStamp(
        val deviceSequence: Long?,
        val wallMs: Long,
        val counter: Long,
        val occurredAt: String,
    )

    fun requirePersistedState(deviceSequence: Long, wallMs: Long, counter: Long) {
        require(deviceSequence in 0..MaxSafeInteger) { "Persisted device sequence is invalid" }
        require(isClockTuple(wallMs, counter, allowLegacySentinel = true)) {
            "Persisted hybrid clock is invalid"
        }
    }

    fun requireOperationClock(
        occurredAt: String,
        wallMs: Long,
        counter: Long,
        allowLegacySentinel: Boolean,
    ) {
        require(isClockTuple(wallMs, counter, allowLegacySentinel)) {
            "Operation hybrid clock is invalid"
        }
        val occurrenceMs = runCatching { Instant.parse(occurredAt).toEpochMilli() }
            .getOrElse { throw IllegalArgumentException("Operation occurrence time is invalid") }
        if (wallMs == 0L) {
            require(allowLegacySentinel && counter == 0L && occurrenceMs == 0L) {
                "Operation legacy clock sentinel is invalid"
            }
            return
        }
        require(occurrenceMs in (wallMs - MaxClockSkewMs)..(wallMs + MaxClockSkewMs)) {
            "Operation occurrence time and hybrid clock disagree"
        }
    }

    fun reserve(
        nowMs: Long,
        retainedWallMs: Long,
        retainedCounter: Long,
        retainedDeviceSequence: Long,
        count: Int,
        withDeviceSequences: Boolean,
    ): List<MutationStamp> {
        require(count > 0) { "Mutation reservation must be positive" }
        require(nowMs in 1..MaxSafeInteger) { "Physical occurrence time is invalid" }
        requirePersistedState(retainedDeviceSequence, retainedWallMs, retainedCounter)

        val wallMs = maxOf(nowMs, retainedWallMs)
        require(wallMs - nowMs <= MaxClockSkewMs) {
            "Retained hybrid clock is too far from physical occurrence time"
        }
        val firstCounter = if (wallMs == retainedWallMs) {
            checkedAdd(retainedCounter, 1L, "Hybrid clock counter overflow")
        } else {
            0L
        }
        val finalCounter = checkedAdd(
            firstCounter,
            count.toLong() - 1L,
            "Hybrid clock counter overflow",
        )
        val firstSequence = if (withDeviceSequences) {
            checkedAdd(retainedDeviceSequence, 1L, "Device sequence overflow")
        } else {
            null
        }
        if (firstSequence != null) {
            checkedAdd(firstSequence, count.toLong() - 1L, "Device sequence overflow")
        }
        val occurredAt = Instant.ofEpochMilli(nowMs).toString()
        return List(count) { index ->
            MutationStamp(
                deviceSequence = firstSequence?.plus(index.toLong()),
                wallMs = wallMs,
                counter = firstCounter + index,
                occurredAt = occurredAt,
            )
        }.also {
            check(it.last().counter == finalCounter)
        }
    }

    fun requirePhysicalSkew(physicalOccurrenceMs: Long, wallMs: Long) {
        require(physicalOccurrenceMs in 1..MaxSafeInteger && wallMs in 1..MaxSafeInteger) {
            "Physical or hybrid clock is invalid"
        }
        require(physicalOccurrenceMs in (wallMs - MaxClockSkewMs)..(wallMs + MaxClockSkewMs)) {
            "Hybrid clock is too far from physical occurrence time"
        }
    }

    fun merge(
        nowMs: Long,
        localWallMs: Long,
        localCounter: Long,
        serverWallMs: Long,
        serverCounter: Long,
    ): Pair<Long, Long> {
        require(nowMs in 1..MaxSafeInteger) { "Physical occurrence time is invalid" }
        require(isClockTuple(localWallMs, localCounter, allowLegacySentinel = true)) {
            "Persisted hybrid clock is invalid"
        }
        require(isClockTuple(serverWallMs, serverCounter, allowLegacySentinel = false)) {
            "Server hybrid clock is invalid"
        }
        val boundedLocalWallMs = localWallMs.takeIf {
            it <= nowMs + MaxClockSkewMs
        } ?: 0L
        val wallMs = maxOf(nowMs, boundedLocalWallMs, serverWallMs)
        requirePhysicalSkew(nowMs, wallMs)
        val counter = when {
            wallMs == boundedLocalWallMs && wallMs == serverWallMs ->
                maxOf(localCounter, serverCounter)
            wallMs == boundedLocalWallMs -> localCounter
            wallMs == serverWallMs -> serverCounter
            else -> 0L
        }
        return wallMs to counter
    }

    fun isClockTuple(wallMs: Long, counter: Long, allowLegacySentinel: Boolean): Boolean =
        counter in 0..MaxSafeInteger && (
            wallMs in 1..MaxSafeInteger ||
                allowLegacySentinel && wallMs == 0L && counter == 0L
            )

    private fun checkedAdd(value: Long, increment: Long, message: String): Long {
        require(increment >= 0L && value <= MaxSafeInteger - increment) { message }
        return value + increment
    }
}
