package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohIdentityRecoveryKind
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohIdentityRecoveryA4UiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun endpointRepairRequiresExplicitConfirmationAndCancelPreservesQuarantine() {
        var confirmationCount = 0
        setRecoverySection(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED) { confirmationCount += 1 }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val review = context.getString(R.string.review_iroh_endpoint_repair)
        val description = context.getString(R.string.repair_iroh_endpoint_identity_description)

        scrollingAction(review).performClick()
        composeRule.onNodeWithText(description).assertIsDisplayed()
        dialogAction(context.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(description).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, confirmationCount) }

        scrollingAction(review).performClick()
        dialogAction(context.getString(R.string.repair_endpoint)).performClick()
        composeRule.runOnIdle { assertEquals(1, confirmationCount) }
    }

    @Test
    fun keyResetExplainsDestructiveScopeAndFencesNetworkActions() {
        var confirmationCount = 0
        setRecoverySection(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) { confirmationCount += 1 }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithText(context.getString(R.string.on_device)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.iroh_room)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.cloud)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.sync_now)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.leave_room)).assertIsNotEnabled()

        scrollingAction(context.getString(R.string.review_iroh_identity_reset)).performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.reset_iroh_identity_and_rooms_description),
        ).assertIsDisplayed()
        dialogAction(context.getString(R.string.reset_iroh_identity)).performClick()
        composeRule.runOnIdle { assertEquals(1, confirmationCount) }
    }

    private fun setRecoverySection(kind: IrohIdentityRecoveryKind, onConfirm: () -> Unit) {
        composeRule.setContent {
            PomodoroughTheme {
                LazyColumn {
                    item {
                        NetworkSection(
                            recoveryState(kind),
                            enabled = true,
                            actions = recoveryActions(onConfirm),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    private fun scrollingAction(label: String) = composeRule.onNode(hasText(label) and hasClickAction())
        .performScrollTo()
        .assertAccessibleAction()

    private fun dialogAction(label: String) = composeRule.onNode(hasText(label) and hasClickAction())
        .assertAccessibleAction()

    private fun SemanticsNodeInteraction.assertAccessibleAction() = this
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)

    private fun recoveryState(kind: IrohIdentityRecoveryKind) = AppState(
        ready = true,
        authStatus = AuthStatus.SignedIn,
        network = IrohNetworkState(
            mode = ReplicationMode.IROH,
            status = IrohConnectionStatus.UNAVAILABLE,
            roomId = "room-a4",
            identityRecovery = kind,
        ),
    )

    private fun recoveryActions(onConfirm: () -> Unit) = NetworkActions(
        onSetMode = {},
        onCreateRoom = {},
        onJoinRoom = {},
        onLeaveRoom = {},
        onRefreshInvite = {},
        onSyncNow = {},
        onConfirmIdentityRecovery = onConfirm,
        onShareInvite = {},
    )
}
