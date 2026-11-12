package me.egigoka.pomodorough.widget

import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetSnapshotTest {
    private val anchorMs = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    @Test
    fun runningTimerShowsItsPhaseTaskAndEndTime() {
        val state = AppState(
            timer = timer(TimerStatus.Running, TimerPhase.ShortBreak, taskId = "task-b"),
            tasks = listOf(FocusTask("task-a", "Write"), FocusTask("task-b", "Review")),
            selectedTaskId = "task-a",
            settings = TimerSettings(selectedPhase = TimerPhase.Focus),
        )

        val snapshot = WidgetSnapshot.from(state, nowMs = anchorMs + 10_000)

        assertEquals(TimerPhase.ShortBreak, snapshot.phase)
        assertEquals("Review", snapshot.taskTitle)
        assertEquals(TimerSurfaceTiming.Running(endsAtMs = anchorMs + 60_000), snapshot.timing)
    }

    @Test
    fun pausedTimerReportsRemainingMillis() {
        val state = AppState(timer = timer(TimerStatus.Paused, TimerPhase.Focus, elapsedAtAnchorMs = 15_000))

        val snapshot = WidgetSnapshot.from(state, nowMs = anchorMs + 999_000)

        assertEquals(TimerSurfaceTiming.Paused(remainingMs = 45_000), snapshot.timing)
    }

    @Test
    fun idleUsesSelectedPhaseAndSelectedTaskFromKnownTasks() {
        val state = AppState(
            timer = timer(TimerStatus.Completed, TimerPhase.Focus, taskId = "task-a"),
            knownTasks = listOf(FocusTask("task-c", "Archived task")),
            selectedTaskId = "task-c",
            settings = TimerSettings(selectedPhase = TimerPhase.LongBreak),
        )

        val snapshot = WidgetSnapshot.from(state, nowMs = anchorMs)

        assertEquals(TimerPhase.LongBreak, snapshot.phase)
        assertEquals("Archived task", snapshot.taskTitle)
        assertEquals(TimerSurfaceTiming.Idle, snapshot.timing)
    }

    @Test
    fun missingTaskLeavesTitleNull() {
        val snapshot = WidgetSnapshot.from(AppState(selectedTaskId = "ghost"), nowMs = anchorMs)

        assertNull(snapshot.taskTitle)
        assertEquals(TimerSurfaceTiming.Idle, snapshot.timing)
    }

    private fun timer(
        status: String,
        phase: String,
        taskId: String? = null,
        elapsedAtAnchorMs: Long = 0,
    ) = CanonicalTimer(
        id = "t1",
        phase = phase,
        status = status,
        plannedDurationMs = 60_000,
        elapsedAtAnchorMs = elapsedAtAnchorMs,
        anchorAt = "2026-01-01T00:00:00Z",
        taskId = taskId,
    )
}
