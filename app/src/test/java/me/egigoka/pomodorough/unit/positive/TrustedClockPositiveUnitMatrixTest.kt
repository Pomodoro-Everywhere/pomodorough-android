package me.egigoka.pomodorough.unit.positive

import java.time.Instant
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.ServerClockSample
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.time.TrustedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedClockPositiveUnitMatrixTest {
    @Test
    fun persistedSampleContinuesMonotonicallyAcrossWallClockRollback() {
        var physicalMs = 1_000L
        var elapsedMs = 120L
        val clock = clock({ physicalMs }, { elapsedMs })
        val local = local().copy(
            serverClockOffsetMs = 500L,
            serverClockSamplePhysicalMs = 1_000L,
            serverClockSampleElapsedRealtimeMs = 100L,
        )

        assertEquals(1_520L, clock.now(local))
        physicalMs = 10L
        elapsedMs = 145L
        assertEquals(1_545L, clock.now(local))
        assertEquals(1_545L, clock.sampledNowOrNull(local))
    }

    @Test
    fun rebootFallbackInstallsAnchorAndThenIgnoresWallClockChanges() {
        var physicalMs = 2_000L
        var elapsedMs = 10L
        val clock = clock({ physicalMs }, { elapsedMs })
        val local = local().copy(serverClockOffsetMs = 250L)

        assertEquals(2_250L, clock.now(local))
        physicalMs = 1L
        elapsedMs = 25L

        assertEquals(2_265L, clock.now(local))
    }

    @Test
    fun installClearAndUnsampledFallbackUseTheirDistinctTimeSources() {
        var physicalMs = 9_000L
        var elapsedMs = 110L
        val clock = clock({ physicalMs }, { elapsedMs })
        val sample = ServerClockSample(0L, 5L, 10_000L, 9_995L, 100L)

        clock.install(sample)
        assertEquals(10_010L, clock.now(local()))
        clock.clear()
        physicalMs = 9_500L
        elapsedMs = 120L

        assertEquals(9_500L, clock.now(local()))
        assertNull(clock.sampledNowOrNull(local()))
    }

    @Test
    fun sampleRoundsOddRoundTripAndReportsClockDisagreement() {
        val sample = clock().sample(
            response = response(10_007L),
            sentPhysicalMs = 10_000L,
            sentElapsedRealtimeMs = 100L,
            receivedPhysicalMs = 10_009L,
            receivedElapsedRealtimeMs = 105L,
        )

        assertEquals(3L, sample.offsetMs)
        assertEquals(7L, sample.uncertaintyMs)
        assertEquals(10_004L, sample.midpointPhysicalMs)
        assertEquals(102L, sample.midpointElapsedRealtimeMs)
    }

    @Test
    fun stalenessBoundaryAndPhysicalDeltaAreInclusive() {
        var elapsedMs = 399_989L
        val clock = clock(elapsed = { elapsedMs })
        val sample = ServerClockSample(-25L, 11L, 1_000L, 1_025L, 100_000L)

        assertFalse(clock.isStale(sample))
        elapsedMs++
        assertTrue(clock.isStale(sample))
        assertEquals(25L, clock.responsePhysicalDelta(sample))
        assertEquals("boot-id", clock.bootId())
    }

    @Test
    fun matchingBootAndForwardElapsedKeepPersistedAnchor() {
        val persisted = local().copy(
            serverClockOffsetMs = 5L,
            serverClockSampleElapsedRealtimeMs = 100L,
            serverClockBootId = "boot-id",
        )

        assertNull(clock(elapsed = { 100L }).invalidateStaleElapsedAnchor(persisted))
    }

    private fun clock(
        physical: () -> Long = { 10_000L },
        elapsed: () -> Long = { 100L },
    ) = TrustedClock(physical, elapsed) { "boot-id" }

    private fun local() = LocalStateEntity(deviceId = "device", settingsJson = "{}")

    private fun response(serverMs: Long) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 0,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = Instant.ofEpochMilli(serverMs).toString(),
        serverHlcWallMs = serverMs,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )
}
