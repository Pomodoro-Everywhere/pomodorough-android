package me.egigoka.pomodorough.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.domain.TimerPresentation

@Composable
internal fun TimerHero(
    state: TimerHeroState,
    actions: TimerHeroActions,
    landscape: Boolean = false,
    orbitMaxSize: Dp = 318.dp,
    modifier: Modifier = Modifier,
) {
    val status = state.timer?.status ?: "idle"
    val phase = displayPhase(state.timer, state.settings)
    val showContextLabel = true
    val palette = phasePalette(phase)
    val textColor = darkModeTextColor(palette.onContainer)
    val containerColor by animateColorAsState(palette.container, label = "timer container")
    val corner by animateDpAsState(
        targetValue = when (status) {
            TimerStatus.Running -> 68.dp
            TimerStatus.Paused -> 46.dp
            else -> 56.dp
        },
        label = "timer shape",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (landscape) Ink else containerColor,
        contentColor = if (landscape) darkModeTextColor(Cloud) else textColor,
        shape = if (landscape) {
            RoundedCornerShape(24.dp)
        } else {
            RoundedCornerShape(
                topStart = corner,
                topEnd = 22.dp,
                bottomStart = 22.dp,
                bottomEnd = corner,
            )
        },
    ) {
        if (landscape) {
            LandscapeTimerHero(state, actions, showContextLabel)
        } else {
            PortraitTimerHero(state, actions, palette, textColor, showContextLabel, orbitMaxSize)
        }
    }
}

internal data class TimerHeroState(
    val timer: CanonicalTimer?,
    val activeCompletionAlertTimerId: String?,
    val settings: TimerSettings,
    val longBreakProgress: Int,
    val taskTitle: String?,
    val ready: Boolean,
    val tasks: List<FocusTask>,
    val selectedTaskId: String?,
    val mutationsEnabled: Boolean,
) {
    val taskSelectorState get() = TaskSelectorState(
        tasks, selectedTaskId, timer, taskTitle, settings.selectedPhase, mutationsEnabled,
    )
}

internal data class TimerHeroActions(
    val onToggleTimer: () -> Unit,
    val onFinishTimer: () -> Unit,
    val onCancelTimer: () -> Unit,
    val onClearTimer: () -> Unit,
    val onStopSound: () -> Unit,
    val onSelectTask: (String?) -> Unit,
)

private data class TimerControlState(
    val status: String,
    val active: Boolean,
    val clearable: Boolean,
    val hasActiveCompletionAlert: Boolean,
)

private fun timerControlState(state: TimerHeroState): TimerControlState {
    val status = state.timer?.status ?: "idle"
    val active = status == TimerStatus.Running || status == TimerStatus.Paused
    return TimerControlState(
        status = status,
        active = active,
        clearable = state.timer != null && !active,
        hasActiveCompletionAlert = state.activeCompletionAlertTimerId != null &&
            state.timer?.id == state.activeCompletionAlertTimerId,
    )
}

@Composable
private fun PortraitTimerHero(
    state: TimerHeroState,
    actions: TimerHeroActions,
    palette: PhasePalette,
    textColor: Color,
    showContextLabel: Boolean,
    orbitMaxSize: Dp,
) {
    Column(Modifier.padding(14.dp)) {
        if (showContextLabel) {
            SectionLabel(stringResource(R.string.current_service))
            Spacer(Modifier.height(6.dp))
        }
        TimerOrbit(state, palette, orbitMaxSize)
        Spacer(Modifier.height(6.dp))
        LongBreakProgress(state.longBreakProgress, textColor)
        Spacer(Modifier.height(6.dp))
        TaskSelector(state.taskSelectorState, actions.onSelectTask)
        Spacer(Modifier.height(8.dp))
        Text(
            timerInstruction(state.timer?.status ?: "idle"),
            Modifier.fillMaxWidth(),
            color = textColor.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        PrimaryTimerButton(state, actions.onToggleTimer)
        Spacer(Modifier.height(6.dp))
        PortraitTimerActions(state, actions, palette)
    }
}

@Composable
private fun PrimaryTimerButton(state: TimerHeroState, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = state.ready,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = darkModeTextColor(Cloud)),
    ) {
        Text(timerToggleLabel(state.timer?.status ?: "idle", state.settings.selectedPhase))
    }
}

@Composable
private fun timerToggleLabel(status: String, selectedPhase: String): String = when (status) {
    TimerStatus.Running -> stringResource(R.string.pause)
    TimerStatus.Paused -> stringResource(R.string.resume)
    else -> stringResource(R.string.start_phase, phaseLabel(selectedPhase).lowercase())
}

@Composable
private fun PortraitTimerActions(state: TimerHeroState, actions: TimerHeroActions, palette: PhasePalette) {
    val controls = timerControlState(state)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(
            onClick = actions.onFinishTimer,
            enabled = state.ready && controls.active,
            modifier = Modifier.weight(1f).height(48.dp),
        ) { Text(stringResource(R.string.finish)) }
        OutlinedButton(
            onClick = {
                when {
                    controls.active -> actions.onCancelTimer()
                    controls.hasActiveCompletionAlert -> actions.onStopSound()
                    else -> actions.onClearTimer()
                }
            },
            enabled = state.ready && (controls.active || controls.clearable),
            modifier = Modifier.weight(1f).height(48.dp),
            border = BorderStroke(1.5.dp, palette.onContainer.copy(alpha = 0.55f)),
        ) {
            Text(
                when {
                    controls.active -> stringResource(R.string.cancel)
                    controls.hasActiveCompletionAlert -> stringResource(R.string.stop_sound)
                    else -> stringResource(R.string.dismiss)
                },
            )
        }
    }
}

@Composable
internal fun LandscapeTimerHero(
    state: TimerHeroState,
    actions: TimerHeroActions,
    showContextLabel: Boolean,
) {
    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showContextLabel) {
            Text(
                stringResource(R.string.current_service),
                color = darkModeTextColor(Cloud),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        LandscapeTimerReadout(state, Modifier.weight(1f))
        LongBreakProgress(state.longBreakProgress, darkModeTextColor(Butter))
        LandscapeTaskSelector(state.taskSelectorState, actions.onSelectTask)
        LandscapeTimerActions(state, actions)
    }
}

@Composable
private fun LandscapeTimerActions(state: TimerHeroState, actions: TimerHeroActions) {
    val controls = timerControlState(state)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = actions.onToggleTimer,
            enabled = state.ready,
            modifier = Modifier.weight(1f).height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = darkModeTextColor(Ink),
            ),
        ) { Text(timerToggleLabel(controls.status, state.settings.selectedPhase), maxLines = 1) }
        if (controls.active) {
            FilledTonalButton(
                onClick = actions.onFinishTimer,
                enabled = state.ready,
                modifier = Modifier.weight(1f).height(54.dp),
            ) { Text(stringResource(R.string.finish)) }
            OutlinedButton(
                onClick = actions.onCancelTimer,
                enabled = state.ready,
                modifier = Modifier.weight(1f).height(54.dp),
                border = BorderStroke(1.5.dp, Cloud.copy(alpha = 0.65f)),
            ) { Text(stringResource(R.string.cancel), color = darkModeTextColor(Cloud)) }
        } else if (controls.clearable) {
            LandscapeClearButton(state.ready, controls.hasActiveCompletionAlert, actions)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LandscapeClearButton(
    ready: Boolean,
    hasAlert: Boolean,
    actions: TimerHeroActions,
) {
    OutlinedButton(
        onClick = if (hasAlert) actions.onStopSound else actions.onClearTimer,
        enabled = ready,
        modifier = Modifier.weight(1f).height(54.dp),
        border = BorderStroke(1.5.dp, Cloud.copy(alpha = 0.65f)),
    ) {
        Text(
            stringResource(if (hasAlert) R.string.stop_sound else R.string.dismiss),
            color = darkModeTextColor(Cloud),
        )
    }
}

@Composable
internal fun LongBreakProgress(progress: Int, color: Color) {
    val progressDescription = stringResource(R.string.pomodoro_progress, progress)
    Text(
        text = "●".repeat(progress) + "○".repeat(4 - progress),
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = progressDescription
            },
        color = color.copy(alpha = 0.78f),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun LandscapeTimerReadout(
    state: TimerHeroState,
    modifier: Modifier = Modifier,
) {
    val textColor = darkModeTextColor(Butter)
    val readout = timerReadout(state, textColor, "Landscape timer progress")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Cloud.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(readout.progress, 0f..1f)
                contentDescription = readout.description
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        LandscapeReadoutHeader(readout.phase, textColor)
        Text(
            text = readout.timeText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 112.sp,
            letterSpacing = (-6).sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        TimerProgressBar(readout.animatedProgress)
    }
}

private data class TimerReadoutState(
    val phase: String,
    val duration: Long,
    val progress: Float,
    val animatedProgress: Float,
    val timeText: AnnotatedString,
    val description: String,
)

@Composable
private fun timerReadout(state: TimerHeroState, textColor: Color, animationLabel: String): TimerReadoutState {
    val timer = state.timer
    var now by remember(timer?.id, timer?.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer?.id, timer?.status, timer?.anchorAt) {
        while (timer?.status == TimerStatus.Running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val phase = displayPhase(timer, state.settings)
    val duration = displayPlannedDurationMs(timer, state.settings)
    val elapsed = if (timer == null || timer.status == TimerStatus.Completed) 0 else {
        TimerPresentation.elapsedAt(timer, now)
    }
    val totalSeconds = ceil((duration - elapsed).coerceAtLeast(0) / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val progress = (if (duration > 0) elapsed.toFloat() / duration else 0f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = animationLabel,
    )
    val status = timer?.status ?: "idle"
    return TimerReadoutState(
        phase = phase,
        duration = duration,
        progress = progress,
        animatedProgress = animatedProgress,
        timeText = timerTimeText(minutes, seconds, status == TimerStatus.Paused, textColor),
        description = stringResource(
            R.string.timer_remaining, minutes, seconds, phaseLabel(phase), statusLabel(status),
        ),
    )
}

@Composable
private fun LandscapeReadoutHeader(phase: String, textColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            phaseRouteLabel(phase).uppercase(),
            color = darkModeTextColor(Cloud),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(phaseLabel(phase).uppercase(), color = textColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimerProgressBar(progress: Float) {
    Box(Modifier.fillMaxWidth().height(9.dp).background(Cloud.copy(alpha = 0.15f), CircleShape)) {
        Box(Modifier.fillMaxWidth(progress).height(9.dp).background(Danger, CircleShape))
    }
}

@Composable
internal fun LandscapeTaskSelector(
    state: TaskSelectorState,
    onSelectTask: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val presentation = taskSelectorPresentation(state)
    Box {
        LandscapeTaskSelectorButton(presentation) { expanded = true }
        TaskSelectionMenu(state.tasks, expanded, onSelectTask) { expanded = false }
    }
}

@Composable
private fun LandscapeTaskSelectorButton(state: TaskSelectorPresentation, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = state.enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        border = BorderStroke(1.dp, Cloud.copy(alpha = 0.5f)),
    ) {
        Text(
            stringResource(R.string.focus_task),
            color = darkModeTextColor(Cloud),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            state.title,
            modifier = Modifier.weight(1f),
            color = darkModeTextColor(Butter),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun TimerOrbit(
    state: TimerHeroState,
    palette: PhasePalette,
    maxSize: Dp,
) {
    val textColor = darkModeTextColor(palette.onContainer)
    val readout = timerReadout(state, textColor, "Timer progress")
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val orbitSize = min(min(maxWidth.value, maxSize.value), 318f).dp
        Box(
            modifier = Modifier
                .size(orbitSize)
                .clearAndSetSemantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(readout.progress, 0f..1f)
                    contentDescription = readout.description
                },
            contentAlignment = Alignment.Center,
        ) {
            TimerOrbitCanvas(readout.animatedProgress, palette)
            TimerOrbitLabels(readout, orbitSize, palette, textColor)
        }
    }
}

@Composable
private fun TimerOrbitCanvas(progress: Float, palette: PhasePalette) {
    Canvas(Modifier.fillMaxSize()) {
        val strokeWidth = 18.dp.toPx()
        val inset = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        drawArc(
            color = palette.onContainer.copy(alpha = 0.12f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        val sweep = 360f * progress
        drawArc(
            color = palette.accent,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            val radius = (size.minDimension - strokeWidth) / 2
            val angle = (sweep - 90f) * (PI.toFloat() / 180f)
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                color = palette.onContainer,
                radius = 5.dp.toPx(),
                center = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius),
            )
        }
    }
}

@Composable
private fun TimerOrbitLabels(
    readout: TimerReadoutState,
    orbitSize: Dp,
    palette: PhasePalette,
    textColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = readout.timeText,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = if (orbitSize < 280.dp) 48.sp else 62.sp,
            letterSpacing = (-3).sp,
        )
        Text(
            text = phaseLabel(readout.phase),
            color = textColor.copy(alpha = 0.68f),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            color = palette.onContainer.copy(alpha = 0.1f),
            contentColor = textColor,
            shape = CircleShape,
        ) {
            Text(
                pluralStringResource(
                    R.plurals.minutes_short,
                    (readout.duration / 60_000).toInt(),
                    readout.duration / 60_000,
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
