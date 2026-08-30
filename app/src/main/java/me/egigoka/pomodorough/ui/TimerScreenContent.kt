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
internal fun PortraitTimerScreen(
    state: AppState,
    mutationsEnabled: Boolean,
    actions: TimerContentActions,
) {
    Column(Modifier.fillMaxSize()) {
        AppHeader(state, actions.header)
        TimerScreenMessages(state, actions, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val orbitSize = (maxHeight - 230.dp).coerceIn(132.dp, 318.dp)
            TimerHero(
                state = timerHeroState(state, mutationsEnabled),
                actions = actions.hero,
                orbitMaxSize = orbitSize,
            )
        }
    }
}

@Composable
internal fun LandscapeTimerScreen(
    state: AppState,
    mutationsEnabled: Boolean,
    actions: TimerContentActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.localAccountResetRequired) {
            ResetLocalAccountButton(actions.header.onResetLocalAccount, Modifier.align(Alignment.End))
        }
        TimerScreenMessages(state, actions)
        TimerHero(
            state = timerHeroState(state, mutationsEnabled),
            actions = actions.hero,
            landscape = true,
            modifier = Modifier.weight(1f),
        )
    }
}

internal data class AppHeaderActions(
    val onSignIn: () -> Unit,
    val onLogout: () -> Unit,
    val onResetLocalAccount: () -> Unit,
)

internal data class TimerContentActions(
    val header: AppHeaderActions,
    val hero: TimerHeroActions,
    val onDismissConflict: () -> Unit,
    val onDismissNotice: () -> Unit,
)

private fun timerHeroState(state: AppState, mutationsEnabled: Boolean) = TimerHeroState(
    timer = state.timer,
    activeCompletionAlertTimerId = state.completionAlertTimerId,
    settings = state.settings,
    longBreakProgress = TimerPresentation.longBreakProgress(
        TimerPresentation.completedFocusCountForDay(state.history),
    ),
    taskTitle = state.timer?.taskId?.let { taskId ->
        state.knownTasks.firstOrNull { it.id == taskId }?.title
    },
    ready = state.ready && mutationsEnabled,
    tasks = state.tasks,
    selectedTaskId = state.selectedTaskId,
    mutationsEnabled = mutationsEnabled,
)

@Composable
private fun TimerScreenMessages(state: AppState, actions: TimerContentActions, modifier: Modifier = Modifier) {
    state.conflict?.let {
        MessageCard(
            title = stringResource(R.string.changes_resolved_on_another_device),
            message = it,
            containerColor = Lavender,
            onDismiss = actions.onDismissConflict,
            modifier = modifier,
        )
    }
    state.notice?.let { NoticeCard(it, actions.onDismissNotice, modifier) }
}

@Composable
private fun ResetLocalAccountButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(
            stringResource(R.string.reset_local_account),
            color = darkModeTextColor(MaterialTheme.colorScheme.error),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun AppHeader(
    state: AppState,
    actions: AppHeaderActions,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Ink,
        contentColor = darkModeTextColor(Cloud),
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 10.dp, bottom = 10.dp)) {
            HeaderTopRow(state, actions)
            Spacer(Modifier.height(4.dp))
            HeaderSyncRow(state)
        }
    }
}

@Composable
private fun HeaderTopRow(state: AppState, actions: AppHeaderActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(compact = true)
        HeaderAuthAction(state, actions)
    }
}

@Composable
private fun HeaderAuthAction(state: AppState, actions: AppHeaderActions) {
    when (state.authStatus) {
        AuthStatus.SignedIn -> HeaderTextButton(
            stringResource(R.string.account), actions.onLogout, enabled = true,
        )
        AuthStatus.SigningIn -> HeaderTextButton(stringResource(R.string.signing_in), {}, enabled = false)
        AuthStatus.Loading -> HeaderTextButton(stringResource(R.string.checking), {}, enabled = false)
        AuthStatus.SignedOut -> {
            val reset = state.localAccountResetRequired
            TextButton(
                onClick = if (reset) actions.onResetLocalAccount else actions.onSignIn,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(if (reset) R.string.reset_local_account else R.string.sign_in),
                    color = darkModeTextColor(if (reset) MaterialTheme.colorScheme.error else Butter),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HeaderTextButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = darkModeTextColor(Lavender))
    }
}

@Composable
private fun HeaderSyncRow(state: AppState) {
    val accountLabel = headerAccountLabel(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = syncColor(state.syncStatus), contentColor = darkModeTextColor(Ink), shape = CircleShape) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(Ink, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(syncLabel(state), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (accountLabel.isBlank()) {
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                text = accountLabel,
                modifier = Modifier.weight(1f),
                color = darkModeTextColor(Cloud),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun headerAccountLabel(state: AppState): String = when (state.authStatus) {
    AuthStatus.SignedIn -> state.user?.name?.ifBlank { state.user.email } ?: stringResource(R.string.signed_in)
    AuthStatus.Loading -> stringResource(R.string.checking_account_2)
    AuthStatus.SigningIn -> stringResource(R.string.signing_in_2)
    AuthStatus.SignedOut -> ""
}

internal enum class MainTab(val labelRes: Int) {
    Timer(R.string.timer),
    Tasks(R.string.tasks),
    Pattern(R.string.pattern),
    Arrivals(R.string.arrivals),
    Network(R.string.network),
}

@Composable
internal fun MainNavigationBar(
    active: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    val showLabels = LocalConfiguration.current.fontScale < 1.3f
    NavigationBar {
        listOf(MainTab.Timer, MainTab.Tasks, MainTab.Pattern, MainTab.Arrivals).forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = tab == active,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            MainTab.Timer -> Icons.Outlined.Timer
                            MainTab.Tasks -> Icons.Outlined.Checklist
                            MainTab.Pattern -> Icons.Outlined.Tune
                            MainTab.Arrivals -> Icons.Outlined.History
                            MainTab.Network -> Icons.Outlined.Hub
                        },
                        contentDescription = label,
                    )
                },
                label = if (showLabels) {
                    { Text(label, maxLines = 1) }
                } else {
                    null
                },
            )
        }
    }
}
