package me.egigoka.pomodorough.timer

import android.content.Context
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.TimerPhase

/** Localized phase name shared by the notification, the widget and the Quick Settings tile. */
internal fun phaseName(context: Context, phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> context.getString(R.string.short_break)
    TimerPhase.LongBreak -> context.getString(R.string.long_break)
    else -> context.getString(R.string.focus)
}
