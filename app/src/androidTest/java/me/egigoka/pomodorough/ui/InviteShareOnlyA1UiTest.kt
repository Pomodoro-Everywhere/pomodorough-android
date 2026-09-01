package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InviteShareOnlyA1UiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rtlInviteActionsExposeShareAndRefreshWithoutCopy() {
        val invite = "pomodorough1.exact-share-invite"
        var sharedInvite: String? = null
        var refreshCalls = 0
        setInviteSection(invite, { sharedInvite = it }, { refreshCalls += 1 })
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onAllNodesWithText("Copy").assertCountEquals(0)
        action(context.getString(R.string.share)).performClick()
        composeRule.runOnIdle { assertEquals(invite, sharedInvite) }
        action(context.getString(R.string.refresh)).performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCalls) }
    }

    private fun setInviteSection(invite: String, onShare: (String) -> Unit, onRefresh: () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PomodoroughTheme {
                    LazyColumn {
                        item {
                            NetworkSection(
                                inviteState(invite),
                                enabled = true,
                                actions = networkActions(onShare, onRefresh),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun action(label: String) = composeRule.onNode(hasText(label) and hasClickAction())
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)

    private fun inviteState(invite: String) = AppState(
        ready = true,
        authStatus = AuthStatus.SignedIn,
        network = IrohNetworkState(
            mode = ReplicationMode.IROH,
            roomId = "room-a1",
            invite = invite,
        ),
    )

    private fun networkActions(onShare: (String) -> Unit, onRefresh: () -> Unit) = NetworkActions(
        onSetMode = {},
        onCreateRoom = {},
        onJoinRoom = {},
        onLeaveRoom = {},
        onRefreshInvite = onRefresh,
        onSyncNow = {},
        onShareInvite = onShare,
    )
}
