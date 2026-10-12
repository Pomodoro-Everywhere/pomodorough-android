package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReportingConsent
import me.egigoka.pomodorough.data.TimerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// R48-A04: auto-start and crash switches must expose one merged
// localized name/state/action with no duplicate toggles.
@RunWith(AndroidJUnit4::class)
class R48A04SwitchSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun autoStartExposesSingleMergedToggle() {
        val title = label(R.string.auto_start_breaks)
        val off = label(R.string.switch_off)
        val on = label(R.string.switch_on)
        var checked by mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        composeRule.setContent {
            Frame(1f, LayoutDirection.Ltr) {
                PatternSection(TimerSettings(autoStartBreaks = checked), null, true, {}, { _, _ -> }, {
                    checked = it
                    calls += it
                })
            }
        }
        composeRule.waitForIdle()
        val toggle = composeRule.onNodeWithContentDescription(title)
        toggle.assertIsDisplayed().assertIsEnabled()
        toggle.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertSingleSwitch()
        assertToggleState(toggle.fetchSemanticsNode().config, off, ToggleableState.Off)
        assertRoleSwitch(toggle.fetchSemanticsNode().config)
        toggle.performClick()
        composeRule.waitForIdle()
        assertTrue("Auto-start click must toggle on", checked)
        assertEquals(listOf(true), calls)
        assertToggleState(toggle.fetchSemanticsNode().config, on, ToggleableState.On)
    }

    @Test
    fun crashExposesSingleMergedToggle() {
        val title = label(R.string.crash_reporting_title)
        val off = label(R.string.switch_off)
        CrashReportingConsent.setEnabled(target(), false)
        showCrash(1f, LayoutDirection.Ltr)
        val toggle = composeRule.onNodeWithContentDescription(title)
        toggle.assertIsDisplayed().assertIsEnabled()
        toggle.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertSingleSwitch()
        assertToggleState(toggle.fetchSemanticsNode().config, off, ToggleableState.Off)
        assertRoleSwitch(toggle.fetchSemanticsNode().config)
        toggle.performClick()
        composeRule.waitForIdle()
        assertTrue("Crash toggle must persist opt-in", CrashReportingConsent.isEnabled(target()))
        assertToggleState(toggle.fetchSemanticsNode().config, label(R.string.switch_on), ToggleableState.On)
    }

    @Test
    fun autoStartDisabledKeepsNameStateAndTarget() {
        val title = label(R.string.auto_start_breaks)
        val off = label(R.string.switch_off)
        var checked by mutableStateOf(false)
        showAutoStart(checked, false, 1f, LayoutDirection.Ltr) { checked = it }
        val toggle = composeRule.onNodeWithContentDescription(title)
        toggle.assertIsDisplayed().assertIsNotEnabled()
        toggle.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertSingleSwitch()
        assertToggleState(toggle.fetchSemanticsNode().config, off, ToggleableState.Off)
        assertRoleSwitch(toggle.fetchSemanticsNode().config)
        composeRule.runOnIdle { assertTrue("Disabled must not toggle", !checked) }
    }

    @Test
    fun rtlKeepsSingleTogglePerCardWithAction() {
        val autoTitle = label(R.string.auto_start_breaks)
        val crashTitle = label(R.string.crash_reporting_title)
        var checked by mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        CrashReportingConsent.setEnabled(target(), false)
        composeRule.setContent {
            Frame(1f, LayoutDirection.Rtl) {
                Column {
                    PatternSection(
                        TimerSettings(autoStartBreaks = checked), null, true,
                        {}, { _, _ -> }, { checked = it; calls += it },
                    )
                    CrashReportingCard()
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(2, switchNodes())
        val auto = composeRule.onNodeWithContentDescription(autoTitle)
        auto.assertIsDisplayed().assertIsEnabled()
        auto.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        auto.performClick()
        composeRule.waitForIdle()
        assertTrue("RTL auto-start must toggle", checked)
        val crash = composeRule.onNodeWithContentDescription(crashTitle)
        crash.assertIsDisplayed().assertIsEnabled()
        crash.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        crash.performClick()
        composeRule.waitForIdle()
        assertTrue("RTL crash must toggle", CrashReportingConsent.isEnabled(target()))
    }

    @Test
    fun largeTextKeepsSingleToggleReachable() {
        val autoTitle = label(R.string.auto_start_breaks)
        val crashTitle = label(R.string.crash_reporting_title)
        var checked by mutableStateOf(false)
        CrashReportingConsent.setEnabled(target(), false)
        composeRule.setContent {
            Frame(2f, LayoutDirection.Ltr) {
                Column {
                    PatternSection(
                        TimerSettings(autoStartBreaks = checked), null, true,
                        {}, { _, _ -> }, { checked = it },
                    )
                    CrashReportingCard()
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(2, switchNodes())
        composeRule.onNodeWithContentDescription(autoTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(crashTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(autoTitle).performClick()
        composeRule.waitForIdle()
        assertTrue("2x auto-start must toggle", checked)
        composeRule.onNodeWithContentDescription(crashTitle).performClick()
        composeRule.waitForIdle()
        assertTrue("2x crash must toggle", CrashReportingConsent.isEnabled(target()))
    }

    private fun showAutoStart(c: Boolean, e: Boolean, s: Float, d: LayoutDirection, onToggle: (Boolean) -> Unit) {
        composeRule.setContent {
            Frame(s, d) {
                PatternSection(TimerSettings(autoStartBreaks = c), null, e, {}, { _, _ -> }, onToggle)
            }
        }
        composeRule.waitForIdle()
    }

    private fun showCrash(s: Float, d: LayoutDirection) {
        composeRule.setContent { Frame(s, d) { CrashReportingCard() } }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Frame(s: Float, d: LayoutDirection, content: @Composable () -> Unit) {
        val c = Configuration(LocalConfiguration.current)
        c.fontScale = s
        val density = Density(LocalDensity.current.density, s)
        CompositionLocalProvider(LocalConfiguration provides c, LocalDensity provides density) {
            CompositionLocalProvider(LocalLayoutDirection provides d) {
                PomodoroughTheme { Box(Modifier.size(400.dp, 900.dp)) { content() } }
            }
        }
    }

    private fun assertSingleSwitch() {
        assertEquals("Must expose exactly one switch toggle", 1, switchNodes())
    }

    private fun switchNodes(): Int {
        val m = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        return composeRule.onAllNodes(m).fetchSemanticsNodes().size
    }

    private fun assertToggleState(c: androidx.compose.ui.semantics.SemanticsConfiguration, d: String, s: ToggleableState) {
        assertEquals(d, c.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(s, c.getOrNull(SemanticsProperties.ToggleableState))
    }

    private fun assertRoleSwitch(c: androidx.compose.ui.semantics.SemanticsConfiguration) {
        assertEquals(Role.Switch, c.getOrNull(SemanticsProperties.Role))
        assertTrue("Switch must expose toggle action", c.contains(SemanticsProperties.ToggleableState))
    }

    private fun target() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(res: Int): String = target().getString(res)
}
