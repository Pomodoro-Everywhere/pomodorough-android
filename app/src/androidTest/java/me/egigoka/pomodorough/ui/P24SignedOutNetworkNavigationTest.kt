package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class P24SignedOutNetworkNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(
        AppState(
            ready = true,
            authStatus = AuthStatus.SignedOut,
            network = IrohNetworkState(mode = ReplicationMode.OFFLINE),
        ),
    )
    private val createdRooms = mutableListOf<String>()
    private val joinedInvites = mutableListOf<String>()
    private val accountCalls = mutableListOf<String>()
    private var signInCalls = 0

    @Test
    fun signedOutCreatesRoomAtNormalFont() {
        assertSignedOutCreateRoom(1f)
    }

    @Test
    fun signedOutCreatesRoomAtDoubleFont() {
        assertSignedOutCreateRoom(2f)
    }

    @Test
    fun signedOutJoinsRoomAtNormalFont() {
        assertSignedOutJoinRoom(1f)
    }

    @Test
    fun signedOutJoinsRoomAtDoubleFont() {
        assertSignedOutJoinRoom(2f)
    }

    @Test
    fun networkPreservesTabsAndAccountRestrictionsAtNormalFont() {
        assertTabsAndAccountRestrictions(1f)
    }

    @Test
    fun networkPreservesTabsAndAccountRestrictionsAtDoubleFont() {
        assertTabsAndAccountRestrictions(2f)
    }

    private fun assertSignedOutCreateRoom(fontScale: Float) {
        openSignedOutNetwork(fontScale)
        val roomName = "Independent focus room"
        composeRule.onNodeWithContentDescription(label(R.string.room_name_optional))
            .performScrollTo().assertIsDisplayed().performTextInput(roomName)
        action(R.string.create_iroh_room).performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        assertSignedOutAccountRestrictions()
        composeRule.runOnIdle {
            assertEquals(listOf(roomName), createdRooms)
            assertEquals(emptyList<String>(), joinedInvites)
            assertEquals(emptyList<String>(), accountCalls)
            assertEquals(0, signInCalls)
        }
    }

    private fun assertSignedOutJoinRoom(fontScale: Float) {
        openSignedOutNetwork(fontScale)
        action(R.string.join_room).performScrollTo().assertIsNotEnabled().performClick()
        val inviteField = composeRule.onNodeWithContentDescription(label(R.string.room_invite))
        inviteField.performScrollTo().assertIsDisplayed().performTextInput("   ")
        action(R.string.join_room).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), joinedInvites) }
        val invite = "pomodorough1:p24-signed-out-callback-witness"
        inviteField.performScrollTo().performTextReplacement(invite)
        action(R.string.join_room).performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        assertSignedOutAccountRestrictions()
        composeRule.runOnIdle {
            assertEquals(listOf(invite), joinedInvites)
            assertEquals(emptyList<String>(), createdRooms)
            assertEquals(emptyList<String>(), accountCalls)
            assertEquals(0, signInCalls)
        }
    }

    private fun assertTabsAndAccountRestrictions(fontScale: Float) {
        openSignedOutNetwork(fontScale)
        composeRule.runOnIdle { state = state.copy(pendingCount = 2) }
        navigation(MainTab.Network).assertIsSelected()
        val joinBounds = action(R.string.join_room).performScrollTo()
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        listOf(MainTab.Timer, MainTab.Tasks, MainTab.Pattern, MainTab.Arrivals).forEach { tab ->
            navigation(tab).performClick().assertIsSelected()
            navigation(MainTab.Network).assertIsNotSelected().performClick().assertIsSelected()
            navigation(tab).assertIsNotSelected()
            assertEquals(joinBounds, action(R.string.join_room).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        assertSignedOutAccountRestrictions()
        composeRule.runOnIdle { state = state.copy(authStatus = AuthStatus.SignedIn) }
        action(R.string.account).performClick()
        action(R.string.open_network_routes).performScrollTo().performClick()
        navigation(MainTab.Network).assertIsSelected()
        action(R.string.account).performClick()
        action(R.string.sign_out).performScrollTo().assertIsEnabled()
        action(R.string.delete_account).performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { state = state.copy(authStatus = AuthStatus.SignedOut) }
        navigation(MainTab.Network).assertIsSelected()
        assertSignedOutAccountRestrictions()
        action(R.string.sign_in).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, signInCalls)
            assertEquals(emptyList<String>(), accountCalls)
            assertEquals(emptyList<String>(), createdRooms)
            assertEquals(emptyList<String>(), joinedInvites)
        }
    }

    private fun openSignedOutNetwork(fontScale: Float) {
        setScreen(fontScale)
        navigation(MainTab.Timer).assertIsSelected()
        MainTab.entries.forEach { tab ->
            val visibleLabel = hasText(label(tab.labelRes))
            navigation(tab).assert(if (fontScale < 1.3f) visibleLabel else visibleLabel.not())
        }
        assertSignedOutAccountRestrictions()
        navigation(MainTab.Network).assertIsNotSelected().performClick().assertIsSelected()
        navigation(MainTab.Timer).assertIsNotSelected()
        composeRule.onNode(hasText(label(R.string.choose_where_time_travels))).assertIsDisplayed()
    }

    private fun assertSignedOutAccountRestrictions() {
        listOf(
            R.string.account, R.string.account_sync, R.string.sign_out,
            R.string.delete_account, R.string.reset_local_account, R.string.open_network_routes,
        ).forEach { labelRes ->
            composeRule.onNode(hasText(label(labelRes)) or hasContentDescription(label(labelRes)))
                .assertDoesNotExist()
        }
    }

    private fun navigation(tab: MainTab): SemanticsNodeInteraction {
        val accessibleName = hasText(label(tab.labelRes)) or hasContentDescription(label(tab.labelRes))
        val matcher = accessibleName and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onAllNodes(matcher).assertCountEquals(1)
        return composeRule.onNode(matcher).assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    private fun action(labelRes: Int): SemanticsNodeInteraction = composeRule.onNode(
        (hasText(label(labelRes)) or hasContentDescription(label(labelRes))) and hasClickAction(),
    )

    private fun label(labelRes: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(labelRes)

    private fun setScreen(fontScale: Float) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density) {
                PomodoroughTheme {
                    PomodoroughScreen(
                        state = state,
                        onSignIn = { signInCalls += 1 },
                        onLogout = { accountCalls += "logout" },
                        onResetLocalAccount = { accountCalls += "reset" },
                        onRefresh = {}, onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {},
                        onClearTimer = {}, onSelectPhase = {}, onChangeDuration = { _, _ -> },
                        onSetAutoStart = {}, onSelectTask = {},
                        onAddTask = { _, result -> result(true) }, onDeleteTask = {},
                        onResolveHistory = {}, onRecoverHistoryResolution = {},
                        onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
                        onDismissConflict = {}, onDismissNotice = {}, onSetReplicationMode = {},
                        onCreateIrohRoom = { createdRooms += it }, onJoinIrohRoom = { joinedInvites += it },
                        onLeaveIrohRoom = {}, onRefreshIrohInvite = {}, onSyncIrohNow = {},
                        onCopyIrohInvite = {}, onShareIrohInvite = {},
                        onDeleteAccount = { accountCalls += "delete" },
                        onOpenPrivacy = { accountCalls += "privacy" },
                    )
                }
            }
        }
    }
}
