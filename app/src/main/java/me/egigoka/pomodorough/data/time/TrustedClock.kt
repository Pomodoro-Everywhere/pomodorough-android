package me.egigoka.pomodorough.data.time

import java.time.Instant
import kotlin.math.abs
import me.egigoka.pomodorough.data.RequestTiming
import me.egigoka.pomodorough.data.ServerClockSample
import me.egigoka.pomodorough.data.SyncProtocolException
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.local.LocalStateEntity

private const val MaxServerClockUncertaintyMs = 30_000L

private data class TrustedClockAnchor(
    val serverTimeMs: Long,
    val elapsedRealtimeMs: Long,
)

internal class TrustedClock(
    private val physicalTimeMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val currentBootId: () -> String?,
) {
    private var anchor: TrustedClockAnchor? = null

    fun sample(
        response: SyncResponse,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): ServerClockSample {
        val serverMs = validate(response)
        val timing = requestTiming(
            sentPhysicalMs,
            sentElapsedRealtimeMs,
            receivedPhysicalMs,
            receivedElapsedRealtimeMs,
        )
        val offsetMs = try {
            Math.subtractExact(serverMs, timing.midpointPhysicalMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        if (offsetMs !in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        return ServerClockSample(
            offsetMs,
            timing.uncertaintyMs,
            serverMs,
            timing.midpointPhysicalMs,
            timing.midpointElapsedRealtimeMs,
        )
    }

    fun advance(
        sample: ServerClockSample,
        response: SyncResponse,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): ServerClockSample {
        validate(response)
        val timing = requestTiming(
            sentPhysicalMs,
            sentElapsedRealtimeMs,
            receivedPhysicalMs,
            receivedElapsedRealtimeMs,
        )
        if (receivedElapsedRealtimeMs < sample.midpointElapsedRealtimeMs) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val advancedServerMs = try {
            checkedTrustedTime(
                sample.serverTimeMs,
                receivedElapsedRealtimeMs - sample.midpointElapsedRealtimeMs,
            )
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Bootstrap clock sample is outside supported range")
        }
        val offsetMs = try {
            Math.subtractExact(advancedServerMs, receivedPhysicalMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        if (offsetMs !in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        return ServerClockSample(
            offsetMs = offsetMs,
            uncertaintyMs = maxOf(sample.uncertaintyMs, timing.uncertaintyMs),
            serverTimeMs = advancedServerMs,
            midpointPhysicalMs = receivedPhysicalMs,
            midpointElapsedRealtimeMs = receivedElapsedRealtimeMs,
        )
    }

    fun now(
        local: LocalStateEntity,
        sample: ServerClockSample? = null,
        retainedWallMs: Long = local.hlcWallMs,
    ): Long {
        val elapsedNowMs = elapsedRealtimeMillis()
        require(elapsedNowMs >= 0L) { "Elapsed time is outside supported range" }
        if (sample != null) {
            require(elapsedNowMs >= sample.midpointElapsedRealtimeMs) {
                "Elapsed time moved backwards during request"
            }
            return checkedTrustedTime(
                sample.serverTimeMs,
                elapsedNowMs - sample.midpointElapsedRealtimeMs,
            )
        }
        anchor?.takeIf { elapsedNowMs >= it.elapsedRealtimeMs }?.let {
            return checkedTrustedTime(it.serverTimeMs, elapsedNowMs - it.elapsedRealtimeMs)
        }
        return continuedPersistedTime(local, elapsedNowMs, retainedWallMs)
    }

    fun sampledNowOrNull(local: LocalStateEntity, retainedWallMs: Long = local.hlcWallMs): Long? {
        local.serverClockOffsetMs ?: return null
        return runCatching { now(local, retainedWallMs = retainedWallMs) }.getOrNull()
    }

    fun invalidateStaleElapsedAnchor(local: LocalStateEntity): LocalStateEntity? {
        val persistedElapsedMs = local.serverClockSampleElapsedRealtimeMs ?: return null
        val persistedBootId = local.serverClockBootId
        if (
            persistedBootId != null && persistedBootId == currentBootId() &&
            elapsedRealtimeMillis() >= persistedElapsedMs
        ) return null
        return local.copy(
            serverClockSamplePhysicalMs = null,
            serverClockSampleElapsedRealtimeMs = null,
            serverClockBootId = null,
        )
    }

    fun install(sample: ServerClockSample) {
        anchor = TrustedClockAnchor(sample.serverTimeMs, sample.midpointElapsedRealtimeMs)
    }

    fun clear() {
        anchor = null
    }

    fun bootId(): String? = currentBootId()

    fun isStale(sample: ServerClockSample): Boolean {
        val elapsedNow = elapsedRealtimeMillis()
        if (elapsedNow < sample.midpointElapsedRealtimeMs) return true
        val maximumAgeMs = SyncWireBounds.MaxClockSkewMs - sample.uncertaintyMs
        return elapsedNow - sample.midpointElapsedRealtimeMs > maximumAgeMs
    }

    fun responsePhysicalDelta(sample: ServerClockSample): Long = try {
        Math.negateExact(sample.offsetMs)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Server clock offset is outside supported range")
    }

    private fun continuedPersistedTime(
        local: LocalStateEntity,
        elapsedNowMs: Long,
        retainedWallMs: Long,
    ): Long {
        val offsetMs = local.serverClockOffsetMs
            ?: return checkedTrustedTime(physicalTimeMillis(), 0L)
        val persistedPhysicalMs = local.serverClockSamplePhysicalMs
        val persistedElapsedMs = local.serverClockSampleElapsedRealtimeMs
        if (persistedPhysicalMs != null && persistedElapsedMs != null && elapsedNowMs >= persistedElapsedMs) {
            val persistedServerMs = trustedTimeSum(persistedPhysicalMs, offsetMs)
            val continuedServerMs = checkedTrustedTime(
                persistedServerMs,
                elapsedNowMs - persistedElapsedMs,
            )
            anchor = TrustedClockAnchor(continuedServerMs, elapsedNowMs)
            return continuedServerMs
        }
        val restartedServerMs = trustedTimeSum(physicalTimeMillis(), offsetMs)
        require(restartedServerMs in 1..SyncWireBounds.MaxSafeInteger) {
            "Trusted time is outside supported range"
        }
        val uncertaintyMs = local.serverClockUncertaintyMs ?: 0L
        val maximumMs = Math.addExact(
            retainedWallMs,
            SyncWireBounds.MaxClockSkewMs - uncertaintyMs,
        ).coerceAtMost(SyncWireBounds.MaxSafeInteger)
        require(restartedServerMs <= maximumMs) {
            "Trusted time requires a fresh server sample"
        }
        val boundedServerMs = maxOf(restartedServerMs, retainedWallMs)
        anchor = TrustedClockAnchor(boundedServerMs, elapsedNowMs)
        return boundedServerMs
    }

    private fun trustedTimeSum(value: Long, increment: Long): Long = try {
        Math.addExact(value, increment)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Trusted time is outside supported range")
    }

    fun validate(response: SyncResponse): Long {
        val serverMs = try {
            Instant.parse(response.serverTime).toEpochMilli()
        } catch (_: Exception) {
            throw SyncProtocolException("Server returned an invalid server timestamp")
        }
        if (serverMs !in 1..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server timestamp is outside supported range")
        }
        try {
            SyncWireBounds.requirePhysicalSkew(serverMs, response.serverHlcWallMs)
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Server HLC disagrees with server timestamp")
        }
        return serverMs
    }

    private fun requestTiming(
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): RequestTiming {
        requireValidReceipt(sentPhysicalMs, sentElapsedRealtimeMs, receivedPhysicalMs, receivedElapsedRealtimeMs)
        val roundTripMs = receivedElapsedRealtimeMs - sentElapsedRealtimeMs
        val halfRoundTripMs = try {
            Math.addExact(roundTripMs, 1L) / 2L
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val physicalDeltaMs = receiptDifference(receivedPhysicalMs, sentPhysicalMs)
        val disagreementMs = receiptDisagreement(physicalDeltaMs, roundTripMs)
        val uncertaintyMs = receiptUncertainty(halfRoundTripMs, disagreementMs)
        if (uncertaintyMs > MaxServerClockUncertaintyMs) {
            throw SyncProtocolException("Server clock sample uncertainty exceeds 30000ms")
        }
        return RequestTiming(
            uncertaintyMs,
            checkedTimeAdd(sentPhysicalMs, physicalDeltaMs / 2L),
            checkedTimeAdd(sentElapsedRealtimeMs, roundTripMs / 2L),
        )
    }

    private fun requireValidReceipt(
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ) {
        if (
            sentPhysicalMs !in 1..SyncWireBounds.MaxSafeInteger ||
            receivedPhysicalMs !in 1..SyncWireBounds.MaxSafeInteger ||
            sentElapsedRealtimeMs < 0L || receivedElapsedRealtimeMs < sentElapsedRealtimeMs
        ) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
    }

    private fun receiptDifference(left: Long, right: Long): Long = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Local receipt timing is outside supported range")
    }

    private fun receiptDisagreement(physicalDeltaMs: Long, roundTripMs: Long): Long = try {
        val difference = Math.subtractExact(physicalDeltaMs, roundTripMs)
        if (difference == Long.MIN_VALUE) throw ArithmeticException()
        abs(difference)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Local receipt timing is outside supported range")
    }

    private fun receiptUncertainty(halfRoundTripMs: Long, disagreementMs: Long): Long = try {
        Math.addExact(halfRoundTripMs, disagreementMs)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Local receipt timing is outside supported range")
    }

    private fun checkedTimeAdd(value: Long, increment: Long): Long = try {
        Math.addExact(value, increment)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Local receipt timing is outside supported range")
    }.also {
        if (it !in 0..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
    }

    private fun checkedTrustedTime(value: Long, increment: Long): Long = try {
        Math.addExact(value, increment)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Trusted time is outside supported range")
    }.also {
        require(it in 0..SyncWireBounds.MaxSafeInteger) {
            "Trusted time is outside supported range"
        }
    }
}
