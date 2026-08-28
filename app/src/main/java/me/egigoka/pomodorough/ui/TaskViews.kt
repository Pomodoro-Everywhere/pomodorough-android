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
internal fun TaskSelector(
    state: TaskSelectorState,
    onSelectTask: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val presentation = taskSelectorPresentation(state)
    Box(Modifier.fillMaxWidth()) {
        TaskSelectorButton(presentation, onClick = { expanded = true })
        TaskSelectionMenu(state.tasks, expanded, onSelectTask) { expanded = false }
    }
}

internal data class TaskSelectorState(
    val tasks: List<FocusTask>,
    val selectedTaskId: String?,
    val timer: CanonicalTimer?,
    val taskTitle: String?,
    val selectedPhase: String,
    val mutationsEnabled: Boolean,
)

internal data class TaskSelectorPresentation(val title: String, val enabled: Boolean)

@Composable
internal fun taskSelectorPresentation(state: TaskSelectorState): TaskSelectorPresentation {
    val active = state.timer?.status == TimerStatus.Running || state.timer?.status == TimerStatus.Paused
    val title = if (active) state.taskTitle else state.tasks.firstOrNull {
        it.id == state.selectedTaskId
    }?.title
    return TaskSelectorPresentation(
        title = title ?: stringResource(R.string.no_task),
        enabled = state.mutationsEnabled && !active && state.selectedPhase == TimerPhase.Focus,
    )
}

@Composable
private fun TaskSelectorButton(state: TaskSelectorPresentation, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = state.enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        border = BorderStroke(1.dp, Ink.copy(alpha = 0.35f)),
    ) {
        Text(stringResource(R.string.focus_task), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(10.dp))
        Text(
            state.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun TaskSelectionMenu(
    tasks: List<FocusTask>,
    expanded: Boolean,
    onSelectTask: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.no_task)) },
            onClick = { onDismiss(); onSelectTask(null) },
        )
        tasks.forEach { task ->
            DropdownMenuItem(
                text = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = { onDismiss(); onSelectTask(task.id) },
            )
        }
    }
}

@Composable
internal fun TaskBoardHeader(
    summaries: List<TaskDailySummary>,
    mutationsEnabled: Boolean,
    onAddTask: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var submissionError by remember { mutableStateOf<String?>(null) }
    val validationErrorKey = TaskDraftPolicy.validate(draft, summaries.map { it.task.title })
    val validationError = if (validationErrorKey == null) null else stringResource(validationErrorKey.messageRes)
    val submissionFailureCopy = stringResource(R.string.task_could_not_be_saved_try_again)
    Column(modifier) {
        TaskBoardSummary(summaries)
        Spacer(Modifier.height(16.dp))
        TaskDraftCard(
            draft = draft,
            error = submissionError ?: validationError?.takeIf { draft.isNotEmpty() },
            valid = validationError == null,
            enabled = mutationsEnabled,
            onDraftChange = { draft = it; submissionError = null },
            onSubmit = {
                onAddTask(draft) { accepted ->
                    if (accepted) draft = "" else submissionError = submissionFailureCopy
                }
            },
        )
    }
}

@Composable
private fun TaskBoardSummary(summaries: List<TaskDailySummary>) {
    val totalFinished = summaries.sumOf(TaskDailySummary::finishedPomodoros)
    val totalTime = summaries.sumOf(TaskDailySummary::timeSpentMs)
    SectionLabel(stringResource(R.string.task_board))
    Text(stringResource(R.string.task_board_2), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        pluralStringResource(
            R.plurals.pomodoro_today,
            totalFinished,
            totalFinished,
            formatTaskDuration(totalTime),
        ),
        color = darkModeTextColor(MaterialTheme.colorScheme.primary),
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun TaskDraftCard(
    draft: String,
    error: String?,
    valid: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = darkModeTextColor(Ink),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.task)) },
                placeholder = { Text(stringResource(R.string.write_release_notes)) },
                singleLine = true,
                enabled = enabled,
                isError = draft.isNotEmpty() && !valid,
                supportingText = { error?.let { Text(it) } },
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSubmit,
                enabled = enabled && valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text(stringResource(R.string.add_task)) }
        }
    }
}

@Composable
internal fun TaskColumnLabels(modifier: Modifier = Modifier) {
    if (LocalConfiguration.current.fontScale < 1.3f) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.task), Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.finished),
                Modifier.weight(1.2f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.time),
                Modifier.weight(1.35f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.action),
                Modifier.weight(0.9f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun TaskSummaryRow(
    summary: TaskDailySummary,
    mutationsEnabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useStackedLayout = LocalConfiguration.current.fontScale >= 1.3f
    val summaryDescription = taskSummaryDescription(summary)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        if (useStackedLayout) {
            StackedTaskSummary(summary, summaryDescription, mutationsEnabled, onDelete)
        } else {
            WideTaskSummary(summary, summaryDescription, mutationsEnabled, onDelete)
        }
    }
}

@Composable
private fun StackedTaskSummary(
    summary: TaskDailySummary,
    description: String,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Text(
                summary.task.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                pluralStringResource(
                    R.plurals.finished_today,
                    summary.finishedPomodoros,
                    summary.finishedPomodoros,
                    formatTaskDuration(summary.timeSpentMs),
                ),
                color = darkModeTextColor(MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DeleteTaskButton(onDelete, enabled, Modifier.align(Alignment.End))
    }
}

@Composable
private fun WideTaskSummary(
    summary: TaskDailySummary,
    description: String,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(4.55f).clearAndSetSemantics {
                contentDescription = description
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary.task.title,
                Modifier.weight(2f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
            TaskSummaryMetric(summary.finishedPomodoros.toString(), Modifier.weight(1.2f), true)
            TaskSummaryMetric(formatTaskDuration(summary.timeSpentMs), Modifier.weight(1.35f), false)
        }
        DeleteTaskButton(onDelete, enabled, Modifier.weight(0.9f))
    }
}

@Composable
private fun TaskSummaryMetric(text: String, modifier: Modifier, monospace: Boolean) {
    if (monospace) {
        Text(
            text,
            modifier,
            color = darkModeTextColor(MaterialTheme.colorScheme.primary),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            text,
            modifier,
            color = darkModeTextColor(MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeleteTaskButton(onDelete: () -> Unit, enabled: Boolean, modifier: Modifier) {
    TextButton(onClick = onDelete, enabled = enabled, modifier = modifier) {
        Text(stringResource(R.string.delete), color = darkModeTextColor(MaterialTheme.colorScheme.error))
    }
}
