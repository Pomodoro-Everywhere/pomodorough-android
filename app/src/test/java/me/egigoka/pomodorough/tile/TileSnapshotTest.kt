package me.egigoka.pomodorough.tile

import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSnapshotTest {
    @Test
    fun runningTimerIsActiveWithTimeLeft() {
        val snapshot = TileSnapshot.from(TimerSurfaceTiming.Running(endsAtMs = 100_000), nowMs = 25_500)

        assertEquals(TileActivation.Active, snapshot.activation)
        assertEquals(TileSubtitle.TimeLeft(remainingMs = 74_500), snapshot.subtitle)
        assertEquals("01:15", TimerSurfaceTiming.formatClock(74_500))
    }

    @Test
    fun expiredRunningTimerClampsToZero() {
        val snapshot = TileSnapshot.from(TimerSurfaceTiming.Running(endsAtMs = 100_000), nowMs = 200_000)

        assertEquals(TileSnapshot(TileActivation.Active, TileSubtitle.TimeLeft(0)), snapshot)
    }

    @Test
    fun pausedTimerIsInactiveWithPausedSubtitle() {
        val snapshot = TileSnapshot.from(TimerSurfaceTiming.Paused(remainingMs = 30_000), nowMs = 0)

        assertEquals(TileSnapshot(TileActivation.Inactive, TileSubtitle.Paused), snapshot)
    }

    @Test
    fun idleIsInactiveAndReady() {
        val snapshot = TileSnapshot.from(TimerSurfaceTiming.Idle, nowMs = 0)

        assertEquals(TileSnapshot(TileActivation.Inactive, TileSubtitle.Ready), snapshot)
    }
}
