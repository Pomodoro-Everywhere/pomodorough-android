package me.egigoka.pomodorough.widget

import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.timer.TimerSurfaceTiming

/** Everything the home-screen widget renders, derived from published repository state. */
internal data class WidgetSnapshot(
    val phase: String,
    val taskTitle: String?,
    val timing: TimerSurfaceTiming,
) {
    companion object {
        fun from(state: AppState, nowMs: Long): WidgetSnapshot {
            val timer = state.timer
            val activeTimer = timer?.takeIf {
                it.status == TimerStatus.Running || it.status == TimerStatus.Paused
            }
            val taskId = activeTimer?.taskId ?: state.selectedTaskId
            return WidgetSnapshot(
                phase = activeTimer?.phase ?: state.settings.selectedPhase,
                taskTitle = taskId?.let { id ->
                    (state.tasks + state.knownTasks).firstOrNull { it.id == id }?.title
                },
                timing = TimerSurfaceTiming.from(timer, nowMs),
            )
        }
    }
}
