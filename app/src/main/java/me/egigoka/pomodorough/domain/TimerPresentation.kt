package me.egigoka.pomodorough.domain

import java.time.Instant
import java.time.ZoneId
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus

object TimerPresentation {
    fun occursOnLocalDay(
        timestamp: String?,
        reference: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val instant = timestamp?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            ?: return false
        return instant.atZone(zoneId).toLocalDate() == reference.atZone(zoneId).toLocalDate()
    }

    fun completedFocusCountForDay(
        history: List<HistoryItem>,
        reference: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int = history.count {
        it.status == TimerStatus.Completed &&
            it.phase == TimerPhase.Focus &&
            occursOnLocalDay(it.completedAt ?: it.endedAt, reference, zoneId)
    }

    fun longBreakProgress(completedFocusCount: Int): Int =
        if (completedFocusCount <= 0) 0 else ((completedFocusCount - 1) % 4) + 1

    fun elapsedAt(timer: CanonicalTimer?, nowMs: Long = System.currentTimeMillis()): Long {
        if (timer == null) return 0
        var elapsed = timer.elapsedAtAnchorMs.coerceIn(0, timer.plannedDurationMs)
        if (timer.status == TimerStatus.Running) {
            val anchorMs = runCatching { Instant.parse(timer.anchorAt).toEpochMilli() }.getOrNull()
            if (anchorMs != null) elapsed += (nowMs - anchorMs).coerceAtLeast(0)
        }
        return elapsed.coerceIn(0, timer.plannedDurationMs)
    }
}
