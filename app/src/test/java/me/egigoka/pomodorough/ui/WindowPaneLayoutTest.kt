package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AND-29: an expanded width with enough height shows two panes. Compact and
 * medium widths, short windows and fold splits keep one pane.
 */
class WindowPaneLayoutTest {
    @Test fun compactWidthKeepsOnePane() {
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(320.dp, 569.dp))
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(411.dp, 914.dp))
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(599.dp, 900.dp))
    }

    @Test fun mediumWidthKeepsOnePane() {
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(600.dp, 900.dp))
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(800.dp, 1280.dp))
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(839.dp, 900.dp))
    }

    @Test fun expandedWidthShowsTwoPanes() {
        assertEquals(WindowPaneLayout.TwoPane, windowPaneLayout(840.dp, 1280.dp))
        assertEquals(WindowPaneLayout.TwoPane, windowPaneLayout(1280.dp, 800.dp))
        assertEquals(WindowPaneLayout.TwoPane, windowPaneLayout(840.dp, 480.dp))
    }

    // A landscape phone is expanded by width, but too short for two panes.
    @Test fun compactHeightKeepsOnePaneAtExpandedWidth() {
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(891.dp, 411.dp))
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(1280.dp, 479.dp))
    }

    @Test fun foldSplitKeepsOnePane() =
        assertEquals(WindowPaneLayout.SinglePane, windowPaneLayout(1280.dp, 800.dp, foldSplit = true))

    @Test fun expandedBreakpointIsTheMaterialExpandedWidth() = assertEquals(840.dp, ExpandedWindowWidthBreakpoint)

    @Test fun taskBoardShowsOnTasksInEveryLayout() {
        WindowPaneLayout.entries.forEach { assertTrue("$it", taskBoardShown(MainTab.Tasks, it)) }
    }

    @Test fun taskBoardShowsBesideTheTimerOnlyWithTwoPanes() {
        assertTrue(taskBoardShown(MainTab.Timer, WindowPaneLayout.TwoPane))
        assertFalse(taskBoardShown(MainTab.Timer, WindowPaneLayout.SinglePane))
    }

    @Test fun otherTabsNeverShowTheTaskBoard() {
        listOf(MainTab.Pattern, MainTab.Arrivals, MainTab.Network).forEach { tab ->
            WindowPaneLayout.entries.forEach { assertFalse("$tab $it", taskBoardShown(tab, it)) }
        }
    }

    @Test fun dialTakesTheHeightTheChromeLeaves() =
        assertEquals(440, timerPaneDialDiameter(paneWidth = 480, viewportHeight = 660, chromeHeight = 220))

    @Test fun dialNeverExceedsThePaneWidth() =
        assertEquals(282, timerPaneDialDiameter(paneWidth = 282, viewportHeight = 1100, chromeHeight = 200))

    // Large text makes the chrome tall. The dial keeps half of the shorter
    // pane side and the pane scrolls.
    @Test fun tallChromeLeavesTheDialHalfOfTheShorterSide() {
        assertEquals(200, timerPaneDialDiameter(paneWidth = 480, viewportHeight = 400, chromeHeight = 380))
        assertEquals(150, timerPaneDialDiameter(paneWidth = 300, viewportHeight = 400, chromeHeight = 380))
    }

    @Test fun dialDigitsGrowWithADialLargerThanThePhoneMaximum() {
        assertEquals(orbitTimeFontSize(318.dp, 1f), orbitTimeFontSize(300.dp, 1f))
        assertTrue(orbitTimeFontSize(440.dp, 1f) > orbitTimeFontSize(318.dp, 1f))
        assertEquals(orbitTimeFontSize(318.dp, 1f) * 2, orbitTimeFontSize(636.dp, 1f))
    }
}
