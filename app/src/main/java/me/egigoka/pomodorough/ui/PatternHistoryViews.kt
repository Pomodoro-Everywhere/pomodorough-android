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
internal fun PatternSection(
    settings: TimerSettings,
    timer: CanonicalTimer?,
    mutationsEnabled: Boolean,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = timer?.status == TimerStatus.Running || timer?.status == TimerStatus.Paused
    Column(modifier) {
        PatternHeader()
        Spacer(Modifier.height(16.dp))
        PhaseCards(settings, mutationsEnabled && !active, onSelectPhase, onChangeDuration)
        Spacer(Modifier.height(12.dp))
        AutoStartBreaksCard(settings.autoStartBreaks, mutationsEnabled, onSetAutoStart)
    }
}

@Composable
private fun PatternHeader() {
    SectionLabel(stringResource(R.string.route))
    Text(stringResource(R.string.service_pattern), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.choose_a_mode_and_duration),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PhaseCards(
    settings: TimerSettings,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
) {
    val phases = listOf(
        TimerPhase.Focus to stringResource(R.string.deep_work),
        TimerPhase.ShortBreak to stringResource(R.string.quick_reset),
        TimerPhase.LongBreak to stringResource(R.string.full_recharge),
    )
    phases.forEachIndexed { index, (phase, supportingText) ->
        if (index > 0) Spacer(Modifier.height(10.dp))
        PhaseCard(phase, supportingText, settings, enabled, onSelect, onChangeDuration)
    }
}

@Composable
private fun AutoStartBreaksCard(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = Ink,
        contentColor = darkModeTextColor(Cloud),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_start_breaks), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.short_after_focus_long_every_fourth_completed_focus),
                    color = darkModeTextColor(Lavender),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
internal fun PhaseCard(
    phase: String,
    supportingText: String,
    settings: TimerSettings,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
) {
    val selected = settings.selectedPhase == phase
    val minutes = settings.minutesFor(phase)
    val palette = phasePalette(phase)
    val selectedTextColor = darkModeTextColor(Ink)
    val durationDescription = pluralStringResource(R.plurals.minutes_long, minutes, minutes)
    Surface(
        onClick = { onSelect(phase) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected
            stateDescription = durationDescription
        },
        color = if (selected) palette.container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) selectedTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = phaseCardShape(selected),
        border = if (selected) BorderStroke(2.dp, palette.accent) else null,
    ) {
        PhaseCardContent(
            phase, supportingText, minutes, enabled, selected, palette,
            selectedTextColor, onChangeDuration,
        )
    }
}

@Composable
private fun phaseCardShape(selected: Boolean) = if (selected) {
    RoundedCornerShape(topStart = 36.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 36.dp)
} else {
    MaterialTheme.shapes.large
}

@Composable
private fun PhaseCardContent(
    phase: String,
    supportingText: String,
    minutes: Int,
    enabled: Boolean,
    selected: Boolean,
    palette: PhasePalette,
    selectedTextColor: Color,
    onChangeDuration: (String, Int) -> Unit,
) {
    if (LocalConfiguration.current.fontScale >= 1.3f) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhaseCardHeader(phase, supportingText, selected, palette, selectedTextColor)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhaseDurationControls(phase, minutes, enabled, selected, selectedTextColor, onChangeDuration)
            }
        }
    } else {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PhaseCardHeader(
                phase, supportingText, selected, palette, selectedTextColor,
                modifier = Modifier.weight(1f),
            )
            PhaseDurationControls(phase, minutes, enabled, selected, selectedTextColor, onChangeDuration)
        }
    }
}

@Composable
internal fun PhaseCardHeader(
    phase: String,
    supportingText: String,
    selected: Boolean,
    palette: PhasePalette,
    selectedTextColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(if (selected) palette.accent else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(phaseLabel(phase), style = MaterialTheme.typography.titleLarge)
            Text(
                supportingText,
                color = if (selected) selectedTextColor.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun PhaseDurationControls(
    phase: String,
    minutes: Int,
    enabled: Boolean,
    selected: Boolean,
    selectedTextColor: Color,
    onChangeDuration: (String, Int) -> Unit,
) {
    StepButton("−", stringResource(R.string.decrease_phase_duration, phaseLabel(phase)), enabled) {
        onChangeDuration(phase, -1)
    }
    Text(
        "$minutes",
        modifier = Modifier.width(56.dp).clearAndSetSemantics { },
        color = if (selected) selectedTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        textAlign = TextAlign.Center,
    )
    StepButton("+", stringResource(R.string.increase_phase_duration, phaseLabel(phase)), enabled) {
        onChangeDuration(phase, 1)
    }
}

@Composable
internal fun StepButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun CompletedFocusBreakdown(
    history: List<HistoryItem>,
    tasks: List<FocusTask>,
    modifier: Modifier = Modifier,
) {
    val totals = completedFocusTotals(history, tasks)
    val summaryDescription = completedFocusDescription(totals)
    Surface(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = summaryDescription
        },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        CompletedFocusContent(totals)
    }
}

private data class CompletedFocusTotal(val title: String, val count: Int, val duration: Long)

@Composable
private fun completedFocusTotals(history: List<HistoryItem>, tasks: List<FocusTask>): List<CompletedFocusTotal> {
    val taskNames = tasks.associate { it.id to it.title }
    val completed = history.filter { it.phase == TimerPhase.Focus && it.status == TimerStatus.Completed }
    return completed.groupBy { it.taskId }.map { (taskId, items) ->
        val title = when {
            taskId == null -> stringResource(R.string.unassigned)
            taskNames[taskId] != null -> taskNames.getValue(taskId)
            else -> stringResource(R.string.deleted_task)
        }
        CompletedFocusTotal(title, items.size, items.sumOf { it.plannedDurationMs })
    }.sortedBy { it.title.lowercase(Locale.getDefault()) }
}

@Composable
private fun completedFocusDescription(totals: List<CompletedFocusTotal>): String {
    if (totals.isEmpty()) {
        return stringResource(R.string.completed_focus_summary_no_completed_focus_sessions)
    }
    val parts = totals.map { total ->
        pluralStringResource(
            R.plurals.summary_sessions,
            total.count,
            total.title,
            total.count,
            formatTaskDuration(total.duration),
        )
    }
    return stringResource(R.string.completed_focus_summary) + parts.joinToString(". ")
}

@Composable
private fun CompletedFocusContent(totals: List<CompletedFocusTotal>) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.completed_focus), style = MaterialTheme.typography.titleLarge)
        if (totals.isEmpty()) {
            Text(stringResource(R.string.no_completed_focus_sessions_yet))
        } else totals.forEach { total ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(total.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.count_and_duration, total.count, formatTaskDuration(total.duration)))
            }
        }
    }
}

@Composable
internal fun HistoryTitle(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            SectionLabel(stringResource(R.string.run_log_this_account))
            Text(stringResource(R.string.recent_arrivals), style = MaterialTheme.typography.headlineMedium)
        }
        Surface(
            color = Violet,
            contentColor = darkModeTextColor(Cloud),
            shape = CircleShape,
        ) {
            Text(
                count.toString().padStart(2, '0'),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
internal fun HistoryRow(
    item: HistoryItem,
    taskTitle: String?,
    showPending: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = phasePalette(item.phase)
    val description = historyDescription(item, taskTitle, showPending)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        HistoryRowContent(item, taskTitle, showPending, palette)
    }
}

@Composable
private fun HistoryRowContent(
    item: HistoryItem,
    taskTitle: String?,
    showPending: Boolean,
    palette: PhasePalette,
) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        HistoryPhaseStamp(item.phase, palette)
        Spacer(Modifier.width(13.dp))
        HistoryDetails(item, taskTitle, showPending, Modifier.weight(1f))
        HistoryDuration(item.plannedDurationMs)
    }
}

@Composable
private fun HistoryPhaseStamp(phase: String, palette: PhasePalette) {
    Surface(
        modifier = Modifier.size(48.dp),
        color = palette.container,
        contentColor = darkModeTextColor(Ink),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(phaseStamp(phase), fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun HistoryDetails(item: HistoryItem, taskTitle: String?, showPending: Boolean, modifier: Modifier) {
    val taskContext = when {
        taskTitle != null -> taskTitle
        item.taskId != null -> stringResource(R.string.deleted_task)
        else -> stringResource(R.string.unassigned)
    }
    Column(modifier) {
        Text(
            phaseLabel(item.phase) + if (item.pending && showPending) stringResource(R.string.queued) else "",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            formatHistoryDate(item),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            taskContext,
            color = darkModeTextColor(MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HistoryDuration(durationMs: Long) {
    val minutes = (durationMs / 60_000).coerceAtLeast(1)
    Text(
        pluralStringResource(R.plurals.minutes_short, minutes.toInt(), minutes),
        color = darkModeTextColor(MaterialTheme.colorScheme.primary),
        style = MaterialTheme.typography.labelLarge,
    )
}
