package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

// R48-A02: short landscape rail must keep all five destinations
// scroll-reachable without shrinking text. Run with a real landscape
// display (for example `adb shell wm size 960x640`) so the 480x320 dp
// box is not coerced by a portrait activity; a portrait-constrained run
// falls back to a bottom bar and cannot prove rail reachability.
// Each case returns to Timer before every tap so every destination is
// entered from the rail, then physically taps it and asserts selection.
// Portrait and existing suites stay as passing controls (see receipt).
@RunWith(AndroidJUnit4::class)
class R48A02LandscapeRailTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun landscapeRailTapsAllFiveAt1x() {
        tapAllFiveDestinations(480, 320, 1f)
    }

    @Test
    fun landscapeRailTapsAllFiveAt2x() {
        tapAllFiveDestinations(480, 320, 2f)
    }

    private fun tapAllFiveDestinations(width: Int, height: Int, fontScale: Float) {
        showScreen(width, height, fontScale)
        navigation(MainTab.Timer).assertIsDisplayed()
        navigation(MainTab.Timer).assertHasLabel(MainTab.Timer)
        navigation(MainTab.Timer).assertIsSelected()
        MainTab.entries.forEachIndexed { index, tab ->
            if (tab != MainTab.Timer) tapDestinationFromRail(tab, index)
        }
        returnToTimer()
        navigation(MainTab.Timer).assertIsSelected()
    }

    private fun tapDestinationFromRail(tab: MainTab, index: Int) {
        returnToTimer()
        scrollRailTo(index)
        navigation(tab).assertIsDisplayed()
        navigation(tab).assertHasLabel(tab)
        navigation(tab).performTouchInput { click() }
        composeRule.waitForIdle()
        navigation(tab).assertIsSelected()
    }

    private fun returnToTimer() {
        if (railListExists()) scrollRailTo(MainTab.entries.indexOf(MainTab.Timer))
        navigation(MainTab.Timer).assertIsDisplayed()
        navigation(MainTab.Timer).performTouchInput { click() }
        composeRule.waitForIdle()
        navigation(MainTab.Timer).assertIsSelected()
    }

    private fun scrollRailTo(index: Int) {
        // Literal must match RailListTag in TimerScreenContent.kt; a plain
        // string keeps this regression compiling against unfixed code.
        // Without the scrollable list there is nothing to scroll: the
        // caller's display assertion then fails exactly on the clipped
        // destination (Network at 1x, Arrivals at 2x).
        if (!railListExists()) return
        composeRule.onNodeWithTag("landscapeRailList").performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun railListExists(): Boolean =
        composeRule.onAllNodesWithTag("landscapeRailList").fetchSemanticsNodes().size == 1

    private fun showScreen(width: Int, height: Int, fontScale: Float) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.fontScale = fontScale
            }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density) {
                PomodoroughTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        PomodoroughScreen(
                            state = AppState(ready = true, authStatus = AuthStatus.SignedOut),
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
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun navigation(tab: MainTab): SemanticsNodeInteraction {
        val name = hasText(label(tab.labelRes)) or hasContentDescription(label(tab.labelRes))
        val matcher = name and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        return composeRule.onNode(matcher).assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    private fun SemanticsNodeInteraction.assertHasLabel(tab: MainTab): SemanticsNodeInteraction {
        val expected = label(tab.labelRes)
        val node = fetchSemanticsNode().config
        val text = node.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        val description = node.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(
            "Tab must preserve label $expected, actual text=$text description=$description",
            text.contains(expected) || description.contains(expected),
        )
        return this
    }

    private fun label(labelRes: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(labelRes)
}
