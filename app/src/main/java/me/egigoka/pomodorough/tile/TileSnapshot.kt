package me.egigoka.pomodorough.tile

import me.egigoka.pomodorough.timer.TimerSurfaceTiming

/** Tile activation, label and subtitle, independent of Android tile classes so tests stay on the JVM. */
internal enum class TileActivation { Active, Inactive }

/** AND-33: a running or paused tile names its phase; an idle tile shows the app name. */
internal sealed interface TileLabel {
    data class Phase(val phase: String) : TileLabel
    data object AppName : TileLabel
}

/** AND-33: a paused tile keeps the time left, as the widget does. */
internal sealed interface TileSubtitle {
    data class TimeLeft(val remainingMs: Long) : TileSubtitle
    data class Paused(val remainingMs: Long) : TileSubtitle
    data object Ready : TileSubtitle
}

internal data class TileSnapshot(
    val activation: TileActivation,
    val label: TileLabel,
    val subtitle: TileSubtitle,
) {
    companion object {
        fun from(timing: TimerSurfaceTiming, phase: String?, nowMs: Long): TileSnapshot {
            val label = phase?.let(TileLabel::Phase) ?: TileLabel.AppName
            return when (timing) {
                is TimerSurfaceTiming.Running -> TileSnapshot(
                    activation = TileActivation.Active,
                    label = label,
                    subtitle = TileSubtitle.TimeLeft((timing.endsAtMs - nowMs).coerceAtLeast(0)),
                )
                is TimerSurfaceTiming.Paused ->
                    TileSnapshot(TileActivation.Inactive, label, TileSubtitle.Paused(timing.remainingMs))
                TimerSurfaceTiming.Idle -> TileSnapshot(TileActivation.Inactive, TileLabel.AppName, TileSubtitle.Ready)
            }
        }
    }
}
