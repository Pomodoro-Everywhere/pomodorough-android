package me.egigoka.pomodorough.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AccountSwitchState
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.HistoryResolutionState
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerScreen(state: AppState, actions: TimerScreenActions) {
    val ui = rememberTimerScreenUiState(state)
    val mutationsEnabled = mutationsEnabled(state)
    val recentHistory = remember(state.history) { state.history.sortedByDescending(::historyEpoch) }
    AutoDismissNotice(state.notice, actions.onDismissNotice)
    TimerScreenLayout(state, actions, ui, mutationsEnabled, recentHistory)
    TimerScreenDialogs(state, actions, ui)
}

private class TimerDialogState {
    var showLogout by mutableStateOf(false)
    var showAccount by mutableStateOf(false)
    var showLocalReset by mutableStateOf(false)
    var showDelete by mutableStateOf(false)
    var deleteConfirmation by mutableStateOf("")
}

private class TimerNavigationState(
    private val tasks: LazyListState,
    private val pattern: LazyListState,
    private val arrivals: LazyListState,
    private val network: LazyListState,
) {
    var activeTab by mutableStateOf(MainTab.Timer)

    fun listState(): LazyListState = when (activeTab) {
        MainTab.Timer -> error("Timer tab does not use a list")
        MainTab.Tasks -> tasks
        MainTab.Pattern -> pattern
        MainTab.Arrivals -> arrivals
        MainTab.Network -> network
    }
}

private data class TimerScreenUiState(
    val dialogs: TimerDialogState,
    val navigation: TimerNavigationState,
    val confirmationStrategy: MutableState<BootstrapStrategy?>,
)

@Composable
private fun rememberTimerScreenUiState(state: AppState): TimerScreenUiState {
    val dialogs = remember { TimerDialogState() }
    val tasks = rememberLazyListState()
    val pattern = rememberLazyListState()
    val arrivals = rememberLazyListState()
    val network = rememberLazyListState()
    val navigation = remember(tasks, pattern, arrivals, network) {
        TimerNavigationState(
            tasks = tasks,
            pattern = pattern,
            arrivals = arrivals,
            network = network,
        )
    }
    val confirmation = remember(
        state.historyResolution?.requestId,
        state.historyResolution?.pendingStrategy,
        state.historyResolution?.recovery,
    ) { mutableStateOf<BootstrapStrategy?>(null) }
    return remember(dialogs, navigation, confirmation) {
        TimerScreenUiState(dialogs, navigation, confirmation)
    }
}

private fun mutationsEnabled(state: AppState): Boolean =
    state.authStatus != AuthStatus.Loading &&
        state.authStatus != AuthStatus.SigningIn &&
        !state.localAccountResetRequired &&
        state.historyResolution == null &&
        state.accountSwitch == null

@Composable
private fun TimerScreenLayout(
    state: AppState,
    actions: TimerScreenActions,
    ui: TimerScreenUiState,
    mutationsEnabled: Boolean,
    recentHistory: List<HistoryItem>,
) {
    val contentActions = timerContentActions(actions, ui.dialogs)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscapeTimer = maxWidth > maxHeight && ui.navigation.activeTab == MainTab.Timer
        if (landscapeTimer) {
            LandscapeTimerScreen(state, mutationsEnabled, contentActions)
        } else {
            PortraitScreenScaffold(state, actions, contentActions, ui, mutationsEnabled, recentHistory)
        }
    }
}

private fun timerContentActions(
    actions: TimerScreenActions,
    dialogs: TimerDialogState,
) = TimerContentActions(
    header = AppHeaderActions(
        onSignIn = actions.onSignIn,
        onLogout = { dialogs.showAccount = true },
        onResetLocalAccount = { dialogs.showLocalReset = true },
    ),
    hero = TimerHeroActions(
        onToggleTimer = actions.onToggleTimer,
        onFinishTimer = actions.onFinishTimer,
        onCancelTimer = actions.onCancelTimer,
        onClearTimer = actions.onClearTimer,
        onStopSound = actions.onStopSound,
        onSelectTask = actions.onSelectTask,
    ),
    onDismissConflict = actions.onDismissConflict,
    onDismissNotice = actions.onDismissNotice,
)

@Composable
private fun PortraitScreenScaffold(
    state: AppState,
    actions: TimerScreenActions,
    contentActions: TimerContentActions,
    ui: TimerScreenUiState,
    mutationsEnabled: Boolean,
    recentHistory: List<HistoryItem>,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        bottomBar = {
            MainNavigationBar(ui.navigation.activeTab) { ui.navigation.activeTab = it }
        },
    ) { padding ->
        if (ui.navigation.activeTab == MainTab.Timer) {
            PortraitTimerTab(state, actions.onRefresh, contentActions, mutationsEnabled, padding)
        } else {
            SecondaryTabList(state, actions, contentActions, ui.navigation, mutationsEnabled, recentHistory, padding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitTimerTab(
    state: AppState,
    onRefresh: () -> Unit,
    actions: TimerContentActions,
    mutationsEnabled: Boolean,
    padding: PaddingValues,
) {
    val modifier = Modifier.fillMaxSize().padding(padding)
    if (state.authStatus == AuthStatus.SignedIn) {
        val pullGestureState = rememberScrollableState { 0f }
        PullToRefreshBox(
            isRefreshing = state.syncStatus == SyncStatus.Syncing,
            onRefresh = onRefresh,
            modifier = modifier.testTag("timer_pull_to_refresh"),
        ) {
            Box(Modifier.fillMaxSize().scrollable(pullGestureState, Orientation.Vertical)) {
                PortraitTimerScreen(state, mutationsEnabled, actions)
            }
        }
    } else {
        Box(modifier) { PortraitTimerScreen(state, mutationsEnabled, actions) }
    }
}

@Composable
private fun SecondaryTabList(
    state: AppState,
    actions: TimerScreenActions,
    contentActions: TimerContentActions,
    navigation: TimerNavigationState,
    mutationsEnabled: Boolean,
    recentHistory: List<HistoryItem>,
    padding: PaddingValues,
) {
    val activeTab = navigation.activeTab
    key(activeTab) {
        LazyColumn(
            state = navigation.listState(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            secondaryHeader(state, contentActions)
            when (activeTab) {
                MainTab.Timer -> Unit
                MainTab.Tasks -> taskTab(state, actions, mutationsEnabled)
                MainTab.Pattern -> patternTab(state, actions, mutationsEnabled)
                MainTab.Arrivals -> arrivalsTab(state, recentHistory)
                MainTab.Network -> networkTab(state, actions, mutationsEnabled)
            }
        }
    }
}

private fun LazyListScope.secondaryHeader(state: AppState, actions: TimerContentActions) {
    item { AppHeader(state, actions.header) }
    state.conflict?.let { conflict ->
        item {
            MessageCard(
                stringResource(R.string.changes_resolved_on_another_device),
                conflict,
                Lavender,
                actions.onDismissConflict,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    state.notice?.let { notice ->
        item {
            NoticeCard(
                notice,
                actions.onDismissNotice,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private fun LazyListScope.taskTab(
    state: AppState,
    actions: TimerScreenActions,
    mutationsEnabled: Boolean,
) {
    item {
        TaskBoardHeader(
            state.taskSummaries,
            mutationsEnabled,
            actions.onAddTask,
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
    if (state.taskSummaries.isEmpty()) {
        item {
            EmptyTabCard(
                stringResource(R.string.no_tasks_yet),
                stringResource(R.string.add_a_task_then_assign_it_before_starting),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        item { TaskColumnLabels(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        items(state.taskSummaries, key = { it.task.id }) { summary ->
            TaskSummaryRow(
                summary,
                mutationsEnabled,
                { actions.onDeleteTask(summary.task.id) },
                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }
}

private fun LazyListScope.patternTab(
    state: AppState,
    actions: TimerScreenActions,
    mutationsEnabled: Boolean,
) {
    item {
        PatternSection(
            state.settings,
            state.timer,
            mutationsEnabled,
            actions.onSelectPhase,
            actions.onChangeDuration,
            actions.onSetAutoStart,
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        )
    }
}

private fun LazyListScope.arrivalsTab(state: AppState, history: List<HistoryItem>) {
    item {
        HistoryTitle(history.size, Modifier.padding(horizontal = 16.dp, vertical = 18.dp))
    }
    item {
        CompletedFocusBreakdown(
            history,
            state.knownTasks,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (history.isEmpty()) {
        item {
            EmptyTabCard(
                stringResource(R.string.no_arrivals_yet),
                stringResource(R.string.your_first_run_appears_here),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    } else {
        items(history, key = { it.id }) { historyItem ->
            HistoryRow(
                historyItem,
                historyItem.taskId?.let { taskId -> state.knownTasks.firstOrNull { it.id == taskId }?.title },
                state.authStatus == AuthStatus.SignedIn,
                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }
}

private fun LazyListScope.networkTab(
    state: AppState,
    actions: TimerScreenActions,
    mutationsEnabled: Boolean,
) {
    item {
        NetworkSection(
            state,
            mutationsEnabled,
            NetworkActions(
                onSetMode = actions.onSetReplicationMode,
                onCreateRoom = actions.onCreateIrohRoom,
                onJoinRoom = actions.onJoinIrohRoom,
                onLeaveRoom = actions.onLeaveIrohRoom,
                onRefreshInvite = actions.onRefreshIrohInvite,
                onSyncNow = actions.onSyncIrohNow,
                onConfirmIdentityRecovery = actions.onConfirmIrohIdentityRecovery,
                onShareInvite = actions.onShareIrohInvite,
            ),
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun EmptyTabCard(title: String, description: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TimerScreenDialogs(state: AppState, actions: TimerScreenActions, ui: TimerScreenUiState) {
    val dialogs = ui.dialogs
    if (dialogs.showAccount && state.authStatus == AuthStatus.SignedIn) {
        AccountDialog(state, actions, dialogs, ui.navigation)
    }
    if (dialogs.showDelete && state.authStatus == AuthStatus.SignedIn) {
        DeleteAccountDialog(actions, dialogs)
    }
    if (dialogs.showLogout && state.authStatus == AuthStatus.SignedIn) {
        LogoutDialog(state.pendingCount, actions.onLogout) { dialogs.showLogout = false }
    }
    if (dialogs.showLocalReset && state.localAccountResetRequired) {
        LocalResetDialog(actions.onResetLocalAccount) { dialogs.showLocalReset = false }
    }
    state.accountSwitch?.let {
        AccountSwitchDialog(it, actions.onConfirmAccountSwitch, actions.onCancelAccountSwitch)
    }
    state.historyResolution?.takeUnless { state.localAccountResetRequired }?.let {
        HistoryResolutionDialog(state.authStatus, it, ui.confirmationStrategy, actions)
    }
}

@Composable
private fun AccountDialog(
    state: AppState,
    actions: TimerScreenActions,
    dialogs: TimerDialogState,
    navigation: TimerNavigationState,
) {
    AlertDialog(
        onDismissRequest = { dialogs.showAccount = false },
        title = { Text(stringResource(R.string.account_sync)) },
        text = { AccountDialogContent(state, actions, dialogs, navigation) },
        confirmButton = {
            TextButton(
                onClick = { dialogs.showAccount = false },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun AccountDialogContent(
    state: AppState,
    actions: TimerScreenActions,
    dialogs: TimerDialogState,
    navigation: TimerNavigationState,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.user?.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cloud_sync_value, syncLabel(state)))
        Text(stringResource(R.string.peer_route_value, networkStatusTitle(state.network.status)))
        AccountDialogLinks(actions, dialogs, navigation)
    }
}

@Composable
private fun AccountDialogLinks(
    actions: TimerScreenActions,
    dialogs: TimerDialogState,
    navigation: TimerNavigationState,
) {
    AccountLink(R.string.open_network_routes) {
        dialogs.showAccount = false
        navigation.activeTab = MainTab.Network
    }
    AccountLink(R.string.privacy_policy, onClick = actions.onOpenPrivacy)
    AccountLink(R.string.sign_out) {
        dialogs.showAccount = false
        dialogs.showLogout = true
    }
    AccountLink(R.string.delete_account, destructive = true) {
        dialogs.showAccount = false
        dialogs.showDelete = true
    }
}

@Composable
private fun AccountLink(labelRes: Int, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(
            stringResource(labelRes),
            color = if (destructive) darkModeTextColor(MaterialTheme.colorScheme.error) else androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
}

@Composable
private fun DeleteAccountDialog(actions: TimerScreenActions, dialogs: TimerDialogState) {
    AlertDialog(
        onDismissRequest = { dialogs.showDelete = false },
        title = { Text(stringResource(R.string.permanently_delete_account)) },
        text = { DeleteAccountContent(dialogs) },
        confirmButton = {
            TextButton(
                onClick = {
                    dialogs.showDelete = false
                    actions.onDeleteAccount(dialogs.deleteConfirmation)
                    dialogs.deleteConfirmation = ""
                },
                enabled = dialogs.deleteConfirmation == "DELETE",
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.delete_forever),
                    color = darkModeTextColor(MaterialTheme.colorScheme.error),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { dialogs.showDelete = false },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteAccountContent(dialogs: TimerDialogState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.this_permanently_deletes_profile_sessions_timers_history_tasks))
        Text(stringResource(R.string.type_delete_exactly_to_confirm), fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = dialogs.deleteConfirmation,
            onValueChange = { dialogs.deleteConfirmation = it },
            label = { Text(stringResource(R.string.confirmation)) },
            singleLine = true,
        )
    }
}

@Composable
private fun LogoutDialog(pendingCount: Int, onLogout: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { BrandOrb(42.dp) },
        title = { Text(stringResource(R.string.sign_out_of_pomodorough)) },
        text = { LogoutWarning(pendingCount) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onLogout() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.sign_out),
                    color = darkModeTextColor(MaterialTheme.colorScheme.error),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stay_signed_in))
            }
        },
    )
}

@Composable
private fun LogoutWarning(pendingCount: Int) {
    Text(
        if (pendingCount > 0) {
            pluralStringResource(R.plurals.pending_sign_out_warning, pendingCount, pendingCount)
        } else {
            stringResource(R.string.local_account_data_will_be_removed_your_synced)
        },
    )
}

@Composable
private fun LocalResetDialog(onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.reset_local_account_title)) },
        text = { Text(stringResource(R.string.reset_local_account_message)) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onReset() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.reset_and_sign_out),
                    color = darkModeTextColor(MaterialTheme.colorScheme.error),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AccountSwitchDialog(
    accountSwitch: AccountSwitchState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.different_account_detected)) },
        text = { AccountSwitchContent(accountSwitch) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !accountSwitch.submitting,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.switch_and_remove_local_data),
                    color = darkModeTextColor(MaterialTheme.colorScheme.error),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !accountSwitch.submitting,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.keep_local_data))
            }
        },
    )
}

@Composable
private fun AccountSwitchContent(accountSwitch: AccountSwitchState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(
                R.string.account_switch_warning,
                accountSwitch.localAccount,
                accountSwitch.incomingAccount,
            ),
        )
        accountSwitch.error?.let {
            Text(it, color = darkModeTextColor(MaterialTheme.colorScheme.error), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryResolutionDialog(
    authStatus: AuthStatus,
    resolution: HistoryResolutionState,
    confirmation: MutableState<BootstrapStrategy?>,
    actions: TimerScreenActions,
) {
    val selected = resolution.pendingStrategy ?: confirmation.value
    val confirmingKeepRemote = resolution.corrupted &&
        resolution.recovery == ResolutionRecovery.KeepRemote &&
        selected == BootstrapStrategy.KeepRemote
    AlertDialog(
        onDismissRequest = {
            if (resolution.pendingStrategy == null && !resolution.submitting) confirmation.value = null
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(historyResolutionTitle(resolution, selected, confirmingKeepRemote)) },
        text = { HistoryResolutionContent(resolution, selected, confirmingKeepRemote) },
        confirmButton = {
            HistoryResolutionAction(authStatus, resolution, selected, confirmingKeepRemote, confirmation, actions)
        },
        dismissButton = if (canCancelHistoryChoice(resolution, selected, confirmingKeepRemote)) {
            { HistoryResolutionDismiss(resolution.submitting) { confirmation.value = null } }
        } else {
            null
        },
    )
}

private fun canCancelHistoryChoice(
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy?,
    confirmingKeepRemote: Boolean,
) = selected != null &&
    resolution.pendingStrategy == null &&
    (!resolution.corrupted || confirmingKeepRemote)

@Composable
private fun historyResolutionTitle(
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy?,
    confirmingKeepRemote: Boolean,
): String = when {
    confirmingKeepRemote -> stringResource(R.string.confirm_keep_remote)
    resolution.recovery == ResolutionRecovery.KeepRemote -> stringResource(R.string.local_changes_cannot_be_submitted)
    resolution.corrupted -> stringResource(R.string.saved_history_choice_is_corrupted)
    resolution.submitting -> stringResource(R.string.applying_history_choice)
    resolution.pendingStrategy != null -> stringResource(R.string.retry_history_choice)
    selected != null -> stringResource(R.string.confirm_choice, resolutionLabel(selected))
    else -> stringResource(R.string.choose_synchronized_state)
}

@Composable
private fun HistoryResolutionContent(
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy?,
    confirmingKeepRemote: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(historyResolutionMessage(resolution, selected, confirmingKeepRemote))
        resolution.error?.let {
            Text(it, color = darkModeTextColor(MaterialTheme.colorScheme.error), fontWeight = FontWeight.Bold)
        }
        if (resolution.pendingStrategy != null) {
            Text(stringResource(R.string.retry_sends_the_exact_saved_request_id_and))
        }
    }
}

@Composable
private fun historyResolutionMessage(
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy?,
    confirmingKeepRemote: Boolean,
): String = when {
    confirmingKeepRemote -> resolutionWarning(BootstrapStrategy.KeepRemote)
    resolution.recovery == ResolutionRecovery.KeepRemote ->
        stringResource(R.string.local_queues_exceed_the_safe_request_limit_or)
    resolution.corrupted -> stringResource(R.string.discard_only_the_corrupted_saved_request_then_fetch)
    selected == null -> stringResource(R.string.this_device_and_your_account_both_contain_synchronized)
    else -> resolutionWarning(selected)
}

@Composable
private fun HistoryResolutionAction(
    authStatus: AuthStatus,
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy?,
    confirmingKeepRemote: Boolean,
    confirmation: MutableState<BootstrapStrategy?>,
    actions: TimerScreenActions,
) {
    when {
        resolution.corrupted && !confirmingKeepRemote ->
            CorruptedResolutionAction(authStatus, resolution, confirmation, actions)
        selected == null -> HistoryChoiceButtons { confirmation.value = it }
        else -> SubmitResolutionButton(authStatus, resolution, selected, actions)
    }
}

@Composable
private fun CorruptedResolutionAction(
    authStatus: AuthStatus,
    resolution: HistoryResolutionState,
    confirmation: MutableState<BootstrapStrategy?>,
    actions: TimerScreenActions,
) {
    when (resolution.recovery) {
        ResolutionRecovery.KeepRemote -> TextButton(
            onClick = { confirmation.value = BootstrapStrategy.KeepRemote },
            enabled = !resolution.submitting,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.review_keep_remote), fontWeight = FontWeight.Bold) }
        ResolutionRecovery.Repreview -> RecoveryPreviewButton(authStatus, resolution.submitting, actions)
        null -> Unit
    }
}

@Composable
private fun RecoveryPreviewButton(
    authStatus: AuthStatus,
    submitting: Boolean,
    actions: TimerScreenActions,
) {
    TextButton(
        onClick = {
            if (authStatus == AuthStatus.SignedIn) actions.onRecoverHistoryResolution() else actions.onSignIn()
        },
        enabled = !submitting && (authStatus == AuthStatus.SignedIn || authStatus == AuthStatus.SignedOut),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(recoveryActionLabel(authStatus), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun recoveryActionLabel(authStatus: AuthStatus): String = when (authStatus) {
    AuthStatus.SignedOut -> stringResource(R.string.sign_in_to_re_check)
    AuthStatus.SigningIn -> stringResource(R.string.signing_in)
    AuthStatus.Loading -> stringResource(R.string.checking_account)
    AuthStatus.SignedIn -> stringResource(R.string.discard_request_and_re_check)
}

@Composable
private fun HistoryChoiceButtons(onSelect: (BootstrapStrategy) -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        TextButton(
            onClick = { onSelect(BootstrapStrategy.ReplaceRemote) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.keep_local))
        }
        TextButton(
            onClick = { onSelect(BootstrapStrategy.KeepRemote) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.keep_remote))
        }
        TextButton(
            onClick = { onSelect(BootstrapStrategy.Merge) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.keep_both))
        }
    }
}

@Composable
private fun SubmitResolutionButton(
    authStatus: AuthStatus,
    resolution: HistoryResolutionState,
    selected: BootstrapStrategy,
    actions: TimerScreenActions,
) {
    TextButton(
        onClick = {
            if (authStatus == AuthStatus.SignedIn) actions.onResolveHistory(selected) else actions.onSignIn()
        },
        enabled = !resolution.submitting &&
            (authStatus == AuthStatus.SignedIn || authStatus == AuthStatus.SignedOut),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            submitResolutionLabel(authStatus, resolution.pendingStrategy, selected),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun submitResolutionLabel(
    authStatus: AuthStatus,
    pendingStrategy: BootstrapStrategy?,
    selected: BootstrapStrategy,
): String = when {
    authStatus == AuthStatus.SignedOut -> stringResource(R.string.sign_in_to_retry)
    authStatus == AuthStatus.SigningIn -> stringResource(R.string.signing_in_to_retry)
    authStatus == AuthStatus.Loading -> stringResource(R.string.checking_account)
    pendingStrategy != null -> stringResource(R.string.retry)
    else -> stringResource(R.string.confirm_choice, resolutionLabel(selected))
}

@Composable
private fun HistoryResolutionDismiss(submitting: Boolean, onDismiss: () -> Unit) {
    val description = stringResource(R.string.cancel_history_choice)
    TextButton(
        onClick = onDismiss,
        enabled = !submitting,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) { Text(stringResource(R.string.cancel)) }
}
