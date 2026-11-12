package me.egigoka.pomodorough.domain

import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerStatus

/**
 * The time a history row counts toward totals, as Core recorded it. Every row the app shows comes
 * from Core projection output: Core fills `elapsedMs` on each row, applying its own rule to rows
 * stored before Core 0.55.0, and the projection boundary rejects a row without it. The client
 * applies no rule of its own; a row without the field adds nothing.
 */
internal fun HistoryItem.countedElapsedMs(): Long = elapsedMs ?: 0L

/** A completed session that Core recorded as running for less than its plan. */
internal fun HistoryItem.finishedEarly(): Boolean =
    status == TimerStatus.Completed && elapsedMs != null && elapsedMs < plannedDurationMs

/**
 * The minutes a row ran, or null for a row without `elapsedMs`. Every client uses the same rule:
 * round half up, and show at least 1 minute for any time above zero, so 29 s shows as 1 min.
 * A row that ran for 0 ms shows 0 min.
 */
internal fun HistoryItem.elapsedMinutes(): Long? = elapsedMs?.let { elapsed ->
    if (elapsed > 0L) maxOf(1L, (elapsed + 30_000L) / 60_000L) else 0L
}
