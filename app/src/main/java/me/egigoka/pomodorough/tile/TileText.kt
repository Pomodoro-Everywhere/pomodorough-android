package me.egigoka.pomodorough.tile

import android.content.Context
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import me.egigoka.pomodorough.timer.phaseName

/** Localized tile strings. Phase names and the paused wording match the home-screen widget. */
internal object TileText {
    fun label(context: Context, label: TileLabel): String = when (label) {
        is TileLabel.Phase -> phaseName(context, label.phase)
        TileLabel.AppName -> context.getString(R.string.tile_label)
    }

    fun subtitle(context: Context, subtitle: TileSubtitle): String = when (subtitle) {
        is TileSubtitle.TimeLeft ->
            context.getString(R.string.tile_subtitle_left, TimerSurfaceTiming.formatClock(subtitle.remainingMs))
        is TileSubtitle.Paused ->
            context.getString(R.string.widget_status_paused, TimerSurfaceTiming.formatClock(subtitle.remainingMs))
        TileSubtitle.Ready -> context.getString(R.string.widget_status_ready)
    }
}
