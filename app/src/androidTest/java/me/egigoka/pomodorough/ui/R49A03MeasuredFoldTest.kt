package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

@RunWith(Parameterized::class)
class R49A03MeasuredFoldTest(
    private val orientation: FoldHingeOrientation,
    private val density: Float,
    private val direction: LayoutDirection,
) {
    @get:Rule
    val composeRule = createComposeRule()

    private var originalHinge by mutableStateOf<HingeBoundsPx?>(null)
    private var shifted by mutableStateOf(false)
    private var toggles = 0
    private var windowBounds: Rect? = null

    @Test
    fun originalPhysicalHingeExcludesMeasuredPanesAndControlsAfterInsetsMove() {
        showFold()
        assertPhysicalPanes()
        composeRule.runOnIdle { shifted = true }
        composeRule.waitForIdle()
        assertPhysicalPanes()
        composeRule.onNodeWithText("Start focus").assertIsDisplayed().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }

    private fun showFold() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density), LocalLayoutDirection provides direction,
            ) {
                PomodoroughTheme {
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        windowBounds = it.boundsInWindow()
                        originalHinge = physicalHinge(requireNotNull(windowBounds))
                    }) {
                        Box(Modifier.fillMaxSize().absolutePadding(
                            left = if (shifted) 31.dp else 19.dp,
                            top = if (shifted) 41.dp else 23.dp,
                            right = 43.dp, bottom = if (shifted) 29.dp else 67.dp,
                        )) {
                            HalfOpenTimerScreen(
                                AppState(ready = true, authStatus = AuthStatus.SignedOut), true, actions(),
                                FoldWindowState(FoldPosture.HalfOpen, orientation, originalHinge),
                                MainTab.Timer, {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // Synthetic FoldingFeature input uses physical window pixels, not mapped content output.
    private fun physicalHinge(window: Rect): HingeBoundsPx {
        val gap = (11 * density).roundToInt()
        return if (orientation == FoldHingeOrientation.Vertical) {
            val left = (window.left + window.width * 0.37f).roundToInt()
            HingeBoundsPx(left, window.top.roundToInt(), left + gap, window.bottom.roundToInt())
        } else {
            val top = (window.top + window.height * 0.43f).roundToInt()
            HingeBoundsPx(window.left.roundToInt(), top, window.right.roundToInt(), top + gap)
        }
    }

    private fun assertPhysicalPanes() {
        val original = requireNotNull(originalHinge)
        val content = windowBounds("timer_fold_content")
        val readout = windowBounds("timer_fold_readout")
        val controls = windowBounds("timer_fold_controls")
        val navigation = windowBounds("timer_fold_navigation")
        composeRule.onAllNodesWithTag("timer_fold_readout").assertCountEquals(1)
        composeRule.onAllNodesWithTag("timer_fold_controls").assertCountEquals(1)
        assertTrue("content origin must include asymmetric insets", content.left > 0 && content.top > 0)
        assertEquals("bottom navigation is excluded from content", navigation.top, content.bottom, 0f)
        assertTrue("bottom navigation has measured height", navigation.height > 0)
        assertTrue("bottom inset differs from top inset", requireNotNull(windowBounds).bottom - navigation.bottom > 0)
        assertPaneEdges(content, readout, controls, original)
        val controlNodes = composeRule.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag("timer_fold_controls")), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("real timer controls must exist", controlNodes.isNotEmpty())
        controlNodes.forEach { node ->
            val coordinates = node.layoutInfo.coordinates
            val origin = coordinates.positionInWindow()
            val bounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            assertFalse("control $bounds crosses original $original", crossesOriginal(bounds, original))
            assertEquals(direction, node.layoutInfo.layoutDirection)
        }
    }

    private fun assertPaneEdges(content: Rect, readout: Rect, controls: Rect, original: HingeBoundsPx) {
        assertFalse(crossesOriginal(readout, original))
        assertFalse(crossesOriginal(controls, original))
        if (orientation == FoldHingeOrientation.Vertical) {
            assertEquals(content.left, readout.left, 0f)
            assertEquals(original.left.toFloat(), readout.right, 0.99f)
            assertEquals(original.right.toFloat(), controls.left, 0.99f)
            assertEquals(content.right, controls.right, 0f)
            assertEquals(content.top, controls.top, 0f)
            assertEquals(content.bottom, controls.bottom, 0f)
        } else {
            assertEquals(content.top, readout.top, 0f)
            assertEquals(original.top.toFloat(), readout.bottom, 0.99f)
            assertEquals(original.bottom.toFloat(), controls.top, 0.99f)
            assertEquals(content.bottom, controls.bottom, 0f)
            assertEquals(content.left, controls.left, 0f)
            assertEquals(content.right, controls.right, 0f)
        }
    }

    private fun crossesOriginal(bounds: Rect, original: HingeBoundsPx): Boolean =
        bounds.left < original.right && bounds.right > original.left &&
            bounds.top < original.bottom && bounds.bottom > original.top

    private fun windowBounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()

    private fun actions() = TimerContentActions(
        AppHeaderActions({}, {}, {}),
        TimerHeroActions({ toggles++ }, {}, {}, {}, {}), {}, {},
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, density={1}, {2}")
        fun cases(): List<Array<Any>> = listOf(
            FoldHingeOrientation.Vertical, FoldHingeOrientation.Horizontal,
        ).flatMap { orientation ->
            listOf(1f, 1.5f, 2.625f).flatMap { density ->
                LayoutDirection.entries.map { direction -> arrayOf(orientation, density, direction) }
            }
        }
    }
}
