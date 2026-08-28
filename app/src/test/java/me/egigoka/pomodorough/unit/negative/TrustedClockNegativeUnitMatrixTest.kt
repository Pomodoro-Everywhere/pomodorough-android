package me.egigoka.pomodorough.unit.negative

import java.time.Instant
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.ServerClockSample
import me.egigoka.pomodorough.data.SyncProtocolException
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.time.TrustedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedClockNegativeUnitMatrixTest {
    @Test
    fun malformedAndOutOfRangeServerTimesFailClosed() {
        val malformed = assertThrows(SyncProtocolException::class.java) {
            clock().validate(response(serverTime = "not-an-instant"))
        }
        val epoch = assertThrows(SyncProtocolException::class.java) {
            clock().validate(response(serverMs = 0L))
        }

        assertEquals("Server returned an invalid server timestamp", malformed.message)
        assertEquals("Server timestamp is outside supported range", epoch.message)
    }

    @Test
    fun serverTimestampAndHybridClockDisagreementIsRejected() {
        val error = assertThrows(SyncProtocolException::class.java) {
            clock().validate(response(serverMs = 1_000_000L, hlcWallMs = 1_300_001L))
        }

        assertEquals("Server HLC disagrees with server timestamp", error.message)
    }

    @Test
    fun receiptTimingRejectsInvalidPhysicalAndElapsedBounds() {
        val cases = listOf(
            longArrayOf(0L, 0L, 1L, 1L),
            longArrayOf(1L, -1L, 2L, 1L),
            longArrayOf(1L, 2L, 2L, 1L),
            longArrayOf(1L, 0L, SyncWireBounds.MaxSafeInteger + 1L, 1L),
        )

        cases.forEach { timing ->
            val error = assertThrows(SyncProtocolException::class.java) {
                clock().sample(response(1L), timing[0], timing[1], timing[2], timing[3])
            }
            assertEquals("Local receipt timing is outside supported range", error.message)
        }
    }

    @Test
    fun excessiveReceiptUncertaintyIsRejected() {
        val error = assertThrows(SyncProtocolException::class.java) {
            clock().sample(
                response = response(10_000L),
                sentPhysicalMs = 10_000L,
                sentElapsedRealtimeMs = 0L,
                receivedPhysicalMs = 10_000L,
                receivedElapsedRealtimeMs = 60_002L,
            )
        }

        assertEquals("Server clock sample uncertainty exceeds 30000ms", error.message)
    }

    @Test
    fun persistedClockAdditionOverflowIsRejected() {
        val local = local().copy(
            serverClockOffsetMs = Long.MAX_VALUE,
            serverClockSamplePhysicalMs = 1L,
            serverClockSampleElapsedRealtimeMs = 0L,
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            clock(elapsed = { 1L }).now(local)
        }

        assertEquals("Trusted time is outside supported range", error.message)
    }

    @Test
    fun explicitSampleRejectsBackwardElapsedWhileStaleInstalledAnchorFallsBack() {
        val local = local().copy(serverClockOffsetMs = 1L)
        val clock = clock(elapsed = { 9L })
        clock.install(ServerClockSample(0L, 0L, 100L, 100L, 10L))

        assertEquals(1_001L, clock.now(local))
        val explicit = assertThrows(IllegalArgumentException::class.java) {
            clock.now(local, ServerClockSample(0L, 0L, 100L, 100L, 10L))
        }

        assertEquals("Elapsed time moved backwards during request", explicit.message)
        assertEquals(101L, clock(elapsed = { 11L }).run {
            install(ServerClockSample(0L, 0L, 100L, 100L, 10L))
            now(local)
        })
    }

    @Test
    fun advanceRejectsReceiptBeforeBootstrapMidpoint() {
        val sample = ServerClockSample(0L, 1L, 1_000L, 1_000L, 50L)
        val error = assertThrows(SyncProtocolException::class.java) {
            clock().advance(sample, response(1_000L), 1_000L, 40L, 1_005L, 45L)
        }

        assertEquals("Local receipt timing is outside supported range", error.message)
    }

    @Test
    fun minimumLongOffsetCannotBeNegated() {
        val sample = ServerClockSample(Long.MIN_VALUE, 0L, 1L, 1L, 0L)
        val error = assertThrows(SyncProtocolException::class.java) {
            clock().responsePhysicalDelta(sample)
        }

        assertEquals("Server clock offset is outside supported range", error.message)
    }

    private fun clock(
        physical: () -> Long = { 1_000L },
        elapsed: () -> Long = { 0L },
    ) = TrustedClock(physical, elapsed) { "boot" }

    private fun local() = LocalStateEntity(deviceId = "device", settingsJson = "{}")

    private fun response(
        serverMs: Long = 1_000L,
        serverTime: String = Instant.ofEpochMilli(serverMs).toString(),
        hlcWallMs: Long = serverMs,
    ) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 0,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = serverTime,
        serverHlcWallMs = hlcWallMs,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )
}
