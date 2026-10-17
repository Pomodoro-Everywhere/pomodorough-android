package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt

internal data class R49A04NoticeEnvironment(
    val orientation: FoldHingeOrientation,
    val density: Float,
    val direction: LayoutDirection,
    val fontScale: Float = 1f,
    val heightFraction: Float = 1f,
)

internal enum class R49A04NoticeRegion { Split, Leading, Trailing, Outside }

internal class R49A04NoticePaneFixture(
    val rule: ComposeContentTestRule,
    val environment: R49A04NoticeEnvironment,
) {
    var state by mutableStateOf(AppState(ready = true, authStatus = AuthStatus.SignedOut))
    private var hinge by mutableStateOf<HingeBoundsPx?>(null)
    private var region = R49A04NoticeRegion.Split
    var noticeDismissals = 0
        private set
    var conflictDismissals = 0
        private set
    val timerCalls = mutableListOf<String>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val messagePane get() = if (region == R49A04NoticeRegion.Split) "timer_fold_readout" else "timer_fold_available"
    private val actionPane get() = if (region == R49A04NoticeRegion.Split) "timer_fold_controls" else "timer_fold_available"

    fun show(timeout: R49A04NoticeTimeout? = null) {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { fontScale = environment.fontScale }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(environment.density, environment.fontScale),
                LocalLayoutDirection provides environment.direction,
                LocalAccessibilityManager provides (timeout ?: LocalAccessibilityManager.current),
            ) {
                PomodoroughTheme {
                    if (timeout != null) AutoDismissNotice(state.notice, actions().onDismissNotice)
                    Box(Modifier.fillMaxHeight(environment.heightFraction)
                        .absolutePadding(left = 13.dp, top = 17.dp, right = 29.dp, bottom = 31.dp)) {
                        HalfOpenTimerScreen(
                            state, true, actions(),
                            FoldWindowState(FoldPosture.HalfOpen, environment.orientation, hinge), MainTab.Timer, {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun setRegion(next: R49A04NoticeRegion) {
        region = next
        val content = bounds("timer_fold_content")
        val vertical = environment.orientation == FoldHingeOrientation.Vertical
        val start = if (vertical) content.left else content.top
        val end = if (vertical) content.right else content.bottom
        val extent = end - start
        val gap = (extent * 0.025f).roundToInt().coerceAtLeast(1)
        val interval = when (next) {
            R49A04NoticeRegion.Split -> (start + extent * 0.47f).roundToInt().let { it to it + gap }
            R49A04NoticeRegion.Leading -> (start - gap).roundToInt() to (start + extent * 0.18f).roundToInt()
            R49A04NoticeRegion.Trailing -> (start + extent * 0.72f).roundToInt() to (end + gap).roundToInt()
            R49A04NoticeRegion.Outside -> (end + gap).roundToInt() to (end + gap * 2).roundToInt()
        }
        rule.runOnIdle {
            hinge = if (vertical) HingeBoundsPx(interval.first, content.top.toInt(), interval.second, content.bottom.toInt())
            else HingeBoundsPx(content.left.toInt(), interval.first, content.right.toInt(), interval.second)
        }
        rule.waitForIdle()
    }

    fun publishMessages(notice: String = NOTICE) {
        rule.runOnIdle { state = state.copy(notice = notice, conflict = CONFLICT) }
        settle()
    }

    fun assertMessages(notice: String = NOTICE) {
        rule.onAllNodesWithText(CONFLICT).assertCountEquals(1)
        val conflict = reveal(rule.onNodeWithText(CONFLICT), messagePane)
        conflict.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onAllNodesWithText(notice, useUnmergedTree = true).assertCountEquals(1)
        val card = reveal(noticeNode(notice), messagePane)
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertIsEnabled()
        assertEquals(context.getString(R.string.dismiss), card.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasContentDescription(description(notice))))
            .assertCountEquals(0)
        val conflictButtons = hasClickAction() and hasAnyAncestor(hasText(CONFLICT))
        rule.onAllNodes(conflictButtons).assertCountEquals(1)
    }

    fun dismissNotice(notice: String = NOTICE) {
        reveal(noticeNode(notice), messagePane).performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle { assertEquals(null, state.notice) }
        settle()
        noticeNode(notice).assertDoesNotExist()
        rule.onAllNodesWithText(notice, useUnmergedTree = true).assertCountEquals(0)
    }

    fun dismissConflict() {
        reveal(rule.onNodeWithText(context.getString(R.string.dismiss)), messagePane)
            .assertIsEnabled().performTouchInput { click() }
        rule.onNodeWithText(CONFLICT).assertDoesNotExist()
    }

    fun assertConflictReachable() {
        reveal(rule.onNodeWithText(CONFLICT), messagePane)
    }

    fun assertNoticeReachable(notice: String = NOTICE) {
        reveal(noticeNode(notice), messagePane)
    }

    fun assertPaneGeometry() {
        val content = bounds("timer_fold_content")
        val original = requireNotNull(hinge)
        val vertical = environment.orientation == FoldHingeOrientation.Vertical
        val expected = when (region) {
            R49A04NoticeRegion.Split -> if (vertical) listOf(
                Rect(content.left, content.top, original.left.toFloat(), content.bottom),
                Rect(original.right.toFloat(), content.top, content.right, content.bottom),
            ) else listOf(
                Rect(content.left, content.top, content.right, original.top.toFloat()),
                Rect(content.left, original.bottom.toFloat(), content.right, content.bottom),
            )
            R49A04NoticeRegion.Leading -> listOf(if (vertical)
                Rect(original.right.toFloat(), content.top, content.right, content.bottom)
            else Rect(content.left, original.bottom.toFloat(), content.right, content.bottom))
            R49A04NoticeRegion.Trailing -> listOf(if (vertical)
                Rect(content.left, content.top, original.left.toFloat(), content.bottom)
            else Rect(content.left, content.top, content.right, original.top.toFloat()))
            R49A04NoticeRegion.Outside -> listOf(content)
        }
        val tags = if (region == R49A04NoticeRegion.Split) listOf(messagePane, actionPane) else listOf(messagePane)
        tags.zip(expected).forEach { (tag, rectangle) -> assertSameRectangle(rectangle, bounds(tag)) }
        rule.onAllNodesWithTag("timer_fold_available").assertCountEquals(if (expected.size == 1) 1 else 0)
        rule.onAllNodesWithTag("timer_fold_readout").assertCountEquals(1)
        rule.onAllNodesWithTag("timer_fold_controls").assertCountEquals(1)
        assertTrue("The content includes the asymmetric test insets", content.left > 0 && content.top > 0)
    }

    fun activateTimer(status: String?) {
        val expected = when (status) {
            TimerStatus.Running -> listOf(R.string.pause to "toggle", R.string.finish to "finish", R.string.cancel to "cancel")
            TimerStatus.Paused -> listOf(R.string.resume to "toggle", R.string.finish to "finish", R.string.cancel to "cancel")
            TimerStatus.Completed -> listOf(null to "toggle", R.string.stop_sound to "stop")
            else -> listOf(null to "toggle")
        }
        rule.runOnIdle {
            val timer = status?.let { CanonicalTimer("a04-notice-timer", TimerPhase.Focus, it, 1_500_000, 1_000, "2026-10-17T12:00:00Z") }
            state = state.copy(timer = timer, completionAlertTimerId = timer?.id?.takeIf { status == TimerStatus.Completed })
            timerCalls.clear()
        }
        expected.forEach { (resource, _) ->
            val label = resource?.let { context.getString(it) }
                ?: context.getString(R.string.start_phase, context.getString(R.string.focus).lowercase())
            rule.onAllNodesWithText(label).assertCountEquals(1)
            reveal(rule.onNodeWithText(label), actionPane).assertIsEnabled().performTouchInput { click() }
        }
        rule.runOnIdle { assertEquals(expected.map { it.second }, timerCalls) }
    }

    fun scrollPosition(): Float {
        val scroll = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyAncestor(hasTestTag(messagePane)), useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return rule.runOnIdle { scroll.value() }
    }

    fun noticeNode(notice: String = NOTICE) = rule.onNodeWithContentDescription(description(notice))

    private fun description(notice: String) = context.getString(R.string.heads_up_message, notice)

    private fun settle() {
        rule.runOnIdle {}
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
    }

    private fun reveal(node: SemanticsNodeInteraction, pane: String): SemanticsNodeInteraction {
        node.performScrollTo()
        settle()
        node.assertIsDisplayed().assert(hasAnyAncestor(hasTestTag(pane)))
        val semantics = node.fetchSemanticsNode()
        assertEquals(environment.direction, semantics.layoutInfo.layoutDirection)
        assertRawInside(raw(semantics), bounds(pane))
        return node
    }

    private fun bounds(tag: String): Rect = raw(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    // Use the complete measured rectangle, not boundsInWindow(), which can hide overflow by clipping.
    private fun raw(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.positionInWindow()
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }

    private fun assertRawInside(actual: Rect, pane: Rect) {
        assertTrue("The measured node must have area: $actual", actual.width > 0 && actual.height > 0)
        assertTrue("The complete node $actual must fit inside $pane", actual.left >= pane.left - 1 && actual.right <= pane.right + 1 &&
            actual.top >= pane.top - 1 && actual.bottom <= pane.bottom + 1)
        val original = requireNotNull(hinge)
        assertFalse("The complete node $actual crosses the original hinge $original",
            actual.left < original.right && actual.right > original.left && actual.top < original.bottom && actual.bottom > original.top)
    }

    private fun assertSameRectangle(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.99f)
        assertEquals(expected.top, actual.top, 0.99f)
        assertEquals(expected.right, actual.right, 0.99f)
        assertEquals(expected.bottom, actual.bottom, 0.99f)
        assertRawInside(actual, actual)
    }

    private fun actions() = TimerContentActions(
        AppHeaderActions({}, {}, {}),
        TimerHeroActions({ timerCalls += "toggle" }, { timerCalls += "finish" }, { timerCalls += "cancel" }, { timerCalls += "stop" }, {}),
        { conflictDismissals++; state = state.copy(conflict = null) },
        { noticeDismissals++; state = state.copy(notice = null) },
    )

    companion object {
        const val NOTICE = "A04 notice"
        const val CONFLICT = "A04 conflict"
    }
}

internal class R49A04NoticeTimeout(val recommendation: Long = 20_000) : AccessibilityManager {
    data class Request(val original: Long, val icons: Boolean, val text: Boolean, val controls: Boolean)
    val requests = mutableListOf<Request>()

    override fun calculateRecommendedTimeoutMillis(
        originalTimeoutMillis: Long,
        containsIcons: Boolean,
        containsText: Boolean,
        containsControls: Boolean,
    ): Long {
        requests += Request(originalTimeoutMillis, containsIcons, containsText, containsControls)
        return recommendation
    }
}
