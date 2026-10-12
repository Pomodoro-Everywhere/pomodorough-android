package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

// R48-A03: short landscape deletion dialog must keep its
// required input and both actions reachable. Run with a real
// landscape display (for example `adb shell wm size 960x640`)
// so the 480x320 dp box selects the rail; a portrait display
// coerces the box and cannot prove the landscape failure.
// No test deletes a real account: the delete lambda only records.
@RunWith(AndroidJUnit4::class)
class R48A03DeletionDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun landscapeInputAcceptsDeleteAndCancelDoesNothing() {
        val calls = mutableListOf<String>()
        showFixed(480, 320, 2f, calls)
        openDeleteDialog()
        val input = deleteInput()
        input.performScrollTo()
        input.assertIsDisplayed()
        input.performClick()
        input.performTextInput("DELETE")
        composeRule.waitForIdle()
        deleteForever().assertIsDisplayed().assertIsEnabled()
        cancel().assertIsDisplayed()
        cancel().performClick()
        composeRule.waitForIdle()
        assertTrue("Cancel must perform no deletion", calls.isEmpty())
        deleteForever().assertDoesNotExist()
    }

    @Test
    fun landscapeWrongCodeKeepsDeleteDisabled() {
        val calls = mutableListOf<String>()
        showFixed(480, 320, 2f, calls)
        openDeleteDialog()
        deleteInput().performScrollTo()
        deleteInput().assertIsDisplayed()
        deleteInput().performTextInput("DEL")
        composeRule.waitForIdle()
        deleteForever().assertIsDisplayed().assertIsNotEnabled()
        deleteInput().performTextInput("ETE")
        composeRule.waitForIdle()
        deleteForever().assertIsDisplayed().assertIsEnabled()
        cancel().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("Cancel must perform no deletion", calls.isEmpty())
    }

    @Test
    fun portraitDeleteDialogControl() {
        val calls = mutableListOf<String>()
        showFixed(320, 480, 1f, calls)
        openDeleteDialog()
        deleteInput().assertIsDisplayed()
        deleteInput().performTextInput("DELETE")
        composeRule.waitForIdle()
        deleteForever().assertIsDisplayed().assertIsEnabled()
        cancel().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("Cancel must perform no deletion", calls.isEmpty())
    }

    @Test
    fun rotationPreservesConfirmation() {
        val width = mutableStateOf(480)
        val height = mutableStateOf(320)
        val calls = mutableListOf<String>()
        showResizable(width, height, 2f, calls)
        openDeleteDialog()
        deleteInput().performScrollTo()
        deleteInput().performTextInput("DELETE")
        composeRule.waitForIdle()
        deleteForever().assertIsDisplayed().assertIsEnabled()
        width.value = 320
        height.value = 480
        composeRule.waitForIdle()
        deleteInput().performScrollTo()
        deleteInput().assertIsDisplayed()
        assertHasText(deleteInput(), "DELETE")
        deleteForever().assertIsDisplayed().assertIsEnabled()
        width.value = 480
        height.value = 320
        composeRule.waitForIdle()
        deleteInput().performScrollTo()
        assertHasText(deleteInput(), "DELETE")
        cancel().performClick()
        composeRule.waitForIdle()
        assertEquals(0, calls.size)
    }

    private fun openDeleteDialog() {
        scrollRailTo(MainTab.entries.indexOf(MainTab.Tasks))
        railTab(MainTab.Tasks).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.account)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.delete_account))
            .performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun deleteInput(): SemanticsNodeInteraction {
        val hint = label(R.string.confirmation)
        return composeRule.onNode(hasSetTextAction() and hasText(hint))
    }

    private fun deleteForever(): SemanticsNodeInteraction {
        return composeRule.onNodeWithText(label(R.string.delete_forever))
    }

    private fun cancel(): SemanticsNodeInteraction {
        return composeRule.onNodeWithText(label(R.string.cancel))
    }

    private fun assertHasText(n: SemanticsNodeInteraction, v: String): SemanticsNodeInteraction {
        val actual = n.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertTrue("Input must preserve $v, actual=$actual", actual == v)
        return n
    }

    private fun railTab(tab: MainTab): SemanticsNodeInteraction {
        val name = hasText(label(tab.labelRes)) or
            hasContentDescription(label(tab.labelRes))
        val matcher = name and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        return composeRule.onNode(matcher)
    }

    private fun scrollRailTo(index: Int) {
        // Literal must match RailListTag in TimerScreenContent.kt;
        // a string keeps this compiling against unfixed code.
        if (composeRule.onAllNodesWithTag("landscapeRailList")
                .fetchSemanticsNodes().size != 1
        ) return
        composeRule.onNodeWithTag("landscapeRailList")
            .performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun showFixed(w: Int, h: Int, s: Float, c: MutableList<String>) {
        composeRule.setContent {
            FixedFrame(w, h, s, c)
        }
        composeRule.waitForIdle()
    }

    private fun showResizable(w: MutableState<Int>, h: MutableState<Int>, s: Float, c: MutableList<String>) {
        composeRule.setContent {
            ResizableFrame(w.value, h.value, s, c)
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun FixedFrame(w: Int, h: Int, s: Float, c: MutableList<String>) {
        FrameContent(w, h, s, c)
    }

    @Composable
    private fun ResizableFrame(w: Int, h: Int, s: Float, c: MutableList<String>) {
        FrameContent(w, h, s, c)
    }

    @Composable
    private fun FrameContent(w: Int, h: Int, s: Float, c: MutableList<String>) {
        val configuration = Configuration(LocalConfiguration.current)
        configuration.fontScale = s
        val density = Density(LocalDensity.current.density, s)
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            CompositionLocalProvider(LocalDensity provides density) {
                PomodoroughTheme {
                    Box(Modifier.size(w.dp, h.dp)) {
                        testScreen(c)
                    }
                }
            }
        }
    }

    @Composable
    private fun testScreen(calls: MutableList<String>) {
        PomodoroughScreen(
            state = AppState(ready = true, authStatus = AuthStatus.SignedIn),
            onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
            onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {},
            onSelectPhase = {}, onChangeDuration = { _, _ -> }, onSetAutoStart = {},
            onSelectTask = {}, onAddTask = { _, result -> result(true) }, onDeleteTask = {},
            onResolveHistory = {}, onRecoverHistoryResolution = {},
            onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
            onDismissConflict = {}, onDismissNotice = {},
            onSetReplicationMode = {}, onCreateIrohRoom = {}, onJoinIrohRoom = {},
            onLeaveIrohRoom = {}, onRefreshIrohInvite = {}, onSyncIrohNow = {},
            onShareIrohInvite = {},
            onDeleteAccount = { calls += it },
        )
    }

    private fun target() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun label(res: Int): String = target().getString(res)
}
