package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.R

/**
 * AND-29: the Timer card of the expanded two-pane layout. It fills its pane:
 * the label and the controls keep their own height and the dial takes the
 * rest, so the countdown and the controls share the first viewport of the
 * pane. The task picker is not here; it heads the task board pane.
 */
@Composable
internal fun TimerPaneHero(
    state: TimerHeroState,
    actions: TimerHeroActions,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val card = timerHeroCardStyle(state)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = card.containerColor,
        contentColor = card.textColor,
        shape = card.shape,
    ) {
        TimerPaneHeroLayout(
            viewportHeight = viewportHeight - PortraitHeroPadding * 2,
            modifier = Modifier.padding(PortraitHeroPadding),
            label = { TimerPaneLabel(card) },
            dial = { diameter -> TimerOrbitDial(state, card.palette, diameter) },
            controls = { TimerPaneControls(state, actions, card) },
        )
    }
}

private enum class TimerPaneSlot { Label, Dial, Controls }

// The dial is composed after the label and the controls are measured, with
// the diameter their heights leave.
@Composable
private fun TimerPaneHeroLayout(
    viewportHeight: Dp,
    modifier: Modifier,
    label: @Composable () -> Unit,
    dial: @Composable (Dp) -> Unit,
    controls: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val labelPlaceable = subcompose(TimerPaneSlot.Label, label).single().measure(loose)
        val controlsPlaceable = subcompose(TimerPaneSlot.Controls, controls).single().measure(loose)
        val chromeHeight = labelPlaceable.height + controlsPlaceable.height
        val diameter = timerPaneDialDiameter(width, viewportHeight.roundToPx(), chromeHeight)
        val dialPlaceable = subcompose(TimerPaneSlot.Dial) { dial(diameter.toDp()) }.single().measure(loose)
        layout(width, chromeHeight + dialPlaceable.height) {
            labelPlaceable.placeRelative(0, 0)
            dialPlaceable.placeRelative((width - dialPlaceable.width) / 2, labelPlaceable.height)
            controlsPlaceable.placeRelative(0, labelPlaceable.height + dialPlaceable.height)
        }
    }
}

/**
 * The dial diameter of the expanded Timer pane, in pixels. The dial takes the
 * height the label and the controls (the chrome) leave in the viewport, and
 * never more than the pane width. When the chrome is tall (large text), the
 * dial keeps half of the shorter pane side and the pane scrolls.
 */
internal fun timerPaneDialDiameter(paneWidth: Int, viewportHeight: Int, chromeHeight: Int): Int {
    val smallest = minOf(paneWidth, viewportHeight) / 2
    return minOf(paneWidth, maxOf(viewportHeight - chromeHeight, smallest))
}

@Composable
private fun TimerPaneLabel(card: TimerHeroCardStyle) {
    Column {
        Text(
            stringResource(R.string.current_service),
            Modifier.padding(start = cornerLabelIndent(card.topStartCorner, PortraitHeroPadding)),
            color = card.textColor.copy(alpha = SecondaryTextAlpha),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TimerPaneControls(state: TimerHeroState, actions: TimerHeroActions, card: TimerHeroCardStyle) {
    Column {
        Spacer(Modifier.height(6.dp))
        PrimaryTimerButton(state, actions.onToggleTimer)
        Spacer(Modifier.height(6.dp))
        PortraitTimerActions(state, actions, card.palette)
        Spacer(Modifier.height(6.dp))
        LongBreakProgress(state.longBreakProgress, card.textColor)
        Spacer(Modifier.height(8.dp))
        Text(
            timerInstruction(state.timer?.status ?: "idle"),
            Modifier.fillMaxWidth(),
            color = card.textColor.copy(alpha = SecondaryTextAlpha),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
