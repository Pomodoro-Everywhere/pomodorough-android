package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * UX round 2, M2: the header, the message cards and the hero down to its
 * primary timer control share the first portrait viewport. The header and the
 * hero keep their own height. The message cards sit above the hero only when
 * they fit in full in the height that is left, so a tall notice with Retry
 * cannot push Start, Pause or Resume below the fold. When they do not fit (the
 * smallest phone at the largest text), the cards move below the hero instead
 * of covering the primary control. The cards never scroll on their own: the
 * portrait screen keeps one scroll container (R43-A01).
 */
@Composable
internal fun PortraitFirstViewport(
    viewportHeight: Dp,
    header: @Composable () -> Unit,
    messages: @Composable () -> Unit,
    hero: @Composable () -> Unit,
) {
    Layout(contents = listOf(header, messages, hero)) { (headers, messageCards, heroes), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val headerPlaceable = headers.single().measure(loose)
        val heroPlaceable = heroes.single().measure(loose)
        val viewport = viewportHeight.roundToPx()
        val left = viewport - headerPlaceable.height - primaryControlBottom(heroPlaceable)
        val messagesPlaceable = messageCards.single().measure(loose)
        val messagesFitAbove = messagesPlaceable.height <= left
        val stacked = if (messagesFitAbove) {
            listOf(headerPlaceable, messagesPlaceable, heroPlaceable)
        } else {
            listOf(headerPlaceable, heroPlaceable, messagesPlaceable)
        }
        layout(constraints.maxWidth, stacked.sumOf { it.height }) {
            var top = 0
            stacked.forEach { placeable ->
                placeable.placeRelative(0, top)
                top += placeable.height
            }
        }
    }
}

