package me.egigoka.pomodorough.data

import java.security.SecureRandom
import java.util.UUID

internal object UuidV7 {
    const val MaxTimestampMs = 281_474_976_710_655L
    const val MaxRandomHigh = 0x0fff
    const val MaxRandomLow = 0x3fff_ffff_ffff_ffffL

    data class Parts(
        val timestampMs: Long,
        val randomHigh: Int,
        val randomLow: Long,
    )

    private val secureRandom = SecureRandom()
    private val prefixes = listOf("command-", "task-operation-", "duration-operation-")

    fun parts(uuid: UUID): Parts {
        require(uuid.version() == 7 && uuid.variant() == 2) {
            "Persisted UUIDv7 cursor is invalid"
        }
        val timestampMs = (uuid.mostSignificantBits ushr 16) and MaxTimestampMs
        require(timestampMs in 1..MaxTimestampMs) {
            "UUIDv7 timestamp is outside supported range"
        }
        return Parts(
            timestampMs = timestampMs,
            randomHigh = (uuid.mostSignificantBits and MaxRandomHigh.toLong()).toInt(),
            randomLow = uuid.leastSignificantBits and MaxRandomLow,
        )
    }

    fun make(
        timestampMs: Long,
        randomHigh: Int,
        randomLow: Long,
    ): UUID {
        require(
            timestampMs in 1..MaxTimestampMs &&
                randomHigh in 0..MaxRandomHigh &&
                randomLow in 0..MaxRandomLow,
        ) { "UUIDv7 components are outside supported range" }
        val mostSignificantBits = (timestampMs shl 16) or
            (7L shl 12) or
            randomHigh.toLong()
        val leastSignificantBits = Long.MIN_VALUE or randomLow
        return UUID(mostSignificantBits, leastSignificantBits)
    }

    fun reserve(
        timestampMs: Long,
        count: Int = 1,
        previous: UUID?,
        entropy: () -> ByteArray = ::secureEntropy,
    ): List<UUID> {
        require(timestampMs in 1..MaxTimestampMs && count > 0) {
            "UUIDv7 reservation is outside supported range"
        }
        if (previous != null) {
            val previousParts = parts(previous)
            if (timestampMs <= previousParts.timestampMs) {
                return sequential(
                    timestampMs = previousParts.timestampMs,
                    count = count,
                    afterHigh = previousParts.randomHigh,
                    afterLow = previousParts.randomLow,
                )
            }
        }
        repeat(16) {
            val random = entropy()
            require(random.size == 10) { "UUIDv7 entropy must contain 10 bytes" }
            val randomHigh = (
                ((random[0].toInt() and 0xff) shl 8) or
                    (random[1].toInt() and 0xff)
                ) and MaxRandomHigh
            var randomLow = 0L
            for (index in 2..9) {
                randomLow = (randomLow shl 8) or (random[index].toLong() and 0xff)
            }
            randomLow = randomLow and MaxRandomLow
            runCatching {
                return sequence(
                    timestampMs = timestampMs,
                    count = count,
                    firstHigh = randomHigh,
                    firstLow = randomLow,
                )
            }
        }
        throw IllegalArgumentException("UUIDv7 random tail has no batch headroom")
    }

    fun parse(value: String): UUID {
        val uuid = UUID.fromString(value)
        require(uuid.toString().equals(value, ignoreCase = true)) {
            "Persisted UUIDv7 cursor is malformed"
        }
        parts(uuid)
        return uuid
    }

    fun payload(identifier: String): UUID? {
        val prefix = prefixes.firstOrNull(identifier::startsWith)
        val value = prefix?.let(identifier::removePrefix) ?: identifier
        return runCatching { parse(value) }.getOrNull()
    }

    fun compare(left: UUID, right: UUID): Int {
        val leftParts = parts(left)
        val rightParts = parts(right)
        return compareValuesBy(
            leftParts,
            rightParts,
            Parts::timestampMs,
            Parts::randomHigh,
            Parts::randomLow,
        )
    }

    fun secureEntropy(): ByteArray = ByteArray(10).also(secureRandom::nextBytes)

    private fun sequential(
        timestampMs: Long,
        count: Int,
        afterHigh: Int,
        afterLow: Long,
    ): List<UUID> {
        var high = afterHigh
        var low = afterLow
        return List(count) {
            increment(high, low).also { next ->
                high = next.first
                low = next.second
            }.let { next ->
                make(timestampMs, next.first, next.second)
            }
        }
    }

    private fun sequence(
        timestampMs: Long,
        count: Int,
        firstHigh: Int,
        firstLow: Long,
    ): List<UUID> {
        var high = firstHigh
        var low = firstLow
        return List(count) { index ->
            if (index > 0) {
                increment(high, low).also { next ->
                    high = next.first
                    low = next.second
                }
            }
            make(timestampMs, high, low)
        }
    }

    private fun increment(high: Int, low: Long): Pair<Int, Long> {
        if (low < MaxRandomLow) return high to (low + 1)
        require(high < MaxRandomHigh) { "UUIDv7 random tail exhausted" }
        return (high + 1) to 0L
    }
}
