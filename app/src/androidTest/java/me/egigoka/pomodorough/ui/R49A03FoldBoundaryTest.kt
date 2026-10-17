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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
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
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

@RunWith(Parameterized::class)
class R49A03FoldBoundaryTest(
    private val orientation: FoldHingeOrientation,
    private val density: Float,
    private val direction: LayoutDirection,
) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var hinge by mutableStateOf<HingeBoundsPx?>(null)
    private var state by mutableStateOf(AppState(ready = true, authStatus = AuthStatus.SignedOut))
    private var enabled by mutableStateOf(true)
    private val clicks = mutableListOf<String>()

    @Test
    fun boundaryAndFullSpanTransitionsKeepEveryAvailableTimerState() {
        show()
        val content = bounds("timer_fold_content")
        val intervals = intervals(content)
        listOf("idle", TimerStatus.Running, TimerStatus.Paused, TimerStatus.Completed).forEach { status ->
            rule.runOnIdle { state = timerState(status); enabled = true }
            intervals.forEach { (name, interval) ->
                val original = original(content, interval)
                rule.runOnIdle { hinge = original; clicks.clear() }
                rule.waitForIdle()
                if (name == "full span") assertOccluded() else assertAvailable(content, original, status, name)
            }
        }
        rule.runOnIdle { enabled = false }
        rule.onNodeWithText("Start focus").assertIsNotEnabled()
        rule.onNodeWithText("Stop sound").assertIsNotEnabled()
    }

    private fun show() {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density), LocalLayoutDirection provides direction) {
                PomodoroughTheme {
                    Box(Modifier.fillMaxSize().absolutePadding(left = 19.dp, top = 23.dp, right = 43.dp, bottom = 67.dp)) {
                        HalfOpenTimerScreen(state, enabled, actions(),
                            FoldWindowState(FoldPosture.HalfOpen, orientation, hinge), MainTab.Timer, {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun intervals(content: Rect): List<Pair<String, Pair<Int, Int>>> {
        val start = (if (orientation == FoldHingeOrientation.Vertical) content.left else content.top).roundToInt()
        val end = (if (orientation == FoldHingeOrientation.Vertical) content.right else content.bottom).roundToInt()
        val interior = start + ((end - start) * 0.43f).roundToInt()
        return listOf(
            "interior" to (interior to interior + 11),
            "before" to (start - 15 to start - 5), "start edge" to (start to start),
            "partial start" to (start - 10 to start + 20),
            "end edge" to (end to end), "after" to (end + 10 to end + 30),
            "partial end" to (end - 20 to end + 10),
            "full span" to (start - 10 to end + 10),
            "interior hairline" to (interior to interior),
            "return interior" to (interior to interior + 11),
        )
    }

    private fun original(content: Rect, interval: Pair<Int, Int>): HingeBoundsPx =
        if (orientation == FoldHingeOrientation.Vertical) {
            HingeBoundsPx(interval.first, content.top.toInt(), interval.second, content.bottom.toInt())
        } else HingeBoundsPx(content.left.toInt(), interval.first, content.right.toInt(), interval.second)

    private fun assertAvailable(content: Rect, original: HingeBoundsPx, status: String, name: String) {
        labels(status).forEach { rule.onNodeWithText(it).assertIsDisplayed() }
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
        val readout = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            hasAnyAncestor(hasTestTag("timer_fold_readout")), useUnmergedTree = true).assertIsDisplayed()
        assertRawInside(raw(readout.fetchSemanticsNode()), expected.first(), original)
        assertButtons(expected.last(), original, status)
        assertCallbacks(status)
    }

    // Oracle uses measured content and the original physical hinge, never production geometry output.
    private fun expectedRegions(content: Rect, original: HingeBoundsPx): List<Rect> =
        if (orientation == FoldHingeOrientation.Vertical) {
            listOf(
                Rect(content.left, content.top, original.left.toFloat().coerceIn(content.left, content.right), content.bottom),
                Rect(original.right.toFloat().coerceIn(content.left, content.right), content.top, content.right, content.bottom),
            )
        } else {
            listOf(
                Rect(content.left, content.top, content.right, original.top.toFloat().coerceIn(content.top, content.bottom)),
                Rect(content.left, original.bottom.toFloat().coerceIn(content.top, content.bottom), content.right, content.bottom),
            )
        }.filter { it.width > 0 && it.height > 0 }

    private fun assertButtons(region: Rect, original: HingeBoundsPx, status: String) {
        labels(status).forEach { label ->
            val button = rule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
            assertRawInside(raw(button), region, original)
            rule.onNodeWithText(label).performTouchInput { click() }
        }
        val nodes = rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("timer_fold_controls")),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach {
            assertRawInside(raw(it), region, original)
            assertEquals(direction, it.layoutInfo.layoutDirection)
        }
    }

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

    private fun assertCallbacks(status: String) {
        val expected = when (status) {
            TimerStatus.Running, TimerStatus.Paused -> listOf("toggle", "finish", "cancel")
            TimerStatus.Completed -> listOf("toggle", "stop")
            else -> listOf("toggle")
        }
        rule.runOnIdle { assertEquals(expected, clicks) }
    }

    private fun labels(status: String): List<String> = when (status) {
        TimerStatus.Running -> listOf("Pause", "Finish", "Cancel")
        TimerStatus.Paused -> listOf("Resume", "Finish", "Cancel")
        TimerStatus.Completed -> listOf("Start focus", "Stop sound")
        else -> listOf("Start focus")
    }

    private fun timerState(status: String): AppState {
        val timer = if (status == "idle") null else CanonicalTimer(
            "boundary-timer", TimerPhase.Focus, status, 1500000L, 1000L, "2026-10-13T12:00:00Z",
        )
        return AppState(ready = true, authStatus = AuthStatus.SignedOut, timer = timer,
            completionAlertTimerId = if (status == TimerStatus.Completed) "boundary-timer" else null)
    }

    private fun actions() = TimerContentActions(AppHeaderActions({}, {}, {}),
        TimerHeroActions({ clicks += "toggle" }, { clicks += "finish" }, { clicks += "cancel" }, { clicks += "stop" }, {}), {}, {})

    private fun bounds(tag: String): Rect = raw(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    private fun raw(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.positionInWindow()
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, density={1}, {2}")
        fun cases(): List<Array<Any>> = listOf(FoldHingeOrientation.Vertical, FoldHingeOrientation.Horizontal)
            .flatMap { orientation -> listOf(1f, 1.5f, 2.625f).flatMap { density ->
                LayoutDirection.entries.map { direction -> arrayOf(orientation, density, direction) }
            } }
    }
}
