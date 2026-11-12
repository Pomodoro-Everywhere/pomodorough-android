package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal class TimerDialContent(
    val ring: @Composable () -> Unit,
    val countdown: @Composable () -> Unit,
    val phase: @Composable () -> Unit,
    val statusChip: @Composable () -> Unit,
    val progressBar: @Composable () -> Unit,
)

/**
 * UX round 2, B2 (AND-25): the ring holds the countdown, the phase and the
 * status chip only while they fit inside it. At the largest text on a small
 * phone they do not, so the dial stacks the countdown, the phase and a
 * straight progress bar instead. The status chip then shows only while it
 * fits beside the phase; the dial description always carries the status.
 */
@Composable
internal fun TimerDialLayout(ringSize: Dp, ringStroke: Dp, content: TimerDialContent, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(content.ring, content.countdown, content.phase, content.statusChip, content.progressBar),
        modifier = modifier,
    ) { (rings, countdowns, phases, chips, bars), constraints ->
        val labels = DialLabels(countdowns.single(), phases.single(), chips.single())
        val ringPx = ringSize.roundToPx()
        if (ringHoldsLabels(ringPx, ringStroke.roundToPx(), labels.naturalSize(ChipGap.roundToPx()))) {
            ringDial(rings.single(), labels, ringPx)
        } else {
            stackedDial(labels, bars.single(), constraints.maxWidth)
        }
    }
}

private val ChipGap = 8.dp
private val StackedRowGap = 6.dp

private class DialLabels(val countdown: Measurable, val phase: Measurable, val statusChip: Measurable) {
    // Each label on one line, stacked as the ring shows them.
    fun naturalSize(chipGap: Int): IntSize {
        val all = listOf(countdown, phase, statusChip)
        return IntSize(
            width = all.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) },
            height = all.sumOf { it.maxIntrinsicHeight(Constraints.Infinity) } + chipGap,
        )
    }
}

// The labels sit centred in the ring, so they fit when their box stays
// inside the ring's stroke on both axes.
internal fun ringHoldsLabels(ringDiameter: Int, strokeWidth: Int, labels: IntSize): Boolean {
    val inner = ringDiameter - 2 * strokeWidth
    return labels.width <= inner && labels.height <= inner
}

private fun MeasureScope.ringDial(ring: Measurable, labels: DialLabels, ringPx: Int): MeasureResult {
    val inRing = Constraints(maxWidth = ringPx, maxHeight = ringPx)
    val ringPlaceable = ring.measure(Constraints.fixed(ringPx, ringPx))
    val countdown = labels.countdown.measure(inRing)
    val phase = labels.phase.measure(inRing)
    val chip = labels.statusChip.measure(inRing)
    val chipGap = ChipGap.roundToPx()
    return layout(ringPx, ringPx) {
        ringPlaceable.placeRelative(0, 0)
        var top = (ringPx - countdown.height - phase.height - chipGap - chip.height) / 2
        countdown.placeRelative((ringPx - countdown.width) / 2, top)
        top += countdown.height
        phase.placeRelative((ringPx - phase.width) / 2, top)
        top += phase.height + chipGap
        chip.placeRelative((ringPx - chip.width) / 2, top)
    }
}

private fun MeasureScope.stackedDial(labels: DialLabels, progressBar: Measurable, width: Int): MeasureResult {
    val inWidth = Constraints(maxWidth = width)
    val countdown = labels.countdown.measure(inWidth)
    val phase = labels.phase.measure(inWidth)
    val chipGap = ChipGap.roundToPx()
    val chipFits = phase.width + chipGap + labels.statusChip.maxIntrinsicWidth(Constraints.Infinity) <= width
    val chip = if (chipFits) labels.statusChip.measure(inWidth) else null
    val bar = progressBar.measure(Constraints.fixedWidth(width))
    val rowGap = StackedRowGap.roundToPx()
    val captionWidth = phase.width + (chip?.let { chipGap + it.width } ?: 0)
    val captionHeight = max(phase.height, chip?.height ?: 0)
    return layout(width, countdown.height + rowGap + captionHeight + rowGap + bar.height) {
        countdown.placeRelative((width - countdown.width) / 2, 0)
        val captionTop = countdown.height + rowGap
        val captionLeft = (width - captionWidth) / 2
        phase.placeRelative(captionLeft, captionTop + (captionHeight - phase.height) / 2)
        chip?.placeRelative(captionLeft + phase.width + chipGap, captionTop + (captionHeight - chip.height) / 2)
        bar.placeRelative(0, captionTop + captionHeight + rowGap)
    }
}
