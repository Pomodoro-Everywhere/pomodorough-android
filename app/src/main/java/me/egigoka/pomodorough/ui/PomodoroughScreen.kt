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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import me.egigoka.pomodorough.domain.TimerReducer

@Composable
fun PomodoroughScreen(
    state: AppState,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
    onSelectTask: (String?) -> Unit,
    onAddTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onResolveHistory: (BootstrapStrategy) -> Unit,
    onRecoverHistoryResolution: () -> Unit,
    onConfirmAccountSwitch: () -> Unit,
    onCancelAccountSwitch: () -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!state.ready) {
            LoadingScreen()
        } else {
            TimerScreen(
                state = state,
                onSignIn = onSignIn,
                onLogout = onLogout,
                onRefresh = onRefresh,
                onToggleTimer = onToggleTimer,
                onFinishTimer = onFinishTimer,
                onCancelTimer = onCancelTimer,
                onClearTimer = onClearTimer,
                onSelectPhase = onSelectPhase,
                onChangeDuration = onChangeDuration,
                onSetAutoStart = onSetAutoStart,
                onSelectTask = onSelectTask,
                onAddTask = onAddTask,
                onDeleteTask = onDeleteTask,
                onResolveHistory = onResolveHistory,
                onRecoverHistoryResolution = onRecoverHistoryResolution,
                onConfirmAccountSwitch = onConfirmAccountSwitch,
                onCancelAccountSwitch = onCancelAccountSwitch,
                onDismissConflict = onDismissConflict,
                onDismissNotice = onDismissNotice,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(88.dp),
                containerColor = Lavender,
                indicatorColor = Violet,
            )
            Spacer(Modifier.height(28.dp))
            Text("Syncing your clock", color = Cloud, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SignInScreen(
    signingIn: Boolean,
    notice: String?,
    onSignIn: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(7_000)
            onDismissNotice()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BrandMark()
            Spacer(Modifier.height(30.dp))
            Text(
                text = "Make time\nfeel yours.",
                color = Cloud,
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "One focused clock, in sync everywhere.",
                color = Lavender,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(30.dp))
            Surface(
                color = Butter,
                contentColor = Ink,
                shape = RoundedCornerShape(
                    topStart = 48.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 48.dp,
                ),
            ) {
                Column(Modifier.padding(24.dp)) {
                    SectionLabel("YOUR CLOCK, ANYWHERE")
                    Spacer(Modifier.height(10.dp))
                    Text("Pick up where you left off", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Google sign-in keeps timers and completed sessions shared across your devices. Offline actions wait safely for sync.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onSignIn,
                        enabled = !signingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Violet,
                            contentColor = Cloud,
                        ),
                    ) {
                        if (signingIn) {
                            ContainedLoadingIndicator(
                                modifier = Modifier.size(36.dp),
                                containerColor = Cloud,
                                indicatorColor = Violet,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("CONTACTING GOOGLE")
                        } else {
                            Text("SIGN IN WITH GOOGLE")
                        }
                    }
                }
            }
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                NoticeCard(notice, onDismissNotice)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerScreen(
    state: AppState,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectPhase: (String) -> Unit,
    onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit,
    onSelectTask: (String?) -> Unit,
    onAddTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onResolveHistory: (BootstrapStrategy) -> Unit,
    onRecoverHistoryResolution: () -> Unit,
    onConfirmAccountSwitch: () -> Unit,
    onCancelAccountSwitch: () -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var confirmationStrategy by remember(
        state.historyResolution?.requestId,
        state.historyResolution?.pendingStrategy,
        state.historyResolution?.recovery,
    ) { mutableStateOf<BootstrapStrategy?>(null) }
    var activeTab by remember { mutableStateOf(MainTab.Timer) }
    val tasksListState = rememberLazyListState()
    val patternListState = rememberLazyListState()
    val arrivalsListState = rememberLazyListState()
    val mutationsEnabled = state.authStatus != AuthStatus.Loading &&
        state.authStatus != AuthStatus.SigningIn &&
        state.historyResolution == null &&
        state.accountSwitch == null
    val recentHistory = remember(state.history) {
        state.history.sortedByDescending(::historyEpoch)
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(7_000)
            onDismissNotice()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape && activeTab == MainTab.Timer) {
            LandscapeTimerScreen(
                state = state,
                mutationsEnabled = mutationsEnabled,
                onToggleTimer = onToggleTimer,
                onFinishTimer = onFinishTimer,
                onCancelTimer = onCancelTimer,
                onClearTimer = onClearTimer,
                onSelectTask = onSelectTask,
                onDismissConflict = onDismissConflict,
                onDismissNotice = onDismissNotice,
            )
        } else {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                bottomBar = {
                    MainNavigationBar(
                        active = activeTab,
                        onSelect = { activeTab = it },
                    )
                },
            ) { scaffoldPadding ->
                if (activeTab == MainTab.Timer) {
                    val pullGestureState = rememberScrollableState { 0f }
                    val content = @Composable {
                        PortraitTimerScreen(
                            state = state,
                            mutationsEnabled = mutationsEnabled,
                            onSignIn = onSignIn,
                            onLogout = { showLogoutDialog = true },
                            onToggleTimer = onToggleTimer,
                            onFinishTimer = onFinishTimer,
                            onCancelTimer = onCancelTimer,
                            onClearTimer = onClearTimer,
                            onSelectTask = onSelectTask,
                            onDismissConflict = onDismissConflict,
                            onDismissNotice = onDismissNotice,
                        )
                    }
                    if (state.authStatus == AuthStatus.SignedIn) {
                        PullToRefreshBox(
                            isRefreshing = state.syncStatus == SyncStatus.Syncing,
                            onRefresh = onRefresh,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(scaffoldPadding),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .scrollable(pullGestureState, Orientation.Vertical),
                            ) { content() }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(scaffoldPadding),
                        ) { content() }
                    }
                    return@Scaffold
                }
                LazyColumn(
                    state = when (activeTab) {
                        MainTab.Timer -> error("Timer tab does not use a list")
                        MainTab.Tasks -> tasksListState
                        MainTab.Pattern -> patternListState
                        MainTab.Arrivals -> arrivalsListState
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
        item {
            AppHeader(
                state = state,
                onSignIn = onSignIn,
                onLogout = { showLogoutDialog = true },
            )
        }
        if (state.conflict != null) {
            item {
                MessageCard(
                    title = "Changes resolved on another device",
                    message = state.conflict,
                    containerColor = Lavender,
                    onDismiss = onDismissConflict,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (state.notice != null) {
            item {
                NoticeCard(
                    message = state.notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (activeTab) {
            MainTab.Timer -> Unit
            MainTab.Tasks -> {
                item {
                    TaskBoardHeader(
                        summaries = state.taskSummaries,
                        mutationsEnabled = mutationsEnabled,
                        onAddTask = onAddTask,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                if (state.taskSummaries.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Lavender,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                "Add a task to give the next focus session a destination.",
                                modifier = Modifier.padding(22.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                } else {
                    item {
                        TaskColumnLabels(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    items(state.taskSummaries, key = { it.task.id }) { summary ->
                        TaskSummaryRow(
                            summary = summary,
                            mutationsEnabled = mutationsEnabled,
                            onDelete = { onDeleteTask(summary.task.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            MainTab.Pattern -> item {
                PatternSection(
                    settings = state.settings,
                    timer = state.timer,
                    mutationsEnabled = mutationsEnabled,
                    onSelectPhase = onSelectPhase,
                    onChangeDuration = onChangeDuration,
                    onSetAutoStart = onSetAutoStart,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                )
            }
            MainTab.Arrivals -> {
                item {
                    HistoryTitle(
                        count = recentHistory.size,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                }
                if (recentHistory.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = Lavender,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                "Your first completed or cancelled session will land here.",
                                modifier = Modifier.padding(22.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(items = recentHistory, key = { it.id }) { item ->
                        HistoryRow(
                            item = item,
                            taskTitle = item.taskId?.let { taskId ->
                                state.knownTasks.firstOrNull { it.id == taskId }?.title
                            },
                            showPending = state.authStatus == AuthStatus.SignedIn,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
            }
            }
        }
    }

    if (showLogoutDialog && state.authStatus == AuthStatus.SignedIn) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = Cloud,
            icon = { BrandOrb(42.dp) },
            title = { Text("Log out on this device?") },
            text = {
                Text(
                    if (state.pendingCount > 0) {
                        "${state.pendingCount} action${if (state.pendingCount == 1) " is" else "s are"} still waiting to sync. Logging out discards ${if (state.pendingCount == 1) "it" else "them"}."
                    } else {
                        "Local account data will be removed. Your synced history stays on your account."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                ) { Text("Log out", color = DangerText, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Stay signed in") }
            },
        )
    }

    state.accountSwitch?.let { accountSwitch ->
        AlertDialog(
            onDismissRequest = {},
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = Cloud,
            title = { Text("Different account detected") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Local data belongs to ${accountSwitch.localAccount}, but ${accountSwitch.incomingAccount} is signed in. Switching accounts permanently removes this device's timer, history, tasks, and unsynced operations.",
                    )
                    accountSwitch.error?.let { Text(it, color = DangerText, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmAccountSwitch,
                    enabled = !accountSwitch.submitting,
                ) { Text("Switch and remove local data", color = DangerText, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelAccountSwitch,
                    enabled = !accountSwitch.submitting,
                ) { Text("Keep local data") }
            },
        )
    }

    state.historyResolution?.let { resolution ->
        val selected = resolution.pendingStrategy ?: confirmationStrategy
        val confirmingKeepRemoteRecovery = resolution.corrupted &&
            resolution.recovery == ResolutionRecovery.KeepRemote &&
            selected == BootstrapStrategy.KeepRemote
        AlertDialog(
            onDismissRequest = {
                if (resolution.pendingStrategy == null && !resolution.submitting) {
                    confirmationStrategy = null
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = Cloud,
            title = {
                Text(
                    when {
                        confirmingKeepRemoteRecovery -> "Confirm Keep Remote"
                        resolution.recovery == ResolutionRecovery.KeepRemote -> "Local changes cannot be submitted"
                        resolution.corrupted -> "Saved history choice is corrupted"
                        resolution.submitting -> "Applying history choice"
                        resolution.pendingStrategy != null -> "Retry history choice"
                        selected != null -> "Confirm ${resolutionLabel(selected)}"
                        else -> "Choose your history"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (confirmingKeepRemoteRecovery) {
                            resolutionWarning(BootstrapStrategy.KeepRemote)
                        } else if (resolution.recovery == ResolutionRecovery.KeepRemote) {
                            "Local queues exceed the safe request limit or contain invalid saved data. Keep Remote is available, but it will discard those queues only after the server confirms."
                        } else if (resolution.corrupted) {
                            "Discard only the corrupted saved request, then fetch account history again. Local timer, history, tasks, settings, and queued operations stay on this device."
                        } else if (selected == null) {
                            "This device and your account both contain synchronized timer, history, task, or settings data. Choose how to continue before making more changes."
                        } else {
                            resolutionWarning(selected)
                        },
                    )
                    resolution.error?.let { Text(it, color = DangerText, fontWeight = FontWeight.Bold) }
                    if (resolution.pendingStrategy != null) {
                        Text("Retry sends the exact saved request ID and operation payload.")
                    }
                }
            },
            confirmButton = {
                if (resolution.corrupted && !confirmingKeepRemoteRecovery) {
                    when (resolution.recovery) {
                        ResolutionRecovery.KeepRemote -> TextButton(
                            onClick = { confirmationStrategy = BootstrapStrategy.KeepRemote },
                            enabled = !resolution.submitting,
                        ) { Text("Review Keep Remote", fontWeight = FontWeight.Bold) }
                        ResolutionRecovery.Repreview -> TextButton(
                            onClick = {
                                if (state.authStatus == AuthStatus.SignedIn) {
                                    onRecoverHistoryResolution()
                                } else {
                                    onSignIn()
                                }
                            },
                            enabled = !resolution.submitting &&
                                (state.authStatus == AuthStatus.SignedIn ||
                                    state.authStatus == AuthStatus.SignedOut),
                        ) {
                            Text(
                                when (state.authStatus) {
                                    AuthStatus.SignedOut -> "Sign in to re-check"
                                    AuthStatus.SigningIn -> "Signing in..."
                                    AuthStatus.Loading -> "Checking account..."
                                    AuthStatus.SignedIn -> "Discard request and re-check"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        null -> Unit
                    }
                } else if (selected == null) {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { confirmationStrategy = BootstrapStrategy.ReplaceRemote }) {
                            Text("Keep Local")
                        }
                        TextButton(onClick = { confirmationStrategy = BootstrapStrategy.KeepRemote }) {
                            Text("Keep Remote")
                        }
                        TextButton(onClick = { confirmationStrategy = BootstrapStrategy.Merge }) {
                            Text("Keep Both")
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (state.authStatus == AuthStatus.SignedIn) {
                                onResolveHistory(selected)
                            } else {
                                onSignIn()
                            }
                        },
                        enabled = !resolution.submitting &&
                            (state.authStatus == AuthStatus.SignedIn || state.authStatus == AuthStatus.SignedOut),
                    ) {
                        Text(
                            when {
                                state.authStatus == AuthStatus.SignedOut -> "Sign in to retry"
                                state.authStatus == AuthStatus.SigningIn -> "Signing in to retry..."
                                state.authStatus == AuthStatus.Loading -> "Checking account..."
                                resolution.pendingStrategy != null -> "Retry"
                                else -> "Confirm ${resolutionLabel(selected)}"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            dismissButton = if (
                selected != null &&
                resolution.pendingStrategy == null &&
                (!resolution.corrupted || confirmingKeepRemoteRecovery)
            ) {
                {
                    TextButton(
                        onClick = { confirmationStrategy = null },
                        enabled = !resolution.submitting,
                        modifier = Modifier.semantics {
                            contentDescription = "Cancel history choice"
                        },
                    ) { Text("Cancel") }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun PortraitTimerScreen(
    state: AppState,
    mutationsEnabled: Boolean,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectTask: (String?) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppHeader(state = state, onSignIn = onSignIn, onLogout = onLogout)
        state.conflict?.let {
            MessageCard(
                title = "Changes resolved on another device",
                message = it,
                containerColor = Lavender,
                onDismiss = onDismissConflict,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        state.notice?.let {
            NoticeCard(
                message = it,
                onDismiss = onDismissNotice,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val orbitSize = (maxHeight - 230.dp).coerceIn(132.dp, 318.dp)
            TimerHero(
                timer = state.timer,
                settings = state.settings,
                taskTitle = state.timer?.taskId?.let { taskId ->
                    state.knownTasks.firstOrNull { it.id == taskId }?.title
                },
                ready = state.ready && mutationsEnabled,
                onToggleTimer = onToggleTimer,
                onFinishTimer = onFinishTimer,
                onCancelTimer = onCancelTimer,
                onClearTimer = onClearTimer,
                tasks = state.tasks,
                selectedTaskId = state.selectedTaskId,
                mutationsEnabled = mutationsEnabled,
                onSelectTask = onSelectTask,
                orbitMaxSize = orbitSize,
            )
        }
    }
}

@Composable
private fun LandscapeTimerScreen(
    state: AppState,
    mutationsEnabled: Boolean,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectTask: (String?) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.conflict?.let {
            MessageCard(
                title = "Changes resolved on another device",
                message = it,
                containerColor = Lavender,
                onDismiss = onDismissConflict,
            )
        }
        state.notice?.let {
            NoticeCard(message = it, onDismiss = onDismissNotice)
        }
        TimerHero(
            timer = state.timer,
            settings = state.settings,
            taskTitle = state.timer?.taskId?.let { taskId ->
                state.knownTasks.firstOrNull { it.id == taskId }?.title
            },
            ready = state.ready && mutationsEnabled,
            onToggleTimer = onToggleTimer,
            onFinishTimer = onFinishTimer,
            onCancelTimer = onCancelTimer,
            onClearTimer = onClearTimer,
            tasks = state.tasks,
            selectedTaskId = state.selectedTaskId,
            mutationsEnabled = mutationsEnabled,
            onSelectTask = onSelectTask,
            landscape = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AppHeader(state: AppState, onSignIn: () -> Unit, onLogout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Ink,
        contentColor = Cloud,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 10.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(compact = true)
                when (state.authStatus) {
                    AuthStatus.SignedIn -> TextButton(onClick = onLogout) {
                        Text("Log out", color = Lavender)
                    }
                    AuthStatus.SigningIn -> TextButton(onClick = {}, enabled = false) {
                        Text("Signing in...", color = Lavender)
                    }
                    AuthStatus.Loading -> TextButton(onClick = {}, enabled = false) {
                        Text("Checking...", color = Lavender)
                    }
                    AuthStatus.SignedOut -> TextButton(onClick = onSignIn) {
                        Text("Sign in", color = Butter, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = syncColor(state.syncStatus),
                    contentColor = Ink,
                    shape = CircleShape,
                ) {
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
                Text(
                    text = when (state.authStatus) {
                        AuthStatus.SignedIn -> state.user?.name?.ifBlank { state.user.email } ?: "Signed in"
                        AuthStatus.Loading -> "Checking account"
                        AuthStatus.SigningIn -> "Signing in"
                        AuthStatus.SignedOut -> ""
                    },
                    modifier = Modifier.weight(1f),
                    color = Cloud,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private enum class MainTab(val label: String) {
    Timer("Timer"),
    Tasks("Tasks"),
    Pattern("Pattern"),
    Arrivals("Arrivals"),
}

@Composable
private fun MainNavigationBar(
    active: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
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
                        },
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun TaskSelector(
    tasks: List<FocusTask>,
    selectedTaskId: String?,
    timer: CanonicalTimer?,
    taskTitle: String?,
    selectedPhase: String,
    mutationsEnabled: Boolean,
    onSelectTask: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = timer?.status == TimerStatus.Running || timer?.status == TimerStatus.Paused
    val enabled = mutationsEnabled && !active && selectedPhase == TimerPhase.Focus
    val selectedTitle = if (active) {
        taskTitle ?: "No task"
    } else {
        tasks.firstOrNull { it.id == selectedTaskId }?.title ?: "No task"
    }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            border = BorderStroke(1.dp, Ink.copy(alpha = 0.35f)),
        ) {
            Text("FOCUS TASK", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                selectedTitle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("No task") },
                onClick = {
                    expanded = false
                    onSelectTask(null)
                },
            )
            tasks.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelectTask(task.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskBoardHeader(
    summaries: List<TaskDailySummary>,
    mutationsEnabled: Boolean,
    onAddTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val totalFinished = summaries.sumOf(TaskDailySummary::finishedPomodoros)
    val totalTime = summaries.sumOf(TaskDailySummary::timeSpentMs)
    Column(modifier) {
        SectionLabel("TASK BOARD")
        Text("Focus by destination", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "$totalFinished pomodoros · ${formatTaskDuration(totalTime)} today",
            color = Violet,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Surface(color = Butter, contentColor = Ink, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Task") },
                    placeholder = { Text("Write release notes") },
                    singleLine = true,
                    enabled = mutationsEnabled,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (draft.isNotEmpty()) {
                            onAddTask(draft)
                            draft = ""
                        }
                    },
                    enabled = mutationsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) { Text("ADD TASK") }
            }
        }
    }
}

@Composable
private fun TaskColumnLabels(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text("Task", Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
        Text(
            "Finished pomodoros today",
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Time today spent",
            Modifier.weight(1.35f),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Action",
            Modifier.weight(0.9f),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun TaskSummaryRow(
    summary: TaskDailySummary,
    mutationsEnabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Ink,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(4.55f)
                    .clearAndSetSemantics {
                        contentDescription = taskSummaryDescription(summary)
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
                Text(
                    summary.finishedPomodoros.toString(),
                    Modifier.weight(1.2f),
                    color = Violet,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                Text(
                    formatTaskDuration(summary.timeSpentMs),
                    Modifier.weight(1.35f),
                    color = Violet,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = onDelete,
                enabled = mutationsEnabled,
                modifier = Modifier.weight(0.9f),
            ) {
                Text("Delete", color = DangerText)
            }
        }
    }
}

@Composable
private fun TimerHero(
    timer: CanonicalTimer?,
    settings: TimerSettings,
    taskTitle: String?,
    ready: Boolean,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    tasks: List<FocusTask>,
    selectedTaskId: String?,
    mutationsEnabled: Boolean,
    onSelectTask: (String?) -> Unit,
    landscape: Boolean = false,
    orbitMaxSize: Dp = 318.dp,
    modifier: Modifier = Modifier,
) {
    val status = timer?.status ?: "idle"
    val phase = timer?.phase ?: settings.selectedPhase
    val showContextLabel = LocalConfiguration.current.fontScale < 1.3f
    val active = status == TimerStatus.Running || status == TimerStatus.Paused
    val clearable = timer != null && !active
    val palette = phasePalette(phase)
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
        contentColor = if (landscape) Cloud else palette.onContainer,
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
            LandscapeTimerHero(
                timer = timer,
                settings = settings,
                taskTitle = taskTitle,
                tasks = tasks,
                selectedTaskId = selectedTaskId,
                ready = ready,
                mutationsEnabled = mutationsEnabled,
                onToggleTimer = onToggleTimer,
                onFinishTimer = onFinishTimer,
                onCancelTimer = onCancelTimer,
                onClearTimer = onClearTimer,
                onSelectTask = onSelectTask,
                showContextLabel = showContextLabel,
            )
            return@Surface
        }
        Column(Modifier.padding(14.dp)) {
            if (showContextLabel) {
                SectionLabel("CURRENT TIMER")
                Spacer(Modifier.height(6.dp))
            }
            TimerOrbit(timer = timer, settings = settings, palette = palette, maxSize = orbitMaxSize)
            Spacer(Modifier.height(6.dp))
            TaskSelector(
                tasks = tasks,
                selectedTaskId = selectedTaskId,
                timer = timer,
                taskTitle = taskTitle,
                selectedPhase = settings.selectedPhase,
                mutationsEnabled = mutationsEnabled,
                onSelectTask = onSelectTask,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                timerInstruction(status),
                modifier = Modifier.fillMaxWidth(),
                color = palette.onContainer.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onToggleTimer,
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = Cloud,
                ),
            ) {
                Text(
                    when (status) {
                        TimerStatus.Running -> "Pause"
                        TimerStatus.Paused -> "Resume"
                        else -> "Start ${phaseLabel(settings.selectedPhase).lowercase()}"
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onFinishTimer,
                    enabled = ready && active,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("Finish") }
                OutlinedButton(
                    onClick = {
                        if (active) onCancelTimer() else onClearTimer()
                    },
                    enabled = ready && (active || clearable),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    border = BorderStroke(1.5.dp, palette.onContainer.copy(alpha = 0.55f)),
                ) { Text(if (active) "Cancel" else "Clear") }
            }
        }
    }
}

@Composable
private fun LandscapeTimerHero(
    timer: CanonicalTimer?,
    settings: TimerSettings,
    taskTitle: String?,
    tasks: List<FocusTask>,
    selectedTaskId: String?,
    ready: Boolean,
    mutationsEnabled: Boolean,
    onToggleTimer: () -> Unit,
    onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onClearTimer: () -> Unit,
    onSelectTask: (String?) -> Unit,
    showContextLabel: Boolean,
) {
    val status = timer?.status ?: "idle"
    val phase = timer?.phase ?: settings.selectedPhase
    val active = status == TimerStatus.Running || status == TimerStatus.Paused
    val clearable = timer != null && !active

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showContextLabel) {
            Text("CURRENT SERVICE", color = Cloud, style = MaterialTheme.typography.labelMedium)
        }
        LandscapeTimerReadout(
            timer = timer,
            settings = settings,
            modifier = Modifier.weight(1f),
        )
        LandscapeTaskSelector(
            tasks = tasks,
            selectedTaskId = selectedTaskId,
            timer = timer,
            taskTitle = taskTitle,
            selectedPhase = settings.selectedPhase,
            mutationsEnabled = mutationsEnabled,
            onSelectTask = onSelectTask,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onToggleTimer,
                enabled = ready,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Butter, contentColor = Ink),
            ) {
                Text(
                    when (status) {
                        TimerStatus.Running -> "Pause"
                        TimerStatus.Paused -> "Resume"
                        else -> "Start ${phaseLabel(settings.selectedPhase).lowercase()}"
                    },
                    maxLines = 1,
                )
            }
            if (active) {
                FilledTonalButton(
                    onClick = onFinishTimer,
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                ) { Text("Finish") }
                OutlinedButton(
                    onClick = onCancelTimer,
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    border = BorderStroke(1.5.dp, Cloud.copy(alpha = 0.65f)),
                ) { Text("Cancel", color = Cloud) }
            } else if (clearable) {
                OutlinedButton(
                    onClick = onClearTimer,
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    border = BorderStroke(1.5.dp, Cloud.copy(alpha = 0.65f)),
                ) { Text("Clear", color = Cloud) }
            }
        }
    }
}

@Composable
private fun LandscapeTimerReadout(
    timer: CanonicalTimer?,
    settings: TimerSettings,
    modifier: Modifier = Modifier,
) {
    var now by remember(timer?.id, timer?.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer?.id, timer?.status, timer?.anchorAt) {
        while (timer?.status == TimerStatus.Running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val phase = timer?.phase ?: settings.selectedPhase
    val duration = timer?.plannedDurationMs ?: settings.durationMsFor(phase)
    val elapsed = if (timer == null) 0 else TimerReducer.elapsedAt(timer, now)
    val remaining = (duration - elapsed).coerceAtLeast(0)
    val totalSeconds = ceil(remaining / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val progress = if (duration > 0) elapsed.toFloat() / duration else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "Landscape timer progress",
    )
    val status = timer?.status ?: "idle"
    val timeText = timerTimeText(minutes, seconds, status == TimerStatus.Paused, Butter)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Cloud.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                contentDescription = "$minutes minutes $seconds seconds remaining, ${phaseLabel(phase)}, $status"
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(phaseRouteLabel(phase).uppercase(), color = Cloud, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(phaseLabel(phase).uppercase(), color = Butter, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            text = timeText,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            color = Butter,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 112.sp,
            letterSpacing = (-6).sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .background(Cloud.copy(alpha = 0.15f), CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(9.dp)
                    .background(Danger, CircleShape),
            )
        }
    }
}

@Composable
private fun LandscapeTaskSelector(
    tasks: List<FocusTask>,
    selectedTaskId: String?,
    timer: CanonicalTimer?,
    taskTitle: String?,
    selectedPhase: String,
    mutationsEnabled: Boolean,
    onSelectTask: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = timer?.status == TimerStatus.Running || timer?.status == TimerStatus.Paused
    val enabled = mutationsEnabled && !active && selectedPhase == TimerPhase.Focus
    val title = if (active) {
        taskTitle ?: "No task"
    } else {
        tasks.firstOrNull { it.id == selectedTaskId }?.title ?: "No task"
    }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            border = BorderStroke(1.dp, Cloud.copy(alpha = 0.5f)),
        ) {
            Text("FOCUS TASK", color = Cloud, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = Butter,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No task") },
                onClick = {
                    expanded = false
                    onSelectTask(null)
                },
            )
            tasks.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelectTask(task.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun TimerOrbit(
    timer: CanonicalTimer?,
    settings: TimerSettings,
    palette: PhasePalette,
    maxSize: Dp,
) {
    var now by remember(timer?.id, timer?.status) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer?.id, timer?.status, timer?.anchorAt) {
        while (timer?.status == TimerStatus.Running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val phase = timer?.phase ?: settings.selectedPhase
    val duration = timer?.plannedDurationMs ?: settings.durationMsFor(phase)
    val elapsed = if (timer == null) 0 else TimerReducer.elapsedAt(timer, now)
    val remaining = (duration - elapsed).coerceAtLeast(0)
    val totalSeconds = ceil(remaining / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val progress = if (duration > 0) elapsed.toFloat() / duration else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "Timer progress",
    )
    val status = timer?.status ?: "idle"
    val timeText = timerTimeText(minutes, seconds, status == TimerStatus.Paused, palette.onContainer)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val orbitSize = min(min(maxWidth.value, maxSize.value), 318f).dp
        Box(
            modifier = Modifier
                .size(orbitSize)
                .clearAndSetSemantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                    contentDescription = "$minutes minutes $seconds seconds remaining, ${phaseLabel(phase)}, $status"
                },
            contentAlignment = Alignment.Center,
        ) {
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
                val sweep = 360f * animatedProgress
                drawArc(
                    color = palette.accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                if (animatedProgress > 0f) {
                    val radius = (size.minDimension - strokeWidth) / 2
                    val angle = (sweep - 90f) * (PI.toFloat() / 180f)
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        color = palette.onContainer,
                        radius = 5.dp.toPx(),
                        center = Offset(
                            center.x + cos(angle) * radius,
                            center.y + sin(angle) * radius,
                        ),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timeText,
                    color = palette.onContainer,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = if (orbitSize < 280.dp) 48.sp else 62.sp,
                    letterSpacing = (-3).sp,
                )
                Text(
                    text = phaseLabel(phase),
                    color = palette.onContainer.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = palette.onContainer.copy(alpha = 0.1f),
                    contentColor = palette.onContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        "${duration / 60_000} min",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternSection(
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
        SectionLabel("YOUR RHYTHM")
        Text("Shape your session", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose what comes next, then tune its length.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        PhaseCard(
            phase = TimerPhase.Focus,
            supportingText = "Deep work",
            settings = settings,
            enabled = mutationsEnabled && !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(10.dp))
        PhaseCard(
            phase = TimerPhase.ShortBreak,
            supportingText = "Quick reset",
            settings = settings,
            enabled = mutationsEnabled && !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(10.dp))
        PhaseCard(
            phase = TimerPhase.LongBreak,
            supportingText = "Full recharge",
            settings = settings,
            enabled = mutationsEnabled && !active,
            onSelect = onSelectPhase,
            onChangeDuration = onChangeDuration,
        )
        Spacer(Modifier.height(12.dp))
        Surface(color = Ink, contentColor = Cloud, shape = MaterialTheme.shapes.large) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-start breaks", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Short after focus, long every fourth.",
                        color = Lavender,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.autoStartBreaks,
                    onCheckedChange = onSetAutoStart,
                    enabled = mutationsEnabled,
                )
            }
        }
    }
}

@Composable
private fun PhaseCard(
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
    val shape = if (selected) {
        RoundedCornerShape(topStart = 36.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 36.dp)
    } else {
        MaterialTheme.shapes.large
    }
    Surface(
        onClick = { onSelect(phase) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                stateDescription = "$minutes minutes"
            },
        color = if (selected) palette.container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Ink,
        shape = shape,
        border = if (selected) BorderStroke(2.dp, palette.accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (selected) palette.accent else MaterialTheme.colorScheme.outline, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(phaseLabel(phase), style = MaterialTheme.typography.titleLarge)
                Text(supportingText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            StepButton("−", "Decrease ${phaseLabel(phase)} duration", enabled) {
                onChangeDuration(phase, -1)
            }
            Text(
                "$minutes",
                modifier = Modifier
                    .width(45.dp)
                    .clearAndSetSemantics { },
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
            StepButton("+", "Increase ${phaseLabel(phase)} duration", enabled) {
                onChangeDuration(phase, 1)
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun HistoryTitle(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            SectionLabel("COMPLETED SESSIONS")
            Text("Recent focus", style = MaterialTheme.typography.headlineMedium)
        }
        Surface(color = Violet, contentColor = Cloud, shape = CircleShape) {
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
private fun HistoryRow(
    item: HistoryItem,
    taskTitle: String?,
    showPending: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = phasePalette(item.phase)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = historyDescription(item, taskTitle, showPending)
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Ink,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = palette.container,
                contentColor = Ink,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(phaseStamp(item.phase), fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    phaseLabel(item.phase) + if (item.pending && showPending) " · queued" else "",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(formatHistoryDate(item), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (taskTitle != null) {
                    Text(taskTitle, color = Violet, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "${(item.plannedDurationMs / 60_000).coerceAtLeast(1)} min",
                color = Violet,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    containerColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = containerColor, shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Ink) }
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    MessageCard(
        title = "Heads up",
        message = message,
        containerColor = Butter,
        onDismiss = onDismiss,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun BrandMark(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandOrb(if (compact) 38.dp else 52.dp)
        Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
        Text(
            "pomodorough",
            color = Cloud,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun BrandOrb(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Butter, RoundedCornerShape(topStart = 50.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 50.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.4f).background(DangerAccent, CircleShape))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

private data class PhasePalette(
    val container: Color,
    val accent: Color,
    val onContainer: Color = Ink,
)

private fun phasePalette(phase: String): PhasePalette = when (phase) {
    TimerPhase.ShortBreak, TimerPhase.LongBreak -> PhasePalette(container = Lavender, accent = DangerAccent)
    else -> PhasePalette(container = Butter, accent = DangerAccent)
}

private fun syncLabel(state: AppState): String {
    if (state.historyResolution != null) return "History choice needed"
    if (state.authStatus != AuthStatus.SignedIn) {
        return when (state.authStatus) {
            AuthStatus.Loading -> "Checking account"
            AuthStatus.SigningIn -> "Signing in"
            else -> "Local only"
        }
    }
    return when (state.syncStatus) {
        SyncStatus.Offline -> if (state.pendingCount > 0) "Offline · ${state.pendingCount} queued" else "Offline · local"
        SyncStatus.Conflict -> if (state.pendingCount > 0) "Conflict · ${state.pendingCount} queued" else "Conflict"
        SyncStatus.Syncing -> "Syncing"
        SyncStatus.Retrying -> if (state.pendingCount > 0) "Retrying · ${state.pendingCount} queued" else "Retrying sync"
        SyncStatus.Queued -> "${state.pendingCount} waiting to sync"
        SyncStatus.Checking -> "Checking sync"
        SyncStatus.Synced -> "In sync"
    }
}

private fun resolutionLabel(strategy: BootstrapStrategy): String = when (strategy) {
    BootstrapStrategy.ReplaceRemote -> "Keep Local"
    BootstrapStrategy.KeepRemote -> "Keep Remote"
    BootstrapStrategy.Merge -> "Keep Both"
}

private fun resolutionWarning(strategy: BootstrapStrategy): String = when (strategy) {
    BootstrapStrategy.ReplaceRemote ->
        "Remote account history will be replaced by this device's local history. This cannot be undone."
    BootstrapStrategy.KeepRemote ->
        "Local history and unsynced operations will be removed, then remote account history will be installed. This cannot be undone."
    BootstrapStrategy.Merge ->
        "Local and remote operations will be combined. Conflicting operations may be ignored or rejected, and errors are possible."
}

private fun syncColor(status: SyncStatus): Color = when (status) {
    SyncStatus.Synced, SyncStatus.Offline -> Lavender
    SyncStatus.Conflict, SyncStatus.Retrying -> Butter
    SyncStatus.Syncing, SyncStatus.Queued, SyncStatus.Checking -> Butter
}

private fun timerInstruction(status: String): String = when (status) {
    TimerStatus.Running -> "Pause whenever you need."
    TimerStatus.Paused -> "Resume when ready."
    TimerStatus.Completed -> "Session complete."
    TimerStatus.Cancelled -> "Start again when ready."
    TimerStatus.Superseded -> "This timer continued on another device."
    else -> "Start when ready."
}

@Composable
private fun timerTimeText(minutes: Long, seconds: Long, paused: Boolean, color: Color): AnnotatedString {
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

private fun phaseLabel(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "Short break"
    TimerPhase.LongBreak -> "Long break"
    else -> "Focus"
}

private fun phaseRouteLabel(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "Reset"
    TimerPhase.LongBreak -> "Recover"
    else -> "Work"
}

private fun phaseStamp(phase: String): String = when (phase) {
    TimerPhase.ShortBreak -> "SB"
    TimerPhase.LongBreak -> "LB"
    else -> "F"
}

private fun historyEpoch(item: HistoryItem): Long {
    val value = item.completedAt ?: item.endedAt ?: return 0
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
}

private fun formatTaskDuration(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    if (minutes == 0L) return "0 min"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0L -> "$minutes min"
        remainingMinutes == 0L -> "$hours hr"
        else -> "$hours hr $remainingMinutes min"
    }
}

private fun taskSummaryDescription(summary: TaskDailySummary): String {
    val pomodoros = if (summary.finishedPomodoros == 1) "pomodoro" else "pomodoros"
    return "${summary.task.title}, ${summary.finishedPomodoros} finished $pomodoros today, " +
        "${formatTaskDuration(summary.timeSpentMs)} spent"
}

private fun historyDescription(item: HistoryItem, taskTitle: String?, showPending: Boolean): String {
    val pending = if (item.pending && showPending) ", queued" else ""
    val task = taskTitle?.let { ", task $it" }.orEmpty()
    return "${phaseLabel(item.phase)}, ${item.status}$pending, ${formatHistoryDate(item)}$task, " +
        formatTaskDuration(item.plannedDurationMs)
}

private fun formatHistoryDate(item: HistoryItem): String {
    val value = item.completedAt ?: item.endedAt ?: return "Time not recorded"
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault("Time not recorded")
}
