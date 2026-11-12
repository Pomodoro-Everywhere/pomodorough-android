package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, B2: at the largest text the Crash reports row no longer squeezes
 * its paragraph into a narrow, screens-tall column beside the switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w320dp-h569dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelledSwitchLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun crashReportsSwitchMovesBelowItsParagraphAtTheLargestText() {
        renderCrashReportingCard(fontScale = 2f)
        val paragraph = paragraphBounds()
        val switch = switchBounds()
        assertTrue(
            "switch top ${switch.top} px is above the paragraph bottom ${paragraph.bottom} px: still side by side " +
                "(paragraph ${paragraph.width} x ${paragraph.height} px)",
            switch.top >= paragraph.bottom,
        )
    }

    @Test
    fun crashReportsSwitchStaysBesideItsParagraphAtTheDefaultTextSize() {
        renderCrashReportingCard(fontScale = 1f)
        val paragraph = paragraphBounds()
        val switch = switchBounds()
        assertTrue("switch left ${switch.left} px, paragraph right ${paragraph.right} px", switch.left >= paragraph.right)
        assertTrue("switch top ${switch.top} px, paragraph bottom ${paragraph.bottom} px", switch.top < paragraph.bottom)
    }

    @Test
    fun labelStacksOnlyWhenItIsSqueezedIntoATallColumn() {
        assertTrue(labelStacksOverSwitch(labelWidth = 188, labelHeight = 413))
        assertTrue(labelStacksOverSwitch(labelWidth = 188, labelHeight = 283))
        assertFalse(labelStacksOverSwitch(labelWidth = 188, labelHeight = 282))
        assertFalse(labelStacksOverSwitch(labelWidth = 228, labelHeight = 100))
    }

    private fun paragraphBounds() =
        compose.onNodeWithText("Sentry crash reports", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

    private fun switchBounds() =
        compose.onNodeWithTag("crash_reporting_switch", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun renderCrashReportingCard(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PomodoroughTheme(darkTheme = false) {
                    // The Network tab gives the card the screen width less its 16 dp gutters.
                    Box(Modifier.width(288.dp)) { CrashReportingCard() }
                }
            }
        }
    }
}
