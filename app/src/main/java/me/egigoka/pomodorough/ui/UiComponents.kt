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

@Composable
internal fun MessageCard(
    title: String,
    message: String,
    containerColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = darkModeTextColor(Ink),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dismiss), color = darkModeTextColor(Ink))
            }
        }
    }
}

@Composable
internal fun NoticeCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.heads_up_message, message)
    val dismissLabel = stringResource(R.string.dismiss)
    MessageCard(
        title = stringResource(R.string.heads_up),
        message = message,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        onDismiss = onDismiss,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
            onClick(label = dismissLabel) {
                onDismiss()
                true
            }
        },
    )
}

@Composable
internal fun AutoDismissNotice(notice: String?, onDismiss: () -> Unit) {
    val accessibilityManager = LocalAccessibilityManager.current
    val timeoutMillis = accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 7_000,
        containsIcons = false,
        containsText = true,
        containsControls = true,
    ) ?: 7_000
    LaunchedEffect(notice, timeoutMillis) {
        if (notice != null) {
            delay(timeoutMillis)
            onDismiss()
        }
    }
}

@Composable
internal fun BrandMark(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandOrb(if (compact) 38.dp else 52.dp)
        Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
        Text(
            stringResource(R.string.pomodorough),
            color = darkModeTextColor(Cloud),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
internal fun BrandOrb(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                RoundedCornerShape(topStart = 50.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 50.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.4f).background(DangerAccent, CircleShape))
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

@Composable
internal fun darkModeTextColor(lightColor: Color): Color =
    if (LocalPomodoroughDarkTheme.current) Color.White else lightColor

internal data class PhasePalette(
    val container: Color,
    val accent: Color,
    val onContainer: Color = Ink,
)

@Composable
internal fun phasePalette(phase: String): PhasePalette = when (phase) {
    TimerPhase.ShortBreak, TimerPhase.LongBreak -> PhasePalette(container = Lavender, accent = DangerAccent)
    else -> PhasePalette(container = MaterialTheme.colorScheme.tertiaryContainer, accent = DangerAccent)
}

@Composable
internal fun syncLabel(state: AppState): String {
    if (state.network.mode == ReplicationMode.OFFLINE) return stringResource(R.string.on_device)
    if (state.network.mode == ReplicationMode.IROH) return when (state.network.status) {
        IrohConnectionStatus.STARTING -> stringResource(R.string.opening_peer_route)
        IrohConnectionStatus.LISTENING -> stringResource(R.string.ready_for_peers)
        IrohConnectionStatus.SYNCING -> stringResource(R.string.syncing_with_peers)
        IrohConnectionStatus.WAITING_FOR_PEERS -> stringResource(R.string.waiting_for_peers)
        IrohConnectionStatus.CONFLICT -> stringResource(R.string.peer_conflict)
        IrohConnectionStatus.UNAVAILABLE -> stringResource(R.string.peer_route_unavailable)
        IrohConnectionStatus.STOPPED -> stringResource(R.string.peer_route_stopped)
    }
    if (state.historyResolution != null) return stringResource(R.string.history_choice_needed)
    if (state.authStatus != AuthStatus.SignedIn) {
        return when (state.authStatus) {
            AuthStatus.Loading -> stringResource(R.string.checking_account_2)
            AuthStatus.SigningIn -> stringResource(R.string.signing_in_2)
            else -> stringResource(R.string.local_only)
        }
    }
    return when (state.syncStatus) {
        SyncStatus.Offline -> if (state.pendingCount > 0) pluralStringResource(R.plurals.offline_queued, state.pendingCount, state.pendingCount) else stringResource(R.string.offline_local)
        SyncStatus.Conflict -> if (state.pendingCount > 0) pluralStringResource(R.plurals.conflict_queued, state.pendingCount, state.pendingCount) else stringResource(R.string.conflict)
        SyncStatus.Syncing -> stringResource(R.string.syncing)
        SyncStatus.Retrying -> if (state.pendingCount > 0) pluralStringResource(R.plurals.retrying_queued, state.pendingCount, state.pendingCount) else stringResource(R.string.retrying_sync)
        SyncStatus.Queued -> pluralStringResource(R.plurals.waiting_to_sync, state.pendingCount, state.pendingCount)
        SyncStatus.Checking -> stringResource(R.string.checking_sync)
        SyncStatus.Synced -> stringResource(R.string.in_sync)
    }
}

internal fun displayPhase(timer: CanonicalTimer?, settings: TimerSettings): String =
    if (timer?.status == TimerStatus.Completed) {
        settings.selectedPhase
    } else {
        timer?.phase ?: settings.selectedPhase
    }

internal fun displayPlannedDurationMs(timer: CanonicalTimer?, settings: TimerSettings): Long {
    val phase = displayPhase(timer, settings)
    return if (timer == null || timer.status == TimerStatus.Completed) {
        settings.durationMsFor(phase)
    } else {
        timer.plannedDurationMs
    }
}

@Composable
internal fun resolutionLabel(strategy: BootstrapStrategy): String = when (strategy) {
    BootstrapStrategy.ReplaceRemote -> stringResource(R.string.keep_local)
    BootstrapStrategy.KeepRemote -> stringResource(R.string.keep_remote)
    BootstrapStrategy.Merge -> stringResource(R.string.keep_both)
}

@Composable
internal fun resolutionWarning(strategy: BootstrapStrategy): String = when (strategy) {
    BootstrapStrategy.ReplaceRemote ->
        stringResource(R.string.account_timer_history_tasks_settings_and_queued_changes)
    BootstrapStrategy.KeepRemote ->
        stringResource(R.string.this_device_s_timer_history_tasks_settings_and)
    BootstrapStrategy.Merge ->
        stringResource(R.string.queued_local_changes_will_be_merged_into_account)
}

@Composable
internal fun syncColor(status: SyncStatus): Color = when (status) {
    SyncStatus.Synced, SyncStatus.Offline -> Lavender
    SyncStatus.Conflict, SyncStatus.Retrying -> MaterialTheme.colorScheme.tertiaryContainer
    SyncStatus.Syncing, SyncStatus.Queued, SyncStatus.Checking -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
internal fun timerInstruction(status: String): String = when (status) {
    TimerStatus.Running -> stringResource(R.string.pause_whenever_you_need)
    TimerStatus.Paused -> stringResource(R.string.resume_when_ready)
    TimerStatus.Completed -> stringResource(R.string.session_complete)
    TimerStatus.Cancelled -> stringResource(R.string.start_again_when_ready)
    TimerStatus.Superseded -> stringResource(R.string.synced_with_your_other_device)
    else -> stringResource(R.string.start_when_ready)
}

@Composable
internal fun timerTimeText(minutes: Long, seconds: Long, paused: Boolean, color: Color): AnnotatedString {
    val blinkingAlpha = if (paused) {
        val transition = rememberInfiniteTransition(label = "Paused timer separator")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "Paused timer separator alpha",
        )
        alpha
    } else {
        1f
    }
    return buildAnnotatedString {
        append("%02d".format(minutes))
        withStyle(SpanStyle(color = color.copy(alpha = blinkingAlpha))) {
            append(":")
        }
        append("%02d".format(seconds))
    }
}

@Composable
internal fun phaseLabel(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> stringResource(R.string.short_break)
    TimerPhase.LongBreak -> stringResource(R.string.long_break)
    else -> stringResource(R.string.focus)
}

@Composable
internal fun statusLabel(status: String): String = when (status) {
    TimerStatus.Running -> stringResource(R.string.running)
    TimerStatus.Paused -> stringResource(R.string.paused)
    TimerStatus.Completed -> stringResource(R.string.completed)
    TimerStatus.Cancelled -> stringResource(R.string.cancelled)
    TimerStatus.Superseded -> stringResource(R.string.superseded)
    else -> stringResource(R.string.idle)
}

@Composable
internal fun phaseRouteLabel(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> stringResource(R.string.reset)
    TimerPhase.LongBreak -> stringResource(R.string.recover)
    else -> stringResource(R.string.work)
}

internal fun phaseStamp(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "SB"
    TimerPhase.LongBreak -> "LB"
    else -> "F"
}

internal fun historyEpoch(item: HistoryItem): Long {
    val value = item.completedAt ?: item.endedAt ?: return 0
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
}

@Composable
internal fun formatTaskDuration(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    if (minutes == 0L) return pluralStringResource(R.plurals.minutes_short, 0, 0)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0L -> pluralStringResource(R.plurals.minutes_short, minutes.toInt(), minutes)
        remainingMinutes == 0L -> pluralStringResource(R.plurals.hours_short, hours.toInt(), hours)
        else -> stringResource(R.string.hours_minutes_short, hours, remainingMinutes)
    }
}

@Composable
internal fun taskSummaryDescription(summary: TaskDailySummary): String {
    return pluralStringResource(
        R.plurals.task_summary_accessibility,
        summary.finishedPomodoros,
        summary.task.title,
        summary.finishedPomodoros,
        formatTaskDuration(summary.timeSpentMs),
    )
}

@Composable
internal fun historyDescription(item: HistoryItem, taskTitle: String?, showPending: Boolean): String {
    val pending = if (item.pending && showPending) stringResource(R.string.queued_2) else ""
    val task = when {
        taskTitle != null -> stringResource(R.string.history_task, taskTitle)
        item.taskId != null -> stringResource(R.string.deleted_task_2)
        else -> stringResource(R.string.unassigned_2)
    }
    return stringResource(
        R.string.history_accessibility,
        phaseLabel(item.phase),
        statusLabel(item.status),
        pending,
        formatHistoryDate(item),
        task,
        formatTaskDuration(item.plannedDurationMs),
    )
}

@Composable
internal fun formatHistoryDate(item: HistoryItem): String {
    val value = item.completedAt ?: item.endedAt ?: return stringResource(R.string.time_not_recorded)
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault(stringResource(R.string.time_not_recorded))
}
