package me.egigoka.pomodorough.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncWireBoundsTest {
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

        val stamps = SyncWireBounds.reserve(
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
            SyncWireBounds.reserve(100, 100, 0, maximum, 1, withDeviceSequences = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncWireBounds.reserve(100, 100, maximum, 0, 1, withDeviceSequences = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncWireBounds.reserve(
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
    fun mergeRebasesPoisonedLocalWallToTrustedServerTime() {
        assertEquals(
            100L to 7L,
            SyncWireBounds.merge(
                nowMs = 100,
                localWallMs = 3_600_100,
                localCounter = SyncWireBounds.MaxSafeInteger,
                serverWallMs = 100,
                serverCounter = 7,
            ),
        )
    }
}
