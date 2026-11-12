package me.egigoka.pomodorough.timer

import java.util.Locale
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.domain.TimerPresentation

/** Timer progress as shown by the home-screen widget and the Quick Settings tile. */
internal sealed interface TimerSurfaceTiming {
    data class Running(val endsAtMs: Long) : TimerSurfaceTiming
    data class Paused(val remainingMs: Long) : TimerSurfaceTiming
    data object Idle : TimerSurfaceTiming

    companion object {
        fun from(timer: CanonicalTimer?, nowMs: Long): TimerSurfaceTiming {
            if (timer == null) return Idle
            val remainingMs = timer.plannedDurationMs - TimerPresentation.elapsedAt(timer, nowMs)
            return when (timer.status) {
                TimerStatus.Running -> Running(endsAtMs = nowMs + remainingMs.coerceAtLeast(0))
                TimerStatus.Paused -> Paused(remainingMs = remainingMs.coerceAtLeast(0))
                else -> Idle
            }
        }

        /** Formats a duration as MM:SS, matching the in-app countdown. */
        fun formatClock(durationMs: Long): String {
            val totalSeconds = (durationMs.coerceAtLeast(0) + 999) / 1_000
            return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }
}
