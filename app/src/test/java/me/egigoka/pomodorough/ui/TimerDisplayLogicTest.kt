package me.egigoka.pomodorough.ui

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerDisplayLogicTest {
    @Test
    fun completedFocusDisplaysSelectedBreakAtFullDuration() {
        val timer = completedTimer(TimerPhase.Focus, 25 * 60_000L)
        val settings = TimerSettings(selectedPhase = TimerPhase.ShortBreak)

        assertEquals(TimerPhase.ShortBreak, displayPhase(timer, settings))
        assertEquals(5 * 60_000L, displayPlannedDurationMs(timer, settings))
    }

    @Test
    fun activeTimerKeepsItsCanonicalPhaseAndDuration() {
        val timer = completedTimer(TimerPhase.Focus, 42 * 60_000L).copy(status = TimerStatus.Paused)
        val settings = TimerSettings(selectedPhase = TimerPhase.ShortBreak)

        assertEquals(TimerPhase.Focus, displayPhase(timer, settings))
        assertEquals(42 * 60_000L, displayPlannedDurationMs(timer, settings))
    }

    private fun completedTimer(phase: String, durationMs: Long) = CanonicalTimer(
        id = "timer-1",
        phase = phase,
        status = TimerStatus.Completed,
        plannedDurationMs = durationMs,
        anchorAt = "2026-08-20T00:25:00Z",
        elapsedAtAnchorMs = durationMs,
    )
}
