package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class R49A04FoldNoticesTest {
    @get:Rule
    val rule = createComposeRule()

    private var hinge by mutableStateOf<HingeBoundsPx?>(null)
    private var appState by mutableStateOf(foldNoticeState(null))
    private var dismissConflictCalls = 0
    private var dismissNoticeCalls = 0
    private val timerCalls = mutableListOf<String>()

    @Test
    fun splitVerticalLtrShowsMessagesAndKeepsGeometry() {
        show(FoldHingeOrientation.Vertical, 1f, LayoutDirection.Ltr)
        assertSingleFallbackMessages()
        setInteriorHinge(FoldHingeOrientation.Vertical)
        assertMessages()
        assertSplitGeometry(FoldHingeOrientation.Vertical)
        dismissNoticeKeepsConflictAndControls()
        dismissConflict()
        activateTimerControls("Start focus" to "toggle")
    }

    @Test
    fun splitHorizontalRtlPausedShowsMessagesAndKeepsDirection() {
        appState = foldNoticeState(pausedTimer())
        show(FoldHingeOrientation.Horizontal, 2.625f, LayoutDirection.Rtl)
        assertSingleFallbackMessages()
        setInteriorHinge(FoldHingeOrientation.Horizontal)
        assertMessages()
        assertSplitGeometry(FoldHingeOrientation.Horizontal)
        assertControlsInherit(LayoutDirection.Rtl)
        activateTimerControls("Resume" to "toggle", "Finish" to "finish", "Cancel" to "cancel")
    }

    @Test
    fun singleOutsideHingeShowsMessagesInWholeContent() {
        show(FoldHingeOrientation.Vertical, 1f, LayoutDirection.Ltr)
        assertSingleFallbackMessages()
        setOutsideHinge()
        assertMessages()
        assertSingleGeometry()
        activateTimerControls("Start focus" to "toggle")
    }

    @Test
    fun splitVerticalRtlLargeTextAnnouncesNewMessagesWithoutMovingPhysicalPanes() {
        appState = appState.copy(notice = null, conflict = null, timer = pausedTimer())
        show(FoldHingeOrientation.Vertical, 2.625f, LayoutDirection.Rtl, fontScale = 1.5f)
        setInteriorHinge(FoldHingeOrientation.Vertical)
        rule.runOnIdle { appState = appState.copy(notice = NOTICE, conflict = CONFLICT) }
        assertMessages()
        assertSplitGeometry(FoldHingeOrientation.Vertical)
        assertControlsInherit(LayoutDirection.Rtl)
        dismissConflict()
        noticeNode().performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle { assertEquals(1, dismissNoticeCalls) }
        rule.onNodeWithContentDescription("Heads up. $NOTICE").assertDoesNotExist()
        activateTimerControls("Resume" to "toggle", "Finish" to "finish", "Cancel" to "cancel")
    }

    @Test
    fun singleHorizontalRtlSmallPaneScrollsMessagesAndActionsAbovePhysicalHinge() {
        appState = foldNoticeState(pausedTimer())
        show(FoldHingeOrientation.Horizontal, 1.5f, LayoutDirection.Rtl, fontScale = 2f, heightFraction = 0.6f)
        val content = contentBounds()
        rule.runOnIdle {
            hinge = HingeBoundsPx(content.left.toInt(), (content.top + content.height * 0.7f).toInt(),
                content.right.toInt(), content.bottom.toInt() + 20)
        }
        assertMessages()
        val available = contentBounds("timer_fold_available")
        assertEquals(requireNotNull(hinge).top.toFloat(), available.bottom, 0.99f)
        assertFalse(crosses(available, requireNotNull(hinge)))
        activateTimerControls("Resume" to "toggle", "Finish" to "finish", "Cancel" to "cancel")
        dismissNoticeKeepsConflictAndControls()
        dismissConflict()
    }

    @Test
    fun splitNoticeIsDisplayedBeforeExistingAutoDismissTimeoutAndConflictRemains() {
        show(FoldHingeOrientation.Vertical, 1f, LayoutDirection.Ltr)
        setInteriorHinge(FoldHingeOrientation.Vertical)
        assertMessages()
        rule.runOnIdle { assertEquals(0, dismissNoticeCalls) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(7_100)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, dismissNoticeCalls) }
        rule.onNodeWithContentDescription("Heads up. $NOTICE").assertDoesNotExist()
        rule.onNodeWithText(CONFLICT).assertIsDisplayed()
        rule.onNodeWithText("Start focus").assertIsDisplayed()
    }

    private fun show(
        orientation: FoldHingeOrientation,
        density: Float,
        direction: LayoutDirection,
        fontScale: Float = 1f,
        heightFraction: Float = 1f,
    ) {
        dismissConflictCalls = 0
        dismissNoticeCalls = 0
        timerCalls.clear()
        rule.setContent {
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                this.fontScale = fontScale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                PomodoroughTheme {
                    AutoDismissNotice(appState.notice, testActions().onDismissNotice)
                    Box(Modifier.fillMaxHeight(heightFraction).absolutePadding(
                        left = 13.dp, top = 17.dp, right = 19.dp, bottom = 11.dp,
                    )) {
                        HalfOpenTimerScreen(
                            appState, true, testActions(),
                            FoldWindowState(FoldPosture.HalfOpen, orientation, hinge),
                            MainTab.Timer, {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun setInteriorHinge(orientation: FoldHingeOrientation) {
        val content = contentBounds()
        val interior = if (orientation == FoldHingeOrientation.Vertical) {
            content.left.roundToInt() + (content.width * 0.4f).roundToInt()
        } else {
            content.top.roundToInt() + (content.height * 0.43f).roundToInt()
        }
        rule.runOnIdle {
            hinge = if (orientation == FoldHingeOrientation.Vertical) {
                HingeBoundsPx(interior, content.top.toInt(), interior + 11, content.bottom.toInt())
            } else {
                HingeBoundsPx(content.left.toInt(), interior, content.right.toInt(), interior + 11)
            }
        }
        rule.waitForIdle()
    }

    private fun setOutsideHinge() {
        val content = contentBounds()
        rule.runOnIdle {
            hinge = HingeBoundsPx(
                content.right.toInt() + 10, content.top.toInt(),
                content.right.toInt() + 30, content.bottom.toInt(),
            )
        }
        rule.waitForIdle()
    }

    private fun assertSingleFallbackMessages() {
        rule.onNodeWithTag("timer_fold_content").assertIsDisplayed()
        assertMessages()
    }

    private fun assertMessages() {
        rule.onAllNodesWithText(CONFLICT).assertCountEquals(1)
        val conflict = revealMessage(rule.onNodeWithText(CONFLICT)).assertIsDisplayed()
        val conflictNode = conflict.fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, conflictNode.config[SemanticsProperties.LiveRegion])
        assertMessageInsidePane(conflict)
        rule.onAllNodesWithText(NOTICE, useUnmergedTree = true).assertCountEquals(1)
        val notice = noticeNode().assertIsDisplayed()
        val noticeConfig = notice.fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, noticeConfig[SemanticsProperties.LiveRegion])
        assertEquals("Dismiss", noticeConfig[SemanticsActions.OnClick].label)
        assertMessageInsidePane(notice)
    }

    private fun assertSplitGeometry(orientation: FoldHingeOrientation) {
        val original = requireNotNull(hinge)
        val readout = contentBounds("timer_fold_readout")
        val controls = contentBounds("timer_fold_controls")
        rule.onAllNodesWithTag("timer_fold_readout").assertCountEquals(1)
        rule.onAllNodesWithTag("timer_fold_controls").assertCountEquals(1)
        assertFalse(crosses(readout, original))
        assertFalse(crosses(controls, original))
        if (orientation == FoldHingeOrientation.Vertical) {
            assertEquals(original.left.toFloat(), readout.right, 0.99f)
            assertEquals(original.right.toFloat(), controls.left, 0.99f)
        } else {
            assertEquals(original.top.toFloat(), readout.bottom, 0.99f)
            assertEquals(original.bottom.toFloat(), controls.top, 0.99f)
        }
        assertTrue(readout.width > 0 && readout.height > 0)
        assertTrue(controls.width > 0 && controls.height > 0)
    }

    private fun assertSingleGeometry() {
        val content = contentBounds()
        val available = contentBounds("timer_fold_available")
        rule.onNodeWithTag("timer_fold_available").assertIsDisplayed()
        assertEquals(content.left, available.left, 0f)
        assertEquals(content.top, available.top, 0f)
        assertEquals(content.right, available.right, 0f)
        assertEquals(content.bottom, available.bottom, 0f)
    }

    private fun dismissNoticeKeepsConflictAndControls() {
        noticeNode().performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle {
            assertEquals(1, dismissNoticeCalls)
            assertEquals(0, dismissConflictCalls)
        }
        rule.onNodeWithContentDescription("Heads up. $NOTICE").assertDoesNotExist()
        rule.onNodeWithText(CONFLICT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(if (appState.timer == null) "Start focus" else "Resume")
            .performScrollTo().assertIsDisplayed()
    }

    private fun dismissConflict() {
        rule.onNodeWithText("Dismiss").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, dismissConflictCalls) }
        rule.onNodeWithText(CONFLICT).assertDoesNotExist()
    }

    private fun activateTimerControls(vararg controls: Pair<String, String>) {
        timerCalls.clear()
        controls.forEach { (label, _) ->
            val button = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val available = rule.onAllNodesWithTag("timer_fold_available").fetchSemanticsNodes().isNotEmpty()
            val region = contentBounds(if (available) "timer_fold_available" else "timer_fold_controls")
            val coordinates = button.fetchSemanticsNode().layoutInfo.coordinates
            val origin = coordinates.positionInWindow()
            val bounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            assertTrue("$bounds inside controls $region", contains(region, bounds))
            hinge?.let { assertFalse(crosses(bounds, it)) }
            button.performTouchInput { click() }
        }
        rule.runOnIdle { assertEquals(controls.map { it.second }, timerCalls) }
    }

    private fun noticeNode() = revealMessage(rule.onNodeWithContentDescription("Heads up. $NOTICE"))

    private fun revealMessage(node: SemanticsNodeInteraction) = if (hinge == null) node else node.performScrollTo()

    private fun assertMessageInsidePane(message: SemanticsNodeInteraction) {
        val bounds = message.fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
        val pane = if (rule.onAllNodesWithTag("timer_fold_available").fetchSemanticsNodes().isNotEmpty()) {
            contentBounds("timer_fold_available")
        } else if (hinge != null) contentBounds("timer_fold_readout") else contentBounds()
        assertTrue("message $bounds inside physical pane $pane", contains(pane, bounds))
        hinge?.let { assertFalse(crosses(bounds, it)) }
    }

    private fun assertControlsInherit(direction: LayoutDirection) {
        val nodes = rule.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag("timer_fold_controls")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { assertEquals(direction, it.layoutInfo.layoutDirection) }
    }

    private fun contentBounds(tag: String = "timer_fold_content"): Rect {
        val coordinates = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.positionInWindow()
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }

    private fun contains(region: Rect, bounds: Rect): Boolean =
        bounds.width > 0 && bounds.height > 0 && bounds.left >= region.left && bounds.right <= region.right &&
            bounds.top >= region.top && bounds.bottom <= region.bottom

    private fun crosses(bounds: Rect, original: HingeBoundsPx): Boolean =
        bounds.left < original.right && bounds.right > original.left &&
            bounds.top < original.bottom && bounds.bottom > original.top

    private fun testActions() = TimerContentActions(
        AppHeaderActions({}, {}, {}),
        TimerHeroActions({ timerCalls += "toggle" }, { timerCalls += "finish" }, { timerCalls += "cancel" }, {}, {}),
        { dismissConflictCalls++; appState = appState.copy(conflict = null) },
        { dismissNoticeCalls++; appState = appState.copy(notice = null) },
    )

    companion object {
        private const val NOTICE = "Fold notice witness"
        private const val CONFLICT = "Fold conflict witness"

        private fun foldNoticeState(timer: CanonicalTimer?) = AppState(
            ready = true,
            authStatus = AuthStatus.SignedOut,
            timer = timer,
            notice = NOTICE,
            conflict = CONFLICT,
        )

        private fun pausedTimer() = CanonicalTimer(
            "fold-notice-timer", TimerPhase.Focus, TimerStatus.Paused,
            1500000L, 1000L, "2026-10-13T12:00:00Z",
        )
    }
}
