package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** Where the details and the duration of an Arrivals row go. */
internal enum class HistoryRowArrangement {
    /** Stamp, details and duration share one line. */
    Inline,

    /** The duration moves below the details, beside the stamp. */
    DurationBelow,

    /** The stamp and the duration share the first line; the details take the full width below. */
    DetailsBelowStamp,
}

/**
 * AND-23: picks the first arrangement in which the longest unbreakable word of
 * the details fits on a line. At large font scales a task name beside the
 * duration would otherwise break in the middle of a word.
 */
internal fun historyRowArrangement(
    rowWidth: Int,
    stampWidth: Int,
    stampGap: Int,
    durationWidth: Int,
    detailsLongestWord: Int,
): HistoryRowArrangement {
    val besideStamp = rowWidth - stampWidth - stampGap
    return when {
        detailsLongestWord <= besideStamp - durationWidth -> HistoryRowArrangement.Inline
        detailsLongestWord <= besideStamp -> HistoryRowArrangement.DurationBelow
        else -> HistoryRowArrangement.DetailsBelowStamp
    }
}

/** Lays out an Arrivals row in the [historyRowArrangement] that keeps every word whole. */
@Composable
internal fun HistoryRowLayout(
    stamp: @Composable () -> Unit,
    details: @Composable () -> Unit,
    duration: @Composable () -> Unit,
    stampGap: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(stamp, details, duration), modifier = modifier) { measurables, constraints ->
        val (stampMeasurables, detailsMeasurables, durationMeasurables) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val parts = HistoryRowParts(
            stamp = stampMeasurables.single().measure(loose),
            duration = durationMeasurables.single().measure(loose),
            details = detailsMeasurables.single(),
            gap = stampGap.roundToPx(),
        )
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else parts.inlineWidth()
        val arrangement = historyRowArrangement(
            rowWidth = rowWidth,
            stampWidth = parts.stamp.width,
            stampGap = parts.gap,
            durationWidth = parts.duration.width,
            detailsLongestWord = parts.details.minIntrinsicWidth(Constraints.Infinity),
        )
        when (arrangement) {
            HistoryRowArrangement.Inline -> placeInline(parts, rowWidth)
            HistoryRowArrangement.DurationBelow -> placeDurationBelow(parts, rowWidth)
            HistoryRowArrangement.DetailsBelowStamp -> placeDetailsBelowStamp(parts, rowWidth)
        }
    }
}

private class HistoryRowParts(
    val stamp: Placeable,
    val duration: Placeable,
    val details: Measurable,
    val gap: Int,
) {
    fun inlineWidth(): Int = stamp.width + gap + details.maxIntrinsicWidth(Constraints.Infinity) + duration.width

    fun measureDetails(width: Int): Placeable = details.measure(Constraints.fixedWidth(width.coerceAtLeast(0)))
}

private fun MeasureScope.placeInline(parts: HistoryRowParts, rowWidth: Int): MeasureResult {
    val detailsX = parts.stamp.width + parts.gap
    val details = parts.measureDetails(rowWidth - detailsX - parts.duration.width)
    val height = maxOf(parts.stamp.height, details.height, parts.duration.height)
    return layout(rowWidth, height) {
        parts.stamp.placeRelative(0, centered(height, parts.stamp.height))
        details.placeRelative(detailsX, centered(height, details.height))
        parts.duration.placeRelative(rowWidth - parts.duration.width, centered(height, parts.duration.height))
    }
}

private fun MeasureScope.placeDurationBelow(parts: HistoryRowParts, rowWidth: Int): MeasureResult {
    val detailsX = parts.stamp.width + parts.gap
    val details = parts.measureDetails(rowWidth - detailsX)
    val columnHeight = details.height + parts.duration.height
    val height = maxOf(parts.stamp.height, columnHeight)
    val columnY = centered(height, columnHeight)
    return layout(rowWidth, height) {
        parts.stamp.placeRelative(0, centered(height, parts.stamp.height))
        details.placeRelative(detailsX, columnY)
        parts.duration.placeRelative(rowWidth - parts.duration.width, columnY + details.height)
    }
}

private fun MeasureScope.placeDetailsBelowStamp(parts: HistoryRowParts, rowWidth: Int): MeasureResult {
    val details = parts.measureDetails(rowWidth)
    val firstLineHeight = maxOf(parts.stamp.height, parts.duration.height)
    val detailsY = firstLineHeight + parts.gap
    return layout(rowWidth, detailsY + details.height) {
        parts.stamp.placeRelative(0, centered(firstLineHeight, parts.stamp.height))
        parts.duration.placeRelative(
            rowWidth - parts.duration.width,
            centered(firstLineHeight, parts.duration.height),
        )
        details.placeRelative(0, detailsY)
    }
}

private fun centered(containerHeight: Int, childHeight: Int): Int = (containerHeight - childHeight) / 2
