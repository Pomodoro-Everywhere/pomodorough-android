package me.egigoka.pomodorough.tile

import me.egigoka.pomodorough.timer.TimerSurfaceTiming

/** Tile activation and subtitle, independent of Android tile classes so tests stay on the JVM. */
internal enum class TileActivation { Active, Inactive }

internal sealed interface TileSubtitle {
    data class TimeLeft(val remainingMs: Long) : TileSubtitle
    data object Paused : TileSubtitle
    data object Ready : TileSubtitle
}

internal data class TileSnapshot(
    val activation: TileActivation,
    val subtitle: TileSubtitle,
) {
    companion object {
        fun from(timing: TimerSurfaceTiming, nowMs: Long): TileSnapshot = when (timing) {
            is TimerSurfaceTiming.Running -> TileSnapshot(
                activation = TileActivation.Active,
                subtitle = TileSubtitle.TimeLeft((timing.endsAtMs - nowMs).coerceAtLeast(0)),
            )
            is TimerSurfaceTiming.Paused -> TileSnapshot(TileActivation.Inactive, TileSubtitle.Paused)
            TimerSurfaceTiming.Idle -> TileSnapshot(TileActivation.Inactive, TileSubtitle.Ready)
        }
    }
}
