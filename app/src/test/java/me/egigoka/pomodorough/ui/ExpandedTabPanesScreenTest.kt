package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-29: the production screen at compact, medium and expanded widths. An
 * expanded width shows two panes beside the rail; the Timer shows the dial
 * whenever the window is not short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedTabPanesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabletLandscapeShowsTheTimerBesideTheTaskBoard() {
        render()
        val (leading, trailing) = assertTwoPanesSideBySide("Timer")
        assertInside("dial", bounds(hasTestTag(TimerDialTag)), leading)
        assertInside("task picker", bounds(hasText(string(R.string.focus_task)) and inTrailingPane), trailing)
        assertInside("task row", bounds(hasContentDescription("Delete Alpha")), trailing)
    }

    // The dial uses the pane: it is larger than the phone maximum, and the
    // primary control still shares the first viewport with it.
    @Test
    fun tabletLandscapeDialUsesThePaneHeight() {
        render()
        val dial = bounds(hasTestTag(TimerDialTag))
        val start = bounds(hasText(string(R.string.start_phase, "focus")))
        val phoneMaximum = with(compose.density) { 318.dp.toPx() }
        assertTrue("dial is ${dial.width} px wide, not above the phone maximum $phoneMaximum", dial.width > phoneMaximum)
        assertEquals("dial is round", dial.width, dial.height)
        assertTrue("Start at ${start.top} overlaps the dial that ends at ${dial.bottom}", start.top >= dial.bottom)
        assertTrue("Start ends at ${start.bottom}, below the window", start.bottom <= rootBounds().bottom)
    }

    @Test
    fun tabletLandscapeShowsTwoPanesOnPatternArrivalsAndNetwork() {
        render()
        openTab(MainTab.Pattern)
        val pattern = assertTwoPanesSideBySide("Pattern")
        assertInside("phase list", bounds(hasText(string(R.string.service_pattern))), pattern.first)
        assertInside("pattern settings", bounds(hasText(string(R.string.auto_start_breaks))), pattern.second)

        openTab(MainTab.Arrivals)
        val arrivals = assertTwoPanesSideBySide("Arrivals")
        assertInside("arrivals summary", bounds(hasText(string(R.string.recent_arrivals))), arrivals.first)
        assertInside("run log", bounds(hasText(string(R.string.no_arrivals_yet))), arrivals.second)

        openTab(MainTab.Network)
        val network = assertTwoPanesSideBySide("Network")
        assertInside("routes", bounds(hasText(string(R.string.choose_where_time_travels))), network.first)
        assertInside("route detail", bounds(hasText(string(R.string.privacy_access))), network.second)
    }

    // The run log starts level with the summary title, from the list layout
    // alone: no offset places it.
    @Test
    fun arrivalsRunLogStartsLevelWithTheSummaryTitle() {
        val arrival = HistoryItem(
            id = "history-1",
            timerId = "timer-1",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 25 * 60_000L,
        )
        render(history = listOf(arrival))
        openTab(MainTab.Arrivals)
        val firstRow = compose.onAllNodes(inTrailingPane and hasContentDescriptionDefined).fetchSemanticsNodes()
            .first().boundsInRoot
        assertEquals("first run log row", summaryTitleTop(), firstRow.top, 1f)
    }

    // The empty card pads its text 22 dp inside its top edge.
    @Test
    fun emptyArrivalsCardStartsLevelWithTheSummaryTitle() {
        render()
        openTab(MainTab.Arrivals)
        val cardTop = bounds(hasText(string(R.string.no_arrivals_yet))).top - with(compose.density) { 22.dp.toPx() }
        assertEquals("empty run log card", summaryTitleTop(), cardTop, 1f)
    }

    private fun summaryTitleTop(): Float =
        bounds(hasText(string(R.string.run_log_this_device)) and hasAnyAncestor(hasTestTag(LeadingPaneTag))).top

    @Test
    fun tabletLandscapeKeepsTheTasksTabAsOnePane() {
        render()
        openTab(MainTab.Tasks)
        assertEquals(0, paneCount())
        compose.onNode(hasContentDescription("Delete Alpha")).assertExists()
    }

    @Test
    @Config(qualifiers = "en-w840dp-h1280dp-port")
    fun expandedPortraitShowsTwoPanes() {
        render()
        assertTwoPanesSideBySide("Timer")
    }

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp-port")
    fun mediumWidthKeepsOnePaneWithTheDial() = assertOnePaneOnEveryTabWithTheDial()

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-port")
    fun compactWidthKeepsOnePaneWithTheDial() = assertOnePaneOnEveryTabWithTheDial()

    // Wider than high, but not short: the old rule showed the compact readout here.
    @Test
    @Config(qualifiers = "en-w800dp-h600dp-land")
    fun wideWindowWithEnoughHeightShowsTheDialInOnePane() = assertOnePaneOnEveryTabWithTheDial()

    @Test
    @Config(qualifiers = "en-w891dp-h411dp-land")
    fun shortLandscapeKeepsTheCompactReadoutInOnePane() {
        render()
        assertEquals(0, paneCount())
        compose.onNode(hasTestTag(TimerDialTag)).assertDoesNotExist()
        compose.onNode(timerReadout).assertExists()
    }

    private fun assertOnePaneOnEveryTabWithTheDial() {
        render()
        compose.onNode(hasTestTag(TimerDialTag)).assertExists()
        MainTab.entries.forEach { tab ->
            openTab(tab)
            assertEquals("panes on $tab", 0, paneCount())
        }
    }

    private fun assertTwoPanesSideBySide(tab: String): Pair<Rect, Rect> {
        val leading = bounds(hasTestTag(LeadingPaneTag))
        val trailing = bounds(hasTestTag(TrailingPaneTag))
        assertTrue("$tab: panes overlap, $leading and $trailing", leading.right <= trailing.left)
        assertEquals("$tab: panes share the top", leading.top, trailing.top)
        assertTrue("$tab: trailing pane ends at ${trailing.right}", trailing.right <= rootBounds().right)
        return leading to trailing
    }

    private fun assertInside(what: String, inner: Rect, pane: Rect) = assertTrue(
        "$what at $inner is outside its pane $pane",
        inner.left >= pane.left && inner.right <= pane.right && inner.top >= pane.top && inner.bottom <= pane.bottom,
    )

    private fun render(history: List<HistoryItem> = emptyList()) {
        val tasks = listOf(FocusTask("task-alpha", "Alpha"), FocusTask("task-beta", "Beta"))
        val state = AppState(
            ready = true,
            authStatus = AuthStatus.SignedOut,
            tasks = tasks,
            taskSummaries = tasks.map { TaskDailySummary(it, 0, 0L) },
            history = history,
        )
        compose.setContent { PomodoroughTheme(darkTheme = false) { StaticPomodoroughScreen(state) } }
    }

    // A short rail scrolls its destinations; the bar and the tall rail do not.
    private fun openTab(tab: MainTab) {
        val target = hasText(string(tab.labelRes)) and isTab
        if (count(hasTestTag(RailListTag)) == 1) compose.onNode(hasTestTag(RailListTag)).performScrollToNode(target)
        compose.onNode(target).performClick()
        compose.waitForIdle()
    }

    private fun paneCount() = count(hasTestTag(LeadingPaneTag)) + count(hasTestTag(TrailingPaneTag))

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun rootBounds(): Rect = compose.onNode(SemanticsMatcher("is root") { it.isRoot }).fetchSemanticsNode().boundsInRoot

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val inTrailingPane = hasAnyAncestor(hasTestTag(TrailingPaneTag))
    private val hasContentDescriptionDefined = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
    private val timerReadout = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *args)
}
