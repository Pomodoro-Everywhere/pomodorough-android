package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import kotlin.math.max

/**
 * UX round 2, B2: a label with a switch beside it. When the width left beside
 * the switch squeezes the label into a tall column, the label takes the full
 * width and the switch moves below it.
 */
@Composable
internal fun LabelledSwitchLayout(
    spacing: Dp,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    switch: @Composable () -> Unit,
) {
    Layout(contents = listOf(label, switch), modifier = modifier) { (labels, switches), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = spacing.roundToPx()
        val switchPlaceable = switches.single().measure(loose)
        val labelMeasurable = labels.single()
        val widthBeside = (constraints.maxWidth - switchPlaceable.width - gap).coerceAtLeast(0)
        val stacked = constraints.hasBoundedWidth &&
            labelStacksOverSwitch(widthBeside, labelMeasurable.minIntrinsicHeight(widthBeside))
        val labelWidth = if (stacked) constraints.maxWidth else widthBeside
        val labelPlaceable = labelMeasurable.measure(loose.copy(maxWidth = labelWidth))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelPlaceable.width + gap + switchPlaceable.width
        if (stacked) {
            layout(width, labelPlaceable.height + gap + switchPlaceable.height) {
                labelPlaceable.placeRelative(0, 0)
                switchPlaceable.placeRelative(width - switchPlaceable.width, labelPlaceable.height + gap)
            }
        } else {
            val height = max(labelPlaceable.height, switchPlaceable.height)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                switchPlaceable.placeRelative(width - switchPlaceable.width, (height - switchPlaceable.height) / 2)
            }
        }
    }
}

// A label more than one and a half times as tall as it is wide reads as a column.
internal fun labelStacksOverSwitch(labelWidth: Int, labelHeight: Int): Boolean =
    labelHeight > labelWidth * ColumnLikeLabelRatio

private const val ColumnLikeLabelRatio = 1.5f
