package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Pure hinge geometry: window pixels in, content Dp out.
// Classpath-free apart from Dp so JVM unit tests cover every case.
data class HingeBoundsPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class WindowSizePx(
    val width: Int,
    val height: Int,
)

data class HingeFractions(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

data class FoldSplitWeights(
    val first: Float,
    val hinge: Float,
    val second: Float,
)

data class HingeInContent(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
)

// Fractions preserve off-center hinges and nonzero widths when the
// content size differs from the window size (insets, navigation).
internal fun hingeFractions(
    hinge: HingeBoundsPx?,
    window: WindowSizePx?,
): HingeFractions? {
    if (hinge == null || window == null) return null
    if (window.width <= 0 || window.height <= 0) return null
    val left = (hinge.left.coerceIn(0, window.width).toFloat() / window.width)
        .coerceIn(0f, 1f)
    val right = (hinge.right.coerceIn(0, window.width).toFloat() / window.width)
        .coerceIn(0f, 1f)
    val top = (hinge.top.coerceIn(0, window.height).toFloat() / window.height)
        .coerceIn(0f, 1f)
    val bottom = (hinge.bottom.coerceIn(0, window.height).toFloat() / window.height)
        .coerceIn(0f, 1f)
    if (right < left || bottom < top) return null
    return HingeFractions(left, right, top, bottom)
}

internal fun verticalSplitWeights(fractions: HingeFractions?): FoldSplitWeights? {
    if (fractions == null) return null
    val first = fractions.left.coerceIn(0f, 1f)
    val hinge = (fractions.right - fractions.left).coerceIn(0f, 1f)
    val second = (1f - fractions.right).coerceIn(0f, 1f)
    if (first <= 0f && second <= 0f) return null
    return FoldSplitWeights(first, hinge, second)
}

internal fun horizontalSplitWeights(fractions: HingeFractions?): FoldSplitWeights? {
    if (fractions == null) return null
    val first = fractions.top.coerceIn(0f, 1f)
    val hinge = (fractions.bottom - fractions.top).coerceIn(0f, 1f)
    val second = (1f - fractions.bottom).coerceIn(0f, 1f)
    if (first <= 0f && second <= 0f) return null
    return FoldSplitWeights(first, hinge, second)
}

// Window-to-content conversion: fractions times measured content size.
// Content size already excludes system bars and navigation, so the
// hinge lands proportionally without crossing insets or the rail/bar.
internal fun windowHingeToContent(
    hingePx: HingeBoundsPx?,
    windowPx: WindowSizePx?,
    contentWidth: Dp,
    contentHeight: Dp,
): HingeInContent? {
    val fractions = hingeFractions(hingePx, windowPx) ?: return null
    return HingeInContent(
        left = contentWidth * fractions.left,
        top = contentHeight * fractions.top,
        right = contentWidth * fractions.right,
        bottom = contentHeight * fractions.bottom,
    )
}

internal fun verticalPaneWidths(
    contentWidth: Dp,
    hinge: HingeInContent,
): Pair<Dp, Dp> {
    val left = hinge.left.coerceIn(0.dp, contentWidth)
    val right = (contentWidth - hinge.right).coerceAtLeast(0.dp)
    return left to right
}

internal fun horizontalPaneHeights(
    contentHeight: Dp,
    hinge: HingeInContent,
): Pair<Dp, Dp> {
    val top = hinge.top.coerceIn(0.dp, contentHeight)
    val bottom = (contentHeight - hinge.bottom).coerceAtLeast(0.dp)
    return top to bottom
}

// True when a control interval overlaps the hinge interval.
// Panes built from verticalPaneWidths/horizontalPaneHeights never do:
// first ends at hinge.start, second starts at hinge.end.
internal fun controlBoundsIntersectHinge(
    controlStart: Dp,
    controlEnd: Dp,
    hingeStart: Dp,
    hingeEnd: Dp,
): Boolean {
    if (controlEnd <= controlStart) return false
    if (hingeEnd <= hingeStart) return controlStart < hingeStart && controlEnd > hingeStart
    return maxOf(controlStart, hingeStart) < minOf(controlEnd, hingeEnd)
}
