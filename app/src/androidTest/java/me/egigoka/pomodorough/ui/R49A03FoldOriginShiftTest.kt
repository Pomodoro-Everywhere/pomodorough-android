package me.egigoka.pomodorough.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.roundToInt

/**
 * R49-A03 complement to the full boundary matrix: edge, outside, and
 * full-span hinges must survive a live content-origin shift with the same
 * physical window hinge. The full matrix holds insets fixed; the measured
 * test holds the hinge interior. This test moves insets while parked at
 * outside and occluded positions, then returns interior.
 */
@RunWith(JUnit4::class)
class R49A03FoldOriginShiftTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var hinge by mutableStateOf<HingeBoundsPx?>(null)
    private var shifted by mutableStateOf(false)
    private var state by mutableStateOf(AppState(ready = true, authStatus = AuthStatus.SignedOut))
    private val clicks = mutableListOf<String>()

    @Test
    fun edgeOutsideAndFullSpanSurviveLiveOriginShift() {
        show()
        var content = bounds("timer_fold_content")
        drivePosition(content, "interior", true)
        drivePosition(content, "after", true)
        rule.runOnIdle { shifted = true }
        rule.waitForIdle()
        content = bounds("timer_fold_content")
        drivePosition(content, "after", true)
        drivePosition(content, "partial end", true)
        drivePosition(content, "full span", false)
        rule.runOnIdle { shifted = false }
        rule.waitForIdle()
        content = bounds("timer_fold_content")
        drivePosition(content, "full span", false)
        drivePosition(content, "return interior", true)
    }

    private fun drivePosition(content: Rect, name: String, expectAvailable: Boolean) {
        val original = original(content, interval(content, name))
        rule.runOnIdle { clicks.clear(); hinge = original }
        rule.waitForIdle()
        val current = bounds("timer_fold_content")
        val expectedOriginal = original(content, interval(content, name))
        if (expectAvailable) assertAvailable(current, expectedOriginal, name)
        else assertOccluded()
    }

    private fun show() {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
                PomodoroughTheme {
                    Box(Modifier.fillMaxSize().absolutePadding(
                        left = if (shifted) 31.dp else 19.dp,
                        top = if (shifted) 41.dp else 23.dp,
                        right = 43.dp,
                        bottom = if (shifted) 29.dp else 67.dp,
                    )) {
                        HalfOpenTimerScreen(state, true, actions(),
                            FoldWindowState(FoldPosture.HalfOpen, FoldHingeOrientation.Horizontal, hinge), MainTab.Timer, {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun interval(content: Rect, name: String): Pair<Int, Int> {
        val start = content.top.roundToInt()
        val end = content.bottom.roundToInt()
        val interior = start + ((end - start) * 0.43f).roundToInt()
        return when (name) {
            "interior" -> interior to interior + 11
            "after" -> end + 10 to end + 30
            "partial end" -> end - 20 to end + 10
            "full span" -> start - 10 to end + 10
            else -> interior to interior + 11
        }
    }

    private fun original(content: Rect, interval: Pair<Int, Int>): HingeBoundsPx =
        HingeBoundsPx(content.left.toInt(), interval.first, content.right.toInt(), interval.second)

    private fun assertAvailable(content: Rect, original: HingeBoundsPx, name: String) {
        rule.onNodeWithText("Start focus").assertIsDisplayed()
        rule.onNodeWithTag("timer_fold_readout").assertIsDisplayed()
        val expected = expectedRegions(content, original)
        if (expected.size == 1) {
            assertEquals("$name usable region", expected.single(), bounds("timer_fold_available"))
        } else {
            assertEquals(expected.first(), bounds("timer_fold_readout"))
            assertEquals(expected.last(), bounds("timer_fold_controls"))
        }
        rule.onAllNodesWithTag("timer_fold_readout").assertCountEquals(1)
        rule.onAllNodesWithTag("timer_fold_controls").assertCountEquals(1)
        assertRawInside(bounds("timer_fold_readout"), expected.first(), original)
        assertRawInside(bounds("timer_fold_controls"), expected.last(), original)
        rule.onNodeWithText("Start focus").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("toggle"), clicks) }
    }

    private fun expectedRegions(content: Rect, original: HingeBoundsPx): List<Rect> =
        listOf(
            Rect(content.left, content.top, content.right, original.top.toFloat().coerceIn(content.top, content.bottom)),
            Rect(content.left, original.bottom.toFloat().coerceIn(content.top, content.bottom), content.right, content.bottom),
        ).filter { it.width > 0 && it.height > 0 }

    private fun assertRawInside(actual: Rect, region: Rect, original: HingeBoundsPx) {
        assertTrue("raw $actual must have positive dimensions", actual.width > 0 && actual.height > 0)
        assertTrue("raw $actual inside $region", actual.left >= region.left && actual.top >= region.top &&
            actual.right <= region.right && actual.bottom <= region.bottom)
        assertFalse("raw $actual crosses original $original", actual.left < original.right && actual.right > original.left &&
            actual.top < original.bottom && actual.bottom > original.top)
    }

    private fun assertOccluded() {
        rule.onAllNodesWithTag("timer_fold_occluded").assertCountEquals(1)
        rule.onAllNodesWithTag("timer_fold_readout").assertCountEquals(0)
        rule.onAllNodesWithTag("timer_fold_controls").assertCountEquals(0)
        rule.runOnIdle { assertTrue(clicks.isEmpty()) }
    }

    private fun actions() = TimerContentActions(AppHeaderActions({}, {}, {}),
        TimerHeroActions({ clicks += "toggle" }, { clicks += "finish" }, { clicks += "cancel" }, { clicks += "stop" }, {}), {}, {})

    private fun bounds(tag: String): Rect = raw(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    private fun raw(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.positionInWindow()
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }
}
