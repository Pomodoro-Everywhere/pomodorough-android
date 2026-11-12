package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isFinite
import kotlin.math.max
import kotlin.math.roundToInt

// Bottom edge of the primary timer control inside the landscape controls.
// The controls wrap, so their own height does not say where that edge is.
internal val PrimaryTimerControlBottom = HorizontalAlignmentLine(::max)

internal fun Modifier.marksPrimaryTimerControlBottom(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(PrimaryTimerControlBottom to placeable.height)) {
        placeable.place(0, 0)
    }
}

/**
 * UX round 2, B1: the label, the countdown and the primary timer control share
 * the first landscape viewport. The label and the controls keep their own
 * height. The countdown gets the height that is left, down to its own minimum;
 * below that the column scrolls as before.
 */
@Composable
internal fun LandscapeFirstViewport(
    viewportHeight: Dp,
    spacing: Dp,
    label: @Composable () -> Unit,
    readout: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(contents = listOf(label, readout, controls)) { (labels, readouts, controlRows), constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val labelPlaceable = labels.firstOrNull()?.measure(loose)
        val controlsPlaceable = controlRows.single().measure(loose)
        val taken = (labelPlaceable?.let { it.height + gap } ?: 0) + gap + primaryControlBottom(controlsPlaceable)
        val readoutMeasurable = readouts.single()
        val readoutHeight = if (viewportHeight.isFinite) {
            max(viewportHeight.roundToPx() - taken, readoutMeasurable.minIntrinsicHeight(constraints.maxWidth))
        } else {
            Constraints.Infinity
        }
        val readoutPlaceable = readoutMeasurable.measure(loose.copy(maxHeight = readoutHeight))
        val stacked = listOfNotNull(labelPlaceable, readoutPlaceable, controlsPlaceable)
        layout(constraints.maxWidth, stacked.sumOf { it.height } + gap * (stacked.size - 1)) {
            var top = 0
            stacked.forEach { placeable ->
                placeable.placeRelative(0, top)
                top += placeable.height + gap
            }
        }
    }
}

internal fun primaryControlBottom(controls: Placeable): Int {
    val bottom = controls[PrimaryTimerControlBottom]
    return if (bottom == AlignmentLine.Unspecified) controls.height else bottom
}

// Scales the countdown line into the height its parent leaves, never below
// minScale. The intrinsic minimum reports the fully shrunk line, so a parent
// can tell how short the countdown may get.
internal fun Modifier.shrinkIntoHeight(minScale: Float): Modifier = this then ShrinkIntoHeightElement(minScale)

private data class ShrinkIntoHeightElement(val minScale: Float) : ModifierNodeElement<ShrinkIntoHeightNode>() {
    override fun create() = ShrinkIntoHeightNode(minScale)

    override fun update(node: ShrinkIntoHeightNode) {
        node.minScale = minScale
    }
}

private class ShrinkIntoHeightNode(var minScale: Float) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val scale = shrinkScale(placeable.height, constraints, minScale)
        return layout(placeable.width, (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        (measurable.minIntrinsicHeight(width) * minScale).roundToInt()
}

internal fun shrinkScale(naturalHeight: Int, constraints: Constraints, minScale: Float): Float {
    if (!constraints.hasBoundedHeight || naturalHeight <= 0) return 1f
    return (constraints.maxHeight.toFloat() / naturalHeight).coerceIn(minScale.coerceAtMost(1f), 1f)
}
