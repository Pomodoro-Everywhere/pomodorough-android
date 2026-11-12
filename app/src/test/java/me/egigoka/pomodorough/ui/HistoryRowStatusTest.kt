package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, B5 (AND-23): an Arrivals row names its status, and a session
 * that did not complete shows its minutes as planned, not as time spent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryRowStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cancelledRowShowsItsStatusAndPlannedMinutes() {
        render(TimerStatus.Cancelled)
        text("Cancelled").assertExists()
        text("Planned 25 min").assertExists()
        text("25 min").assertDoesNotExist()
        description("Focus, Cancelled, Time not recorded, unassigned, Planned 25 min").assertExists()
    }

    @Test
    fun supersededRowShowsItsStatusAndPlannedMinutes() {
        render(TimerStatus.Superseded)
        text("Continued on another device").assertExists()
        text("Planned 25 min").assertExists()
    }

    @Test
    fun completedRowShowsItsStatusAndKeepsItsMinutes() {
        render(TimerStatus.Completed)
        text("Completed").assertExists()
        text("25 min").assertExists()
        text("Planned 25 min").assertDoesNotExist()
    }

    @Test
    fun onlyCompletedSessionsCountTheirPlannedMinutesAsSpent() {
        val statuses = listOf(TimerStatus.Completed, TimerStatus.Cancelled, TimerStatus.Superseded)
        assertEquals(listOf(false, true, true), statuses.map(::showsPlannedDuration))
    }

    // The row is one node for a screen reader; its texts are in the unmerged tree.
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    private fun description(value: String) = compose.onNodeWithContentDescription(value)

    private fun render(status: String) {
        val item = HistoryItem(
            id = "history-1",
            timerId = "timer-1",
            phase = TimerPhase.Focus,
            status = status,
            plannedDurationMs = 25 * 60_000L,
        )
        compose.setContent {
            PomodoroughTheme(darkTheme = false) { HistoryRow(item, taskTitle = null, showPending = false) }
        }
    }
}
