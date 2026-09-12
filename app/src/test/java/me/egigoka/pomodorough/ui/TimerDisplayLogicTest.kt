package me.egigoka.pomodorough.ui

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun tickCountIsOnePerCeilingMinuteOfDisplayedDuration() {
        assertEquals(25, displayedTickCount(25 * 60_000L))
        assertEquals(5, displayedTickCount(5 * 60_000L))
        assertEquals(2, displayedTickCount(61_000L))
        assertEquals(2, displayedTickCount(90_000L))
        assertEquals(1, displayedTickCount(0L))
    }

    @Test
    fun finishedTimerHasNoClearableControlState() {
        val controls = timerControlState(heroState(completedTimer(TimerPhase.Focus, 25 * 60_000L)))

        assertFalse(controls.active)
        assertFalse(controls.hasActiveCompletionAlert)
    }

    @Test
    fun completionAlertSurvivesWithoutDismissControl() {
        val timer = completedTimer(TimerPhase.Focus, 25 * 60_000L)
        val controls = timerControlState(heroState(timer, activeCompletionAlertTimerId = timer.id))

        assertFalse(controls.active)
        assertTrue(controls.hasActiveCompletionAlert)
    }

    @Test
    fun runningTimerIsActiveWithoutAlert() {
        val timer = completedTimer(TimerPhase.Focus, 25 * 60_000L).copy(status = TimerStatus.Running)
        val controls = timerControlState(heroState(timer))

        assertTrue(controls.active)
        assertFalse(controls.hasActiveCompletionAlert)
    }

    @Test
    fun selectorTitleFollowsSelectedTask() {
        val tasks = listOf(FocusTask(id = "task-1", title = "Deep work"))

        assertEquals("Deep work", taskSelectorTitle(tasks, "task-1", "No task"))
        assertEquals("No task", taskSelectorTitle(tasks, null, "No task"))
        assertEquals("No task", taskSelectorTitle(tasks, "missing", "No task"))
    }

    private fun heroState(timer: CanonicalTimer?, activeCompletionAlertTimerId: String? = null) =
        TimerHeroState(
            timer = timer,
            activeCompletionAlertTimerId = activeCompletionAlertTimerId,
            settings = TimerSettings(),
            longBreakProgress = 0,
            ready = true,
            tasks = emptyList(),
            selectedTaskId = null,
            mutationsEnabled = true,
        )

    private fun completedTimer(phase: String, durationMs: Long) = CanonicalTimer(
        id = "timer-1",
        phase = phase,
        status = TimerStatus.Completed,
        plannedDurationMs = durationMs,
        anchorAt = "2026-08-20T00:25:00Z",
        elapsedAtAnchorMs = durationMs,
    )
}
