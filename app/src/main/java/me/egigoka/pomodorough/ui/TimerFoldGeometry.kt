package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil
import kotlin.math.floor

data class HingeBoundsPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class ContentBoundsInWindow(
    val left: Float,
    val top: Float,
    val width: Int,
    val height: Int,
)

internal data class HingeInContent(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class PhysicalFoldPanes(val first: IntRect, val second: IntRect)

internal sealed interface FoldContentLayout {
    data class Split(val panes: PhysicalFoldPanes) : FoldContentLayout
    data class Single(val region: IntRect) : FoldContentLayout
    data object Occluded : FoldContentLayout
}

// FoldingFeature and measured content share window pixels, regardless of density.
internal fun windowHingeToContent(
    hinge: HingeBoundsPx?,
    content: ContentBoundsInWindow?,
): HingeInContent? {
    if (hinge == null || content == null) return null
    if (content.width <= 0 || content.height <= 0) return null
    if (hinge.right < hinge.left || hinge.bottom < hinge.top) return null
    return HingeInContent(
        left = (hinge.left - content.left).coerceIn(0f, content.width.toFloat()),
        top = (hinge.top - content.top).coerceIn(0f, content.height.toFloat()),
        right = (hinge.right - content.left).coerceIn(0f, content.width.toFloat()),
        bottom = (hinge.bottom - content.top).coerceIn(0f, content.height.toFloat()),
    )
}

internal fun physicalFoldPanes(
    content: ContentBoundsInWindow,
    hinge: HingeInContent,
    orientation: FoldHingeOrientation,
): PhysicalFoldPanes? = when (orientation) {
    // Round away from the physical hinge so fractional content origins cannot overlap it.
    FoldHingeOrientation.Vertical -> PhysicalFoldPanes(
        IntRect(0, 0, floor(hinge.left).toInt(), content.height),
        IntRect(ceil(hinge.right).toInt(), 0, content.width, content.height),
    )
    FoldHingeOrientation.Horizontal -> PhysicalFoldPanes(
        IntRect(0, 0, content.width, floor(hinge.top).toInt()),
        IntRect(0, ceil(hinge.bottom).toInt(), content.width, content.height),
    )
    FoldHingeOrientation.Unknown -> null
}

internal fun foldContentLayout(panes: PhysicalFoldPanes): FoldContentLayout {
    val firstAvailable = panes.first.width > 0 && panes.first.height > 0
    val secondAvailable = panes.second.width > 0 && panes.second.height > 0
    return when {
        firstAvailable && secondAvailable -> FoldContentLayout.Split(panes)
        firstAvailable -> FoldContentLayout.Single(panes.first)
        secondAvailable -> FoldContentLayout.Single(panes.second)
        else -> FoldContentLayout.Occluded
    }
}
