package me.egigoka.pomodorough.tile

import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSnapshotTest {
    @Test
    fun runningTimerIsActiveWithItsPhaseAndTimeLeft() {
        val snapshot = TileSnapshot.from(
            TimerSurfaceTiming.Running(endsAtMs = 100_000),
            phase = TimerPhase.ShortBreak,
            nowMs = 25_500,
        )

        assertEquals(TileActivation.Active, snapshot.activation)
        assertEquals(TileLabel.Phase(TimerPhase.ShortBreak), snapshot.label)
        assertEquals(TileSubtitle.TimeLeft(remainingMs = 74_500), snapshot.subtitle)
        assertEquals("01:15", TimerSurfaceTiming.formatClock(74_500))
    }

    @Test
    fun expiredRunningTimerClampsToZero() {
        val snapshot = TileSnapshot.from(
            TimerSurfaceTiming.Running(endsAtMs = 100_000),
            phase = TimerPhase.Focus,
            nowMs = 200_000,
        )

        assertEquals(
            TileSnapshot(TileActivation.Active, TileLabel.Phase(TimerPhase.Focus), TileSubtitle.TimeLeft(0)),
            snapshot,
        )
    }

    // AND-33: the paused tile keeps its phase and the time left.
    @Test
    fun pausedTimerIsInactiveWithItsPhaseAndTimeLeft() {
        val snapshot = TileSnapshot.from(
            TimerSurfaceTiming.Paused(remainingMs = 30_000),
            phase = TimerPhase.Focus,
            nowMs = 0,
        )

        assertEquals(
            TileSnapshot(TileActivation.Inactive, TileLabel.Phase(TimerPhase.Focus), TileSubtitle.Paused(30_000)),
            snapshot,
        )
    }

    @Test
    fun idleIsInactiveAndReadyUnderTheAppName() {
        val snapshot = TileSnapshot.from(TimerSurfaceTiming.Idle, phase = TimerPhase.LongBreak, nowMs = 0)

        assertEquals(TileSnapshot(TileActivation.Inactive, TileLabel.AppName, TileSubtitle.Ready), snapshot)
    }
}
