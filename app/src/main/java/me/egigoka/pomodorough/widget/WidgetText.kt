package me.egigoka.pomodorough.widget

import android.content.Context
import android.text.format.DateFormat
import java.util.Date
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.timer.TimerSurfaceTiming

/** Localized widget strings. Wall-clock formatting is injectable so tests stay deterministic. */
internal object WidgetText {
    fun phaseLabel(context: Context, phase: String): String = when (phase) {
        TimerPhase.ShortBreak -> context.getString(R.string.short_break)
        TimerPhase.LongBreak -> context.getString(R.string.long_break)
        else -> context.getString(R.string.focus)
    }

    fun taskTitle(context: Context, snapshot: WidgetSnapshot): String =
        snapshot.taskTitle ?: context.getString(R.string.no_task)

    fun status(
        context: Context,
        timing: TimerSurfaceTiming,
        wallClock: (Long) -> String = { DateFormat.getTimeFormat(context).format(Date(it)) },
    ): String = when (timing) {
        is TimerSurfaceTiming.Running ->
            context.getString(R.string.widget_status_ends_at, wallClock(timing.endsAtMs))
        is TimerSurfaceTiming.Paused -> context.getString(
            R.string.widget_status_paused,
            TimerSurfaceTiming.formatClock(timing.remainingMs),
        )
        TimerSurfaceTiming.Idle -> context.getString(R.string.widget_status_ready)
    }

    fun toggleLabel(context: Context, timing: TimerSurfaceTiming): String = when (timing) {
        is TimerSurfaceTiming.Running -> context.getString(R.string.pause)
        is TimerSurfaceTiming.Paused -> context.getString(R.string.resume)
        TimerSurfaceTiming.Idle -> context.getString(R.string.widget_start)
    }
}
