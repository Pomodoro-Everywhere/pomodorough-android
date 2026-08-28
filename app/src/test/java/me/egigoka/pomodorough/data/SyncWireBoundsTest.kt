package me.egigoka.pomodorough.data

import java.time.Instant
import me.egigoka.pomodorough.core.SharedCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncWireBoundsTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val hlc by lazy {
        CoreHlcDispatcher { operation, input -> core.dispatch(operation, input) }
    }

    @Test
    fun exactWireAndSkewBoundariesAreAccepted() {
        val maximum = SyncWireBounds.MaxSafeInteger
        SyncWireBounds.requirePersistedState(maximum, maximum, maximum)
        SyncWireBounds.requireOperationClock(
            occurredAt = Instant.ofEpochMilli(maximum - SyncWireBounds.MaxClockSkewMs).toString(),
            wallMs = maximum,
            counter = maximum,
            allowLegacySentinel = false,
        )
        SyncWireBounds.requireOperationClock(
            occurredAt = Instant.EPOCH.toString(),
            wallMs = 0,
            counter = 0,
            allowLegacySentinel = true,
        )
    }

    @Test
    fun twoStampReservationAcceptsExactSequenceAndCounterHeadroom() {
        val maximum = SyncWireBounds.MaxSafeInteger

        val stamps = reserve(
            nowMs = 100,
            retainedWallMs = 100,
            retainedCounter = maximum - 2,
            retainedDeviceSequence = maximum - 2,
            count = 2,
            withDeviceSequences = true,
        )

        assertEquals(listOf(maximum - 1, maximum), stamps.map { it.deviceSequence })
        assertEquals(listOf(maximum - 1, maximum), stamps.map { it.counter })
    }

    @Test
    fun reservationRejectsSequenceCounterAndSkewOverflow() {
        val maximum = SyncWireBounds.MaxSafeInteger
        assertThrows(IllegalArgumentException::class.java) {
            reserve(100, 100, 0, maximum, 1, withDeviceSequences = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reserve(100, 100, maximum, 0, 1, withDeviceSequences = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reserve(
                nowMs = 100,
                retainedWallMs = 100 + SyncWireBounds.MaxClockSkewMs + 1,
                retainedCounter = 0,
                retainedDeviceSequence = 0,
                count = 1,
                withDeviceSequences = false,
            )
        }
    }

    @Test
    fun generatedOperationCannotUseLegacySentinelOrExceedOccurrenceSkew() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncWireBounds.requireOperationClock(
                occurredAt = Instant.EPOCH.toString(),
                wallMs = 0,
                counter = 0,
                allowLegacySentinel = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncWireBounds.requireOperationClock(
                occurredAt = Instant.ofEpochMilli(400_001).toString(),
                wallMs = 100_000,
                counter = 0,
                allowLegacySentinel = false,
            )
        }
    }

    @Test
    fun legacySentinelRequiresExactUnixEpoch() {
        listOf(
            "1970-01-01T00:00:00.000000001Z",
            "1970-01-01T00:00:00.000999999Z",
        ).forEach { occurredAt ->
            assertThrows(IllegalArgumentException::class.java) {
                SyncWireBounds.requireOperationClock(
                    occurredAt = occurredAt,
                    wallMs = 0,
                    counter = 0,
                    allowLegacySentinel = true,
                )
            }
        }
    }

    @Test
    fun coreTickRebasesPoisonedLocalWallToTrustedServerTime() {
        assertEquals(
            CoreHlc(100L, 8L),
            hlc.tick(
                physicalNowMs = 100,
                local = CoreHlc(0, 0),
                remote = CoreHlc(100, 7),
            ),
        )
    }

    private fun reserve(
        nowMs: Long,
        retainedWallMs: Long,
        retainedCounter: Long,
        retainedDeviceSequence: Long,
        count: Int,
        withDeviceSequences: Boolean,
    ): List<SyncWireBounds.MutationStamp> {
        val clocks = hlc.reserve(
            physicalNowMs = nowMs,
            retained = CoreHlc(retainedWallMs, retainedCounter),
            count = count,
        )
        return SyncWireBounds.mutationStamps(
            nowMs,
            clocks,
            retainedDeviceSequence,
            withDeviceSequences,
        )
    }
}
