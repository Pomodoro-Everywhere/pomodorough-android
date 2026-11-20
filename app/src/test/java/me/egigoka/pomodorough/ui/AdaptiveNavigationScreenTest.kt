package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-26 and AND-29: the window size picks one navigation surface for every
 * tab. Landscape phones and tablets keep the side rail when the user leaves
 * Timer, and landscape Timer shows the same account header as the other tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w569dp-h320dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveNavigationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun smallLandscapeKeepsTheRailOnEveryTab() = assertRailOnEveryTab()

    @Test
    @Config(qualifiers = "en-w891dp-h411dp-land")
    fun regularLandscapeKeepsTheRailOnEveryTab() = assertRailOnEveryTab()

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp-port")
    fun tabletPortraitUsesTheRailOnEveryTab() = assertRailOnEveryTab()

    @Test
    @Config(qualifiers = "en-w1280dp-h800dp-land")
    fun tabletLandscapeUsesTheRailOnEveryTab() = assertRailOnEveryTab()

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-port")
    fun portraitPhoneKeepsTheBottomBarOnEveryTab() {
        render()
        MainTab.entries.forEach { tab ->
            openTab(tab)
            assertEquals("rail on $tab", 0, railCount())
        }
    }

    @Test
    fun landscapeTimerShowsTheAccountHeader() {
        render()
        compose.onNodeWithText(string(me.egigoka.pomodorough.R.string.sign_in)).assertExists()
    }

    private fun assertRailOnEveryTab() {
        render()
        MainTab.entries.forEach { tab ->
            openTab(tab)
            assertEquals("rail on $tab", 1, railCount())
        }
    }

    private fun render() {
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                StaticPomodoroughScreen(AppState(ready = true, authStatus = AuthStatus.SignedOut))
            }
        }
    }

    // The short landscape rail scrolls its destinations; the bar and the
    // tall rail do not.
    private fun openTab(tab: MainTab) {
        val target = hasText(string(tab.labelRes)) and isTab
        if (railCount() == 1) compose.onNode(hasTestTag(RailListTag)).performScrollToNode(target)
        compose.onNode(target).performClick()
        compose.waitForIdle()
    }

    private fun railCount() = compose.onAllNodes(hasTestTag(RailListTag)).fetchSemanticsNodes().size

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)
}
