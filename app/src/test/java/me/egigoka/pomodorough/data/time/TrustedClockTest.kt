package me.egigoka.pomodorough.data.time

import java.time.Instant
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.ServerClockSample
import me.egigoka.pomodorough.data.SyncProtocolException
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedClockTest {
    @Test
    fun samplePreservesRequestTimingMath() {
        val sample = clock().sample(
            response = response(serverTimeMs = 10_010L),
            sentPhysicalMs = 10_000L,
            sentElapsedRealtimeMs = 100L,
            receivedPhysicalMs = 10_020L,
            receivedElapsedRealtimeMs = 110L,
        )

        assertEquals(10_010L, sample.serverTimeMs)
        assertEquals(0L, sample.offsetMs)
        assertEquals(15L, sample.uncertaintyMs)
        assertEquals(10_010L, sample.midpointPhysicalMs)
        assertEquals(105L, sample.midpointElapsedRealtimeMs)
    }

    @Test
    fun sampleValidatesServerBeforeReceiptTiming() {
        val error = assertThrows(SyncProtocolException::class.java) {
            clock().sample(
                response = response(serverTime = "bad"),
                sentPhysicalMs = -1L,
                sentElapsedRealtimeMs = -1L,
                receivedPhysicalMs = -1L,
                receivedElapsedRealtimeMs = -2L,
            )
        }

        assertEquals("Server returned an invalid server timestamp", error.message)
    }

    @Test
    fun trustedTimeContinuesFromElapsedAnchorAcrossWallClockChange() {
        var physicalMs = 1_000L
        var elapsedMs = 120L
        val clock = clock(
            physicalTimeMillis = { physicalMs },
            elapsedRealtimeMillis = { elapsedMs },
        )
        val local = localState().copy(
            serverClockSamplePhysicalMs = 1_000L,
            serverClockOffsetMs = 500L,
            serverClockSampleElapsedRealtimeMs = 100L,
        )

        assertEquals(1_520L, clock.now(local))
        physicalMs = 10L
        elapsedMs = 145L
        assertEquals(1_545L, clock.now(local))
    }

    @Test
    fun stalePersistedAnchorPreservesValidatedOffsetForRecovery() {
        val local = localState().copy(
            serverClockOffsetMs = 500L,
            serverClockUncertaintyMs = 10L,
            serverClockSamplePhysicalMs = 1_000L,
            serverClockSampleElapsedRealtimeMs = 100L,
            serverClockBootId = "old-boot",
        )

        val cleared = clock(bootId = { "new-boot" }).invalidateStaleElapsedAnchor(local)

        assertTrue(cleared != null)
        assertEquals(500L, cleared?.serverClockOffsetMs)
        assertEquals(10L, cleared?.serverClockUncertaintyMs)
        assertNull(cleared?.serverClockSamplePhysicalMs)
        assertNull(cleared?.serverClockSampleElapsedRealtimeMs)
        assertNull(cleared?.serverClockBootId)
    }

    @Test
    fun restartedClockAcceptsExactUncertaintyAdjustedUpperBound() {
        val uncertaintyMs = 10L
        val retainedWallMs = 1_000L
        val candidateMs = retainedWallMs + SyncWireBounds.MaxClockSkewMs - uncertaintyMs
        val local = localState().copy(
            hlcWallMs = retainedWallMs,
            serverClockOffsetMs = 500L,
            serverClockUncertaintyMs = uncertaintyMs,
        )

        val result = clock(
            physicalTimeMillis = { candidateMs - 500L },
            elapsedRealtimeMillis = { 1L },
        ).now(local, retainedWallMs = retainedWallMs)

        assertEquals(candidateMs, result)
    }

    @Test
    fun restartedClockRejectsCandidateBeyondUncertaintyAdjustedUpperBound() {
        val uncertaintyMs = 10L
        val retainedWallMs = 1_000L
        val candidateMs = retainedWallMs + SyncWireBounds.MaxClockSkewMs - uncertaintyMs + 1L
        val local = localState().copy(
            hlcWallMs = retainedWallMs,
            serverClockOffsetMs = 500L,
            serverClockUncertaintyMs = uncertaintyMs,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            clock(
                physicalTimeMillis = { candidateMs - 500L },
                elapsedRealtimeMillis = { 1L },
            ).now(local, retainedWallMs = retainedWallMs)
        }

        assertEquals("Trusted time requires a fresh server sample", error.message)
    }

    @Test
    fun restartedClockClampsBackwardWallJumpToRetainedClock() {
        val local = localState().copy(
            hlcWallMs = 1_000L,
            serverClockOffsetMs = 500L,
            serverClockUncertaintyMs = 10L,
        )

        val result = clock(
            physicalTimeMillis = { 1L },
            elapsedRealtimeMillis = { 1L },
        ).now(local, retainedWallMs = 1_000L)

        assertEquals(1_000L, result)
    }

    @Test
    fun usablePersistedAnchorRemainsUnchanged() {
        val local = localState().copy(
            serverClockSampleElapsedRealtimeMs = 100L,
            serverClockBootId = "same-boot",
        )

        val replacement = clock(
            elapsedRealtimeMillis = { 101L },
            bootId = { "same-boot" },
        ).invalidateStaleElapsedAnchor(local)

        assertNull(replacement)
    }

    @Test
    fun advanceUsesElapsedTimeAndRetainsLargestUncertainty() {
        val sample = ServerClockSample(0L, 15L, 10_010L, 10_010L, 105L)

        val advanced = clock().advance(
            sample = sample,
            response = response(serverTimeMs = 10_030L),
            sentPhysicalMs = 10_020L,
            sentElapsedRealtimeMs = 110L,
            receivedPhysicalMs = 10_040L,
            receivedElapsedRealtimeMs = 130L,
        )

        assertEquals(10_035L, advanced.serverTimeMs)
        assertEquals(-5L, advanced.offsetMs)
        assertEquals(15L, advanced.uncertaintyMs)
        assertEquals(10_040L, advanced.midpointPhysicalMs)
        assertEquals(130L, advanced.midpointElapsedRealtimeMs)
    }

    private fun clock(
        physicalTimeMillis: () -> Long = { 10_020L },
        elapsedRealtimeMillis: () -> Long = { 110L },
        bootId: () -> String? = { "boot" },
    ): TrustedClock = TrustedClock(physicalTimeMillis, elapsedRealtimeMillis, bootId)

    private fun response(
        serverTimeMs: Long = 10_010L,
        serverTime: String = Instant.ofEpochMilli(serverTimeMs).toString(),
    ): SyncResponse = SyncResponse(
        acknowledgements = emptyList(),
        revision = 0L,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = serverTime,
        serverHlcWallMs = serverTimeMs,
        serverHlcCounter = 0L,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartAcknowledgements = emptyList(),
        autoStartBreaks = false,
        selectedTaskAcknowledgements = emptyList(),
        selectedTaskId = null,
    )

    private fun localState(): LocalStateEntity = LocalStateEntity(
        deviceId = "device",
        settingsJson = "{}",
    )
}
