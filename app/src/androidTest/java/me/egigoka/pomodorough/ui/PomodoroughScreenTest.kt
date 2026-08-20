package me.egigoka.pomodorough.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AccountSwitchState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryResolutionState
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.integration.testHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PomodoroughScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resolutionConfirmationsWarnAndCancelHasNoSideEffect() {
        var resolved: BootstrapStrategy? = null
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                syncStatus = SyncStatus.Conflict,
                historyResolution = HistoryResolutionState(2, 3),
            ),
            onResolve = { resolved = it },
        )

        composeRule.onNodeWithText("Keep Local").performClick()
        composeRule.onNodeWithText(
            "Account timer, history, tasks, settings, and queued changes will be replaced by this device's data.",
        ).assertExists()
        composeRule.onNodeWithContentDescription("Cancel history choice").performClick()

        assertNull(resolved)
        composeRule.onNodeWithText("Choose synchronized state").assertExists()

        composeRule.onNodeWithText("Keep Both").performClick()
        composeRule.onNodeWithText(
            "Queued local changes will be merged into account data. Conflicts or rejected changes are possible.",
        ).assertExists()
        composeRule.onNode(hasText("Confirm Keep Both") and hasClickAction()).performClick()

        assertEquals(BootstrapStrategy.Merge, resolved)
    }

    @Test
    fun signedOutStateStillShowsLocalTimerHistoryAndSignIn() {
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedOut,
                history = listOf(testHistory("local-history", TimerPhase.Focus).copy(pending = true)),
                syncStatus = SyncStatus.Queued,
                pendingCount = 2,
                deviceId = "device-local",
            ),
        )

        composeRule.onNodeWithText("Sign in").assertExists()
        composeRule.onNodeWithText("Local only").assertExists()
        composeRule.onNodeWithText("Local clock · sign in to sync").assertDoesNotExist()
        composeRule.onNode(hasText("Arrivals") or hasContentDescription("Arrivals")).performClick()
        composeRule.onNodeWithText("Recent arrivals").assertExists()
        composeRule.onNodeWithText("Focus · queued").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            "Focus, completed",
            substring = true,
        ).assertExists()
    }

    @Test
    fun pullToRefreshIsUnavailableWhileSignedOut() {
        var refreshCalls = 0
        setScreen(
            AppState(ready = true, authStatus = AuthStatus.SignedOut),
            onRefresh = { refreshCalls += 1 },
        )

        composeRule.onRoot().performTouchInput { swipeDown() }
        assertEquals(0, refreshCalls)
    }

    @Test
    fun pullToRefreshIsAvailableWhileSignedIn() {
        var refreshCalls = 0
        setScreen(
            AppState(ready = true, authStatus = AuthStatus.SignedIn),
            onRefresh = { refreshCalls += 1 },
        )
        composeRule.onRoot().performTouchInput { swipeDown() }

        composeRule.waitUntil(timeoutMillis = 2_000) { refreshCalls == 1 }
    }

    @Test
    fun loadingDisablesTimerAndDurationMutations() {
        setScreen(AppState(ready = true, authStatus = AuthStatus.Loading))

        composeRule.onNodeWithText("Start focus").assertIsNotEnabled()
        composeRule.onNode(hasText("Pattern") or hasContentDescription("Pattern")).performClick()
        composeRule.onNodeWithContentDescription("Increase Focus duration").assertIsNotEnabled()
    }

    @Test
    fun completedTimerUsesStopSoundLabelAndClearAction() {
        var clearCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                timer = CanonicalTimer(
                    id = "timer-1",
                    phase = TimerPhase.Focus,
                    status = TimerStatus.Completed,
                    plannedDurationMs = 25 * 60_000L,
                    elapsedAtAnchorMs = 25 * 60_000L,
                    anchorAt = "2026-08-04T09:00:00Z",
                ),
            ),
            onClearTimer = { clearCalls += 1 },
        )

        composeRule.onNodeWithText("Stop sound").performClick()

        assertEquals(1, clearCalls)
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun taskRowHasOneSpokenSummaryAndSeparateDeleteAction() {
        val task = FocusTask(id = "task-1", title = "Accessibility audit")
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                tasks = listOf(task),
                knownTasks = listOf(task),
                taskSummaries = listOf(
                    TaskDailySummary(
                        task = task,
                        finishedPomodoros = 2,
                        timeSpentMs = 25 * 60_000L,
                    ),
                ),
            ),
        )

        composeRule.onNode(hasText("Tasks") or hasContentDescription("Tasks")).performClick()

        composeRule.onNodeWithContentDescription(
            "Accessibility audit, 2 finished pomodoros today, 25 min spent",
        ).assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun noticeIsOnePoliteDismissibleElement() {
        var dismissCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedOut,
                notice = "Network unavailable",
            ),
            onDismissNotice = { dismissCalls += 1 },
        )

        composeRule.onNodeWithContentDescription("Heads up. Network unavailable")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, dismissCalls)
        composeRule.onNodeWithText("Heads up").assertDoesNotExist()
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test
    fun readyScreenPassesPlatformAccessibilityChecks() {
        val task = FocusTask(id = "task-1", title = "Accessibility audit")
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                tasks = listOf(task),
                knownTasks = listOf(task),
                taskSummaries = listOf(TaskDailySummary(task, 2, 25 * 60_000L)),
            ),
        )

        composeRule.enableAccessibilityChecks()
        try {
            composeRule.onRoot().tryPerformAccessibilityChecks()
        } finally {
            composeRule.disableAccessibilityChecks()
        }
    }

    @Test
    fun primaryControlsMeetMinimumTouchTargetSize() {
        val task = FocusTask(id = "task-1", title = "Accessibility audit")
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                tasks = listOf(task),
                knownTasks = listOf(task),
                taskSummaries = listOf(TaskDailySummary(task, 0, 0)),
            ),
        )

        composeRule.onNode(hasText("Start focus") and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNode(hasText("FOCUS TASK") and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)

        composeRule.onNode(hasText("Pattern") or hasContentDescription("Pattern")).performClick()
        composeRule.onNodeWithContentDescription("Decrease Focus duration")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Increase Focus duration")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)

        composeRule.onNode(hasText("Tasks") or hasContentDescription("Tasks")).performClick()
        composeRule.onNodeWithText("Delete")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun navigationDestinationsHaveOneSemanticTargetEach() {
        setScreen(AppState(ready = true, authStatus = AuthStatus.SignedIn))

        listOf("Timer", "Tasks", "Pattern", "Arrivals", "Network").forEach { label ->
            composeRule.onAllNodes(hasText(label) or hasContentDescription(label))
                .assertCountEquals(1)
        }
    }

    @Test
    fun networkNavigationShowsModesPrivacyAndJoinAction() {
        var selectedMode: ReplicationMode? = null
        var joined: String? = null
        composeRule.setContent {
            PomodoroughTheme {
                PomodoroughScreen(
                    state = AppState(
                        ready = true,
                        authStatus = AuthStatus.SignedOut,
                        network = IrohNetworkState(mode = ReplicationMode.OFFLINE),
                    ),
                    onSignIn = {},
                    onLogout = {},
                    onRefresh = {},
                    onToggleTimer = {},
                    onFinishTimer = {},
                    onCancelTimer = {},
                    onClearTimer = {},
                    onSelectPhase = {},
                    onChangeDuration = { _, _ -> },
                    onSetAutoStart = {},
                    onSelectTask = {},
                    onAddTask = {},
                    onDeleteTask = {},
                    onResolveHistory = {},
                    onRecoverHistoryResolution = {},
                    onConfirmAccountSwitch = {},
                    onCancelAccountSwitch = {},
                    onDismissConflict = {},
                    onDismissNotice = {},
                    onSetReplicationMode = { selectedMode = it },
                    onCreateIrohRoom = {},
                    onJoinIrohRoom = { joined = it },
                    onLeaveIrohRoom = {},
                    onRefreshIrohInvite = {},
                    onSyncIrohNow = {},
                    onCopyIrohInvite = {},
                    onShareIrohInvite = {},
                )
            }
        }

        composeRule.onNode(hasText("Network") or hasContentDescription("Network")).performClick()
        composeRule.onNodeWithText("Iroh room").performClick()
        assertEquals(ReplicationMode.IROH, selectedMode)
        composeRule.onNodeWithText("Room invites grant full read and write access.", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Room invite").performClick()
        composeRule.onNodeWithText("JOIN ROOM").assertIsNotEnabled()
        assertNull(joined)
    }

    @Test
    fun signingInDisablesMutationsAndSavedChoiceSignInButton() {
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SigningIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 0,
                    pendingStrategy = BootstrapStrategy.ReplaceRemote,
                    requestId = "request-1",
                ),
            ),
        )

        composeRule.onNodeWithText("Start focus").assertIsNotEnabled()
        composeRule.onNode(hasText("Signing in to retry...") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun savedChoiceSignInActionIsEnabledOnlyWhenSignedOut() {
        var signInCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedOut,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 0,
                    pendingStrategy = BootstrapStrategy.ReplaceRemote,
                    requestId = "request-1",
                ),
            ),
            onSignIn = { signInCalls += 1 },
        )

        composeRule.onNodeWithText("Sign in to retry").performClick()

        assertEquals(1, signInCalls)
    }

    @Test
    fun corruptedResolutionOffersMetadataOnlyRepreview() {
        var recoverCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 1,
                    corrupted = true,
                    recovery = ResolutionRecovery.Repreview,
                ),
            ),
            onRecover = { recoverCalls += 1 },
        )

        composeRule.onNodeWithText("Discard request and re-check").performClick()

        assertEquals(1, recoverCalls)
    }

    @Test
    fun oversizedResolutionRequiresKeepRemoteConfirmation() {
        var resolved: BootstrapStrategy? = null
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 1,
                    remoteHistoryCount = 1,
                    corrupted = true,
                    recovery = ResolutionRecovery.KeepRemote,
                ),
            ),
            onResolve = { resolved = it },
        )

        composeRule.onNodeWithText("Review Keep Remote").performClick()
        composeRule.onNodeWithText(
            "Local history and unsynced operations will be removed, then remote account history will be installed. This cannot be undone.",
        ).assertExists()
        composeRule.onNode(hasText("Confirm Keep Remote") and hasClickAction()).performClick()

        assertEquals(BootstrapStrategy.KeepRemote, resolved)
    }

    @Test
    fun differentAccountDialogRequiresExplicitSafeChoice() {
        var confirmCalls = 0
        var cancelCalls = 0
        setScreen(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                accountSwitch = AccountSwitchState(
                    localAccount = "old@example.com",
                    incomingAccount = "new@example.com",
                ),
            ),
            onConfirmAccountSwitch = { confirmCalls += 1 },
            onCancelAccountSwitch = { cancelCalls += 1 },
        )

        composeRule.onNodeWithText("Different account detected").assertExists()
        composeRule.onNodeWithText(
            "Local data belongs to old@example.com, but new@example.com is signed in. Switching accounts permanently removes this device's timer, history, tasks, and unsynced operations.",
        ).assertExists()
        composeRule.onNodeWithText("Keep local data").performClick()
        composeRule.onNodeWithText("Switch and remove local data").performClick()

        assertEquals(1, cancelCalls)
        assertEquals(1, confirmCalls)
    }

    private fun setScreen(
        state: AppState,
        onSignIn: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onClearTimer: () -> Unit = {},
        onResolve: (BootstrapStrategy) -> Unit = {},
        onRecover: () -> Unit = {},
        onConfirmAccountSwitch: () -> Unit = {},
        onCancelAccountSwitch: () -> Unit = {},
        onDismissNotice: () -> Unit = {},
    ) {
        composeRule.setContent {
            PomodoroughTheme {
                PomodoroughScreen(
                    state = state,
                    onSignIn = onSignIn,
                    onLogout = {},
                    onRefresh = onRefresh,
                    onToggleTimer = {},
                    onFinishTimer = {},
                    onCancelTimer = {},
                    onClearTimer = onClearTimer,
                    onSelectPhase = {},
                    onChangeDuration = { _, _ -> },
                    onSetAutoStart = {},
                    onSelectTask = {},
                    onAddTask = {},
                    onDeleteTask = {},
                    onResolveHistory = onResolve,
                    onRecoverHistoryResolution = onRecover,
                    onConfirmAccountSwitch = onConfirmAccountSwitch,
                    onCancelAccountSwitch = onCancelAccountSwitch,
                    onDismissConflict = {},
                    onDismissNotice = onDismissNotice,
                    onSetReplicationMode = {},
                    onCreateIrohRoom = {},
                    onJoinIrohRoom = {},
                    onLeaveIrohRoom = {},
                    onRefreshIrohInvite = {},
                    onSyncIrohNow = {},
                    onCopyIrohInvite = {},
                    onShareIrohInvite = {},
                )
            }
        }
    }
}
