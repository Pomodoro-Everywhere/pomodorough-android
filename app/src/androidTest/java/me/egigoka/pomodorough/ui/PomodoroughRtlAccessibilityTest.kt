package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AccountSwitchState
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = context.resources.configuration.locales[0]
        val expectedLocale = requireNotNull(
            InstrumentationRegistry.getArguments().getString("expectedLocale"),
        ) { "Runner must provide expectedLocale" }
        assertEquals(expectedLocale, locale.toLanguageTag())
        val expectedConfigurationDirection: Int
        val expectedComposeDirection: LayoutDirection
        if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
            expectedConfigurationDirection = Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
            expectedComposeDirection = LayoutDirection.Rtl
        } else {
            expectedConfigurationDirection = Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
            expectedComposeDirection = LayoutDirection.Ltr
        }
        assertEquals(
            expectedConfigurationDirection,
            context.resources.configuration.screenLayout and
                Configuration.SCREENLAYOUT_LAYOUTDIR_MASK,
        )

        var composedDirection: LayoutDirection? = null
        setScreen(
            state = AppState(ready = true, authStatus = AuthStatus.SignedIn),
            captureDirection = { composedDirection = it },
        )
        composeRule.runOnIdle { assertEquals(expectedComposeDirection, composedDirection) }

        val routes = listOf(
            R.string.timer to R.string.current_service,
            R.string.tasks to R.string.task_board,
            R.string.pattern to R.string.service_pattern,
            R.string.arrivals to R.string.recent_arrivals,
        )
        routes.forEach { (navigationLabel, routeHeading) ->
            val label = context.getString(navigationLabel)
            val navigationNode = composeRule.onNode(hasText(label) or hasContentDescription(label))
            navigationNode
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .performClick()
            navigationNode.assertIsSelected()
            try {
                composeRule.onNodeWithText(context.getString(routeHeading)).assertIsDisplayed()
            } catch (error: AssertionError) {
                throw AssertionError("Route heading is clipped after selecting $label", error)
            }
        }

        val account = context.getString(R.string.account)
        composeRule.onNode(hasText(account) or hasContentDescription(account)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.open_network_routes))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.choose_where_time_travels)).assertExists()
    }

    @Test
    fun destructiveAccountDialogsRemainOperableAtCurrentFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var logoutCalls = 0
        var deletedConfirmation: String? = null
        setScreen(
            state = AppState(ready = true, authStatus = AuthStatus.SignedIn),
            onLogout = { logoutCalls += 1 },
            onDeleteAccount = { deletedConfirmation = it },
        )

        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.sign_out))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.sign_out_of_pomodorough)).assertExists()
        composeRule.onNode(hasText(context.getString(R.string.sign_out)) and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.stay_signed_in))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(0, logoutCalls) }

        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.delete_account))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.permanently_delete_account)).assertExists()
        composeRule.onNode(hasText(context.getString(R.string.delete_forever)) and hasClickAction())
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.confirmation))
            .performClick()
            .assertIsFocused()
            .performTextInput("DELETE")
        composeRule.onNode(hasText(context.getString(R.string.delete_forever)) and hasClickAction())
            .performClick()
        composeRule.runOnIdle { assertEquals("DELETE", deletedConfirmation) }
    }

    @Test
    fun everyPeerRouteStateAndLeaveDialogRemainOperableAtCurrentFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var leaveCalls = 0
        var state by mutableStateOf(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                network = IrohNetworkState(
                    mode = ReplicationMode.IROH,
                    status = IrohConnectionStatus.STOPPED,
                    roomId = "room-accessibility",
                    roomName = "Shared focus room",
                    invite = "pomodorough1:accessibility-reflow-witness",
                ),
            ),
        )
        composeRule.setContent {
            ScreenUnderTest(state, {}, {}, onLeaveIrohRoom = { leaveCalls += 1 })
        }
        openAccount(context)
        composeRule.onNodeWithText(context.getString(R.string.open_network_routes))
            .performScrollTo()
            .performClick()

        val labels = listOf(
            IrohConnectionStatus.STOPPED to (R.string.route_stopped to R.string.no_peer_endpoint_is_running),
            IrohConnectionStatus.STARTING to (R.string.opening_route to R.string.binding_encrypted_iroh_transport),
            IrohConnectionStatus.LISTENING to (R.string.ready_for_peers to R.string.foreground_endpoint_is_listening_on_pomodorough_sync_v1),
            IrohConnectionStatus.SYNCING to (R.string.exchanging_changes to R.string.pulling_bounded_inventory_and_immutable_operations),
            IrohConnectionStatus.WAITING_FOR_PEERS to (R.string.waiting_for_peers to R.string.no_peer_is_online_local_changes_remain_durable),
            IrohConnectionStatus.CONFLICT to (R.string.repair_required to R.string.two_payloads_claim_one_immutable_operation_id_replication),
            IrohConnectionStatus.UNAVAILABLE to (R.string.route_unavailable to R.string.use_on_device_or_cloud_mode_while_this),
        )
        labels.forEach { (status, resources) ->
            composeRule.runOnIdle { state = state.copy(network = state.network.copy(status = status)) }
            assertTrue(
                composeRule.onAllNodesWithText(context.getString(resources.first))
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
            composeRule.onNodeWithText(context.getString(resources.second)).assertExists()
        }

        listOf(
            R.string.sync_now,
            R.string.leave_room,
            R.string.copy,
            R.string.share,
            R.string.refresh,
        ).forEach { actionLabel ->
            composeRule.onNode(hasText(context.getString(actionLabel)) and hasClickAction())
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }

        composeRule.onNode(hasText(context.getString(R.string.leave_room)) and hasClickAction())
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.leave_iroh_room)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stay_in_room))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(0, leaveCalls) }

        composeRule.onNode(hasText(context.getString(R.string.leave_room)) and hasClickAction())
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.leave_and_restore))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, leaveCalls) }
    }

    @Test
    fun accountSwitchAndLocalResetDialogsRequireExplicitAccessibleActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var confirmSwitchCalls = 0
        var cancelSwitchCalls = 0
        var resetCalls = 0
        var state by mutableStateOf(
            AppState(
                ready = true,
                authStatus = AuthStatus.SignedIn,
                accountSwitch = AccountSwitchState(
                    localAccount = "old@example.com",
                    incomingAccount = "new@example.com",
                ),
            ),
        )
        composeRule.setContent {
            ScreenUnderTest(
                state = state,
                onLogout = {},
                onDeleteAccount = {},
                onResetLocalAccount = { resetCalls += 1 },
                onConfirmAccountSwitch = {
                    confirmSwitchCalls += 1
                    state = state.copy(accountSwitch = null)
                },
                onCancelAccountSwitch = {
                    cancelSwitchCalls += 1
                    state = state.copy(accountSwitch = null)
                },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.different_account_detected)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.switch_and_remove_local_data))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText(context.getString(R.string.keep_local_data))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmSwitchCalls)
            assertEquals(1, cancelSwitchCalls)
            state = state.copy(
                authStatus = AuthStatus.SignedOut,
                localAccountResetRequired = true,
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.reset_local_account))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.reset_local_account_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.cancel))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(0, resetCalls) }
        composeRule.onNodeWithText(context.getString(R.string.reset_local_account))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.reset_and_sign_out))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, resetCalls) }
    }

    private fun openAccount(context: android.content.Context) {
        val account = context.getString(R.string.account)
        composeRule.onNode(hasText(account) or hasContentDescription(account)).performClick()
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
    ) {
        PomodoroughTheme {
            PomodoroughScreen(
                state = state,
                onSignIn = {},
                onLogout = onLogout,
                onResetLocalAccount = onResetLocalAccount,
                onRefresh = {},
                onToggleTimer = {},
                onFinishTimer = {},
                onCancelTimer = {},
                onClearTimer = {},
                onStopSound = {},
                onSelectPhase = {},
                onChangeDuration = { _, _ -> },
                onSetAutoStart = {},
                onSelectTask = {},
                onAddTask = { _, result -> result(true) },
                onDeleteTask = {},
                onResolveHistory = { _: BootstrapStrategy -> },
                onRecoverHistoryResolution = {},
                onConfirmAccountSwitch = onConfirmAccountSwitch,
                onCancelAccountSwitch = onCancelAccountSwitch,
                onDismissConflict = {},
                onDismissNotice = {},
                onSetReplicationMode = {},
                onCreateIrohRoom = {},
                onJoinIrohRoom = {},
                onLeaveIrohRoom = onLeaveIrohRoom,
                onRefreshIrohInvite = {},
                onSyncIrohNow = {},
                onCopyIrohInvite = {},
                onShareIrohInvite = {},
                onDeleteAccount = onDeleteAccount,
            )
        }
    }
}
