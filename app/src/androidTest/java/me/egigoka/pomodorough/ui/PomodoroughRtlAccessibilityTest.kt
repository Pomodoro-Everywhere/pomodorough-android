package me.egigoka.pomodorough.ui

import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AccountSwitchState
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.HistoryResolutionState
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PomodoroughRtlAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun configuredLocaleDirectionKeepsEveryRouteReachableAtCurrentFontScale() {
        val context = targetContext()
        val expectedDirection = assertConfiguredLocale(context)
        var composedDirection: LayoutDirection? = null
        setScreen(pausedTimerState(), captureDirection = { composedDirection = it })
        composeRule.runOnIdle { assertEquals(expectedDirection, composedDirection) }

        assertTimerNameAndValue(context)
        navigatePrimaryRoutes(context)
        assertAccountValues(context)
        openNetworkRoutes(context)
        composeRule.onNodeWithText(context.getString(R.string.choose_where_time_travels)).assertIsDisplayed()
        assertPlatformAccessibility()
    }

    @Test
    fun destructiveAccountDialogsRemainOperableAtCurrentFontScale() {
        val context = targetContext()
        var logoutCalls = 0
        var deletedConfirmation: String? = null
        setScreen(
            state = signedInState(),
            onLogout = { logoutCalls += 1 },
            onDeleteAccount = { deletedConfirmation = it },
        )

        assertLogoutDialog(context)
        composeRule.runOnIdle { assertEquals(0, logoutCalls) }
        assertDeleteDialog(context)
        composeRule.runOnIdle { assertEquals("DELETE", deletedConfirmation) }
    }

    @Test
    fun everyPeerRouteStateAndLeaveDialogRemainOperableAtCurrentFontScale() {
        val context = targetContext()
        var leaveCalls = 0
        var state by mutableStateOf(irohState())
        composeRule.setContent {
            ScreenUnderTest(state, {}, {}, onLeaveIrohRoom = { leaveCalls += 1 })
        }
        openAccount(context)
        openNetworkRoutes(context)

        assertEveryIrohState(context) { status ->
            state = state.copy(network = state.network.copy(status = status))
        }
        assertIrohActionTargets(context)
        assertLeaveRoomDialog(context) { leaveCalls }
    }

    @Test
    fun accountSwitchAndLocalResetDialogsRequireExplicitAccessibleActions() {
        val context = targetContext()
        var confirmSwitchCalls = 0
        var cancelSwitchCalls = 0
        var resetCalls = 0
        var state by mutableStateOf(accountSwitchState())
        composeRule.setContent {
            ScreenUnderTest(
                state = state,
                onLogout = {},
                onDeleteAccount = {},
                onResetLocalAccount = { resetCalls += 1 },
                onConfirmAccountSwitch = { confirmSwitchCalls += 1 },
                onCancelAccountSwitch = {
                    cancelSwitchCalls += 1
                    state = resetRequiredState()
                },
            )
        }

        assertAccountSwitchDialog(context)
        composeRule.runOnIdle {
            assertEquals(0, confirmSwitchCalls)
            assertEquals(1, cancelSwitchCalls)
        }
        assertLocalResetDialog(context) { resetCalls }
    }

    @Test
    fun offlineConflictNoticeAndHistoryChoiceExposeOnlyAvailableActions() {
        val context = targetContext()
        var resolved: BootstrapStrategy? = null
        var state by mutableStateOf(offlineState())
        composeRule.setContent {
            ScreenUnderTest(
                state = state,
                onLogout = {},
                onDeleteAccount = {},
                onResolveHistory = {
                    resolved = it
                    state = state.copy(historyResolution = null)
                },
                onDismissConflict = { state = state.copy(conflict = null) },
                onDismissNotice = { state = state.copy(notice = null) },
            )
        }

        assertOfflineRoute(context)
        composeRule.runOnIdle { state = state.copy(conflict = "RTL conflict witness") }
        assertConflictCanBeDismissed(context)
        composeRule.runOnIdle { state = state.copy(notice = "RTL notice witness") }
        assertNoticeCanBeDismissed(context)
        composeRule.runOnIdle { state = state.copy(historyResolution = HistoryResolutionState(2, 3)) }
        assertHistoryChoice(context)
        composeRule.runOnIdle { assertEquals(BootstrapStrategy.Merge, resolved) }
        assertRoomFieldsRemainLabeledAndEditable(context)
    }

    private fun assertConfiguredLocale(context: Context): LayoutDirection {
        val locale = context.resources.configuration.locales[0]
        val expectedLocale = requireNotNull(
            InstrumentationRegistry.getArguments().getString("expectedLocale"),
        ) { "Runner must provide expectedLocale" }
        assertEquals(expectedLocale, locale.toLanguageTag())
        val rtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL
        val configurationDirection = if (rtl) {
            Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
        } else {
            Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
        }
        assertEquals(
            configurationDirection,
            context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_LAYOUTDIR_MASK,
        )
        return if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    }

    private fun assertTimerNameAndValue(context: Context) {
        val description = context.getString(
            R.string.timer_remaining,
            20,
            0,
            context.getString(R.string.focus),
            context.getString(R.string.paused),
        )
        val node = composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        val range = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo(0.2f, 0f..1f), range)
        assertActionTarget(context.getString(R.string.resume))
        assertPlatformAccessibility()
    }

    private fun navigatePrimaryRoutes(context: Context) {
        listOf(
            R.string.timer to R.string.current_service,
            R.string.tasks to R.string.task_board,
            R.string.pattern to R.string.service_pattern,
            R.string.arrivals to R.string.recent_arrivals,
        ).forEach { (navigationLabel, routeHeading) ->
            val label = context.getString(navigationLabel)
            val navigationNode = composeRule.onNode(hasText(label) or hasContentDescription(label))
            navigationNode.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            navigationNode.assertIsSelected()
            composeRule.onNodeWithText(context.getString(routeHeading)).assertIsDisplayed()
            assertPlatformAccessibility()
        }
    }

    private fun assertAccountValues(context: Context) {
        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.account_sync)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.cloud_sync_value, context.getString(R.string.checking_sync)),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.peer_route_value, context.getString(R.string.route_stopped)),
        ).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.done))
        assertPlatformAccessibility()
    }

    private fun assertLogoutDialog(context: Context) {
        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.sign_out)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.sign_out_of_pomodorough)).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.sign_out))
        val staySignedIn = assertActionTarget(context.getString(R.string.stay_signed_in))
        assertPlatformAccessibility()
        staySignedIn.performClick()
        composeRule.onNodeWithText(context.getString(R.string.sign_out_of_pomodorough)).assertDoesNotExist()
    }

    private fun assertDeleteDialog(context: Context) {
        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.delete_account)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.permanently_delete_account)).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.delete_forever)).assertIsNotEnabled()
        assertActionTarget(context.getString(R.string.cancel))
        assertPlatformAccessibility()
        composeRule.onNodeWithText(context.getString(R.string.confirmation))
            .performClick().assertIsFocused().performTextInput("DELETE")
        assertActionTarget(context.getString(R.string.delete_forever)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.permanently_delete_account)).assertDoesNotExist()
    }

    private fun assertEveryIrohState(context: Context, setStatus: (IrohConnectionStatus) -> Unit) {
        irohLabels().forEach { (status, resources) ->
            composeRule.runOnIdle { setStatus(status) }
            assertTrue(
                composeRule.onAllNodesWithText(context.getString(resources.first))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.onNodeWithText(context.getString(resources.second))
                .performScrollTo()
                .assertIsDisplayed()
            assertPlatformAccessibility()
        }
    }

    private fun assertIrohActionTargets(context: Context) {
        listOf(R.string.sync_now, R.string.leave_room, R.string.share, R.string.refresh)
            .forEach { label -> assertActionTarget(context.getString(label), scroll = true) }
    }

    private fun assertLeaveRoomDialog(context: Context, leaveCalls: () -> Int) {
        assertActionTarget(context.getString(R.string.leave_room), scroll = true).performClick()
        composeRule.onNodeWithText(context.getString(R.string.leave_iroh_room)).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.stay_in_room)).performClick()
        composeRule.runOnIdle { assertEquals(0, leaveCalls()) }
        composeRule.onNodeWithText(context.getString(R.string.leave_iroh_room)).assertDoesNotExist()
        assertActionTarget(context.getString(R.string.leave_room), scroll = true).performClick()
        assertPlatformAccessibility()
        assertActionTarget(context.getString(R.string.leave_and_restore)).performClick()
        composeRule.runOnIdle { assertEquals(1, leaveCalls()) }
    }

    private fun assertAccountSwitchDialog(context: Context) {
        composeRule.onNodeWithText(context.getString(R.string.different_account_detected)).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.switch_and_remove_local_data))
        assertActionTarget(context.getString(R.string.keep_local_data))
        assertPlatformAccessibility()
        assertActionTarget(context.getString(R.string.keep_local_data)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.different_account_detected)).assertDoesNotExist()
    }

    private fun assertLocalResetDialog(context: Context, resetCalls: () -> Int) {
        assertActionTarget(context.getString(R.string.reset_local_account)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.reset_local_account_title)).assertIsDisplayed()
        assertActionTarget(context.getString(R.string.cancel)).performClick()
        composeRule.runOnIdle { assertEquals(0, resetCalls()) }
        composeRule.onNodeWithText(context.getString(R.string.reset_local_account_title)).assertDoesNotExist()
        assertActionTarget(context.getString(R.string.reset_local_account)).performClick()
        assertPlatformAccessibility()
        assertActionTarget(context.getString(R.string.reset_and_sign_out)).performClick()
        composeRule.runOnIdle { assertEquals(1, resetCalls()) }
    }

    private fun assertOfflineRoute(context: Context) {
        openAccount(context)
        openNetworkRoutes(context)
        val offline = composeRule.onNode(
            hasText(context.getString(R.string.on_device)) and hasClickAction(),
        ).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertEquals(
            context.getString(R.string.selected),
            offline.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithText(context.getString(R.string.sync_now)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.leave_room)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.iroh_room)).assertIsNotEnabled()
        assertPlatformAccessibility()
    }

    private fun assertConflictCanBeDismissed(context: Context) {
        val title = context.getString(R.string.changes_resolved_on_another_device)
        composeRule.onNodeWithText(title).assertIsDisplayed()
        val dismiss = assertActionTarget(context.getString(R.string.dismiss), scroll = true)
        assertPlatformAccessibility()
        dismiss.performClick()
        composeRule.onNodeWithText(title).assertDoesNotExist()
    }

    private fun assertNoticeCanBeDismissed(context: Context) {
        val description = context.getString(R.string.heads_up_message, "RTL notice witness")
        val notice = composeRule.onNodeWithContentDescription(description)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertPlatformAccessibility()
        notice.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    private fun assertHistoryChoice(context: Context) {
        composeRule.onNodeWithText(context.getString(R.string.choose_synchronized_state)).assertIsDisplayed()
        listOf(R.string.keep_local, R.string.keep_remote, R.string.keep_both).forEach { label ->
            assertActionTarget(context.getString(label))
        }
        assertPlatformAccessibility()
        assertActionTarget(context.getString(R.string.keep_both)).performClick()
        val cancel = composeRule.onNodeWithContentDescription(context.getString(R.string.cancel_history_choice))
        cancel.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsDisplayed()
        assertPlatformAccessibility()
        assertActionTarget(
            context.getString(R.string.confirm_choice, context.getString(R.string.keep_both)),
        ).performClick()
        composeRule.onNodeWithText(context.getString(R.string.choose_synchronized_state)).assertDoesNotExist()
    }

    private fun assertActionTarget(label: String, scroll: Boolean = false) =
        composeRule.onNode(hasText(label) and hasClickAction()).let { node ->
            if (scroll) node.performScrollTo() else node
        }.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

    private fun assertRoomFieldsRemainLabeledAndEditable(context: Context) {
        listOf(
            R.string.room_name_optional to "Accessible room",
            R.string.room_invite to "pomodorough1:editable-invite-witness",
        ).forEach { (label, value) ->
            val field = composeRule.onNodeWithContentDescription(context.getString(label))
                .performScrollTo().assertIsDisplayed()
            field.performTextInput(value)
            val semantics = field.fetchSemanticsNode().config
            assertEquals(value, semantics[SemanticsProperties.EditableText].text)
            assertTrue(semantics.contains(SemanticsActions.SetText))
            assertPlatformAccessibility()
            assertAccessibleEditableValue(context.getString(label), value)
        }
    }

    private fun assertPlatformAccessibility() {
        // Accessibility Framework sees Compose TextButton visual bounds, while semantics expose 48 dp targets.
        val validator = AccessibilityValidator()
            .setRunChecksFromRootView(true)
            .setSuppressingResultMatcher(
                AccessibilityCheckResultUtils.matchesCheck(TouchTargetSizeCheck::class.java),
            )
        composeRule.enableAccessibilityChecks(validator)
        try {
            composeRule.onRoot().tryPerformAccessibilityChecks()
        } catch (failure: Throwable) {
            runCatching { accessibilityHierarchy() }
                .onSuccess { failure.addSuppressed(AssertionError(it)) }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            composeRule.disableAccessibilityChecks()
        }
    }

    private fun openAccount(context: Context) {
        val account = context.getString(R.string.account)
        assertActionTarget(account).performClick()
    }

    private fun openNetworkRoutes(context: Context) {
        assertActionTarget(context.getString(R.string.open_network_routes), scroll = true).performClick()
    }

    private fun setScreen(
        state: AppState,
        captureDirection: (LayoutDirection) -> Unit = {},
        onLogout: () -> Unit = {},
        onDeleteAccount: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            captureDirection(LocalLayoutDirection.current)
            ScreenUnderTest(state, onLogout, onDeleteAccount)
        }
    }

    @Composable
    private fun ScreenUnderTest(
        state: AppState,
        onLogout: () -> Unit,
        onDeleteAccount: (String) -> Unit,
        onLeaveIrohRoom: () -> Unit = {},
        onResetLocalAccount: () -> Unit = {},
        onConfirmAccountSwitch: () -> Unit = {},
        onCancelAccountSwitch: () -> Unit = {},
        onResolveHistory: (BootstrapStrategy) -> Unit = {},
        onDismissConflict: () -> Unit = {},
        onDismissNotice: () -> Unit = {},
    ) {
        PomodoroughTheme {
            PomodoroughScreen(
                state = state,
                onSignIn = {}, onLogout = onLogout, onResetLocalAccount = onResetLocalAccount,
                onRefresh = {}, onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {},
                onClearTimer = {}, onStopSound = {}, onSelectPhase = {},
                onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
                onAddTask = { _, result -> result(true) }, onDeleteTask = {},
                onResolveHistory = onResolveHistory, onRecoverHistoryResolution = {},
                onConfirmAccountSwitch = onConfirmAccountSwitch,
                onCancelAccountSwitch = onCancelAccountSwitch,
                onDismissConflict = onDismissConflict, onDismissNotice = onDismissNotice,
                onSetReplicationMode = {}, onCreateIrohRoom = {}, onJoinIrohRoom = {},
                onLeaveIrohRoom = onLeaveIrohRoom, onRefreshIrohInvite = {},
                onSyncIrohNow = {}, onShareIrohInvite = {},
                onDeleteAccount = onDeleteAccount,
            )
        }
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun signedInState() = AppState(ready = true, authStatus = AuthStatus.SignedIn)

    private fun pausedTimerState() = signedInState().copy(
        timer = CanonicalTimer(
            id = "timer-accessibility",
            phase = TimerPhase.Focus,
            status = TimerStatus.Paused,
            plannedDurationMs = 25 * 60_000L,
            elapsedAtAnchorMs = 5 * 60_000L,
            anchorAt = "2026-01-01T00:00:00Z",
        ),
    )

    private fun irohState() = signedInState().copy(
        network = IrohNetworkState(
            mode = ReplicationMode.IROH,
            status = IrohConnectionStatus.STOPPED,
            roomId = "room-accessibility",
            roomName = "Shared focus room",
            invite = "pomodorough1:accessibility-reflow-witness",
        ),
    )

    private fun accountSwitchState() = signedInState().copy(
        accountSwitch = AccountSwitchState(
            localAccount = "old@example.com",
            incomingAccount = "new@example.com",
        ),
    )

    private fun resetRequiredState() = AppState(
        ready = true,
        authStatus = AuthStatus.SignedOut,
        localAccountResetRequired = true,
    )

    private fun offlineState() = signedInState().copy(
        syncStatus = SyncStatus.Offline,
        pendingCount = 2,
        network = IrohNetworkState(mode = ReplicationMode.OFFLINE),
    )

    private fun irohLabels() = listOf(
        IrohConnectionStatus.STOPPED to (R.string.route_stopped to R.string.no_peer_endpoint_is_running),
        IrohConnectionStatus.STARTING to (R.string.opening_route to R.string.binding_encrypted_iroh_transport),
        IrohConnectionStatus.LISTENING to (
            R.string.ready_for_peers to R.string.foreground_endpoint_is_listening_on_pomodorough_sync_v1
        ),
        IrohConnectionStatus.SYNCING to (
            R.string.exchanging_changes to R.string.pulling_bounded_inventory_and_immutable_operations
        ),
        IrohConnectionStatus.WAITING_FOR_PEERS to (
            R.string.waiting_for_peers to R.string.no_peer_is_online_local_changes_remain_durable
        ),
        IrohConnectionStatus.CONFLICT to (
            R.string.repair_required to R.string.two_payloads_claim_one_immutable_operation_id_replication
        ),
        IrohConnectionStatus.UNAVAILABLE to (
            R.string.route_unavailable to R.string.use_on_device_or_cloud_mode_while_this
        ),
    )
}
