package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, batch C: Arrivals and the completed-focus totals show the time a
 * session really ran (`elapsedMs`), a completed session shorter than its plan
 * says "Finished early", and a row stored before Core recorded the field keeps
 * the old display. Rework2: elapsed minutes round half up with at least 1 minute
 * for any time above zero, the rule every client uses. The timer view of an early-finished, completed timer shows
 * it as ended: no countdown and no time left.
 *
 * Rows are decoded from JSON so that the test names no new model field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryElapsedViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun earlyFinishedRowShowsItsRealMinutesAndFinishedEarly() {
        renderRow(row("early", TimerStatus.Completed, elapsedMs = 1_200_000L))
        text("Finished early").assertExists()
        text("20 min").assertExists()
        text("25 min").assertDoesNotExist()
        text("Completed").assertDoesNotExist()
        description("Focus, Finished early, Time not recorded, unassigned, 20 min").assertExists()
    }

    @Test
    fun rowStoredWithoutElapsedKeepsTheCompletedPlanDisplay() {
        renderRow(row("legacy", TimerStatus.Completed, elapsedMs = null))
        text("Completed").assertExists()
        text("25 min").assertExists()
        text("Finished early").assertDoesNotExist()
    }

    @Test
    fun cancelledRowWithElapsedShowsTheMinutesItRan() {
        renderRow(row("cancelled", TimerStatus.Cancelled, elapsedMs = 600_000L))
        text("Cancelled").assertExists()
        text("10 min").assertExists()
        text("Planned 25 min").assertDoesNotExist()
    }

    @Test
    fun earlyFinishAfter29SecondsShowsOneMinute() {
        renderRow(row("short", TimerStatus.Completed, elapsedMs = 29_000L))
        text("Finished early").assertExists()
        text("1 min").assertExists()
        text("0 min").assertDoesNotExist()
        description("Focus, Finished early, Time not recorded, unassigned, 1 min").assertExists()
    }

    @Test
    fun earlyFinishAfter30SecondsRoundsHalfUpToOneMinute() {
        renderRow(row("half", TimerStatus.Completed, elapsedMs = 30_000L))
        text("Finished early").assertExists()
        text("1 min").assertExists()
        description("Focus, Finished early, Time not recorded, unassigned, 1 min").assertExists()
    }

    @Test
    fun rowThatRanForNoTimeShowsZeroMinutes() {
        renderRow(row("none", TimerStatus.Cancelled, elapsedMs = 0L))
        text("Cancelled").assertExists()
        text("0 min").assertExists()
        text("Planned 25 min").assertDoesNotExist()
        description("Focus, Cancelled, Time not recorded, unassigned, 0 min").assertExists()
    }

    @Test
    fun cancelledRowWithoutElapsedKeepsThePlannedWording() {
        renderRow(row("legacy-cancelled", TimerStatus.Cancelled, elapsedMs = null))
        text("Cancelled").assertExists()
        text("Planned 25 min").assertExists()
    }

    // Totals count only the elapsedMs Core projected. Core gives a completed row stored before
    // 0.55.0 its plan, so that row arrives here with elapsedMs = 25 min; the client adds no rule.
    @Test
    fun completedFocusTotalsCountTheElapsedTimeOfCompletedRowsOnly() {
        val history = listOf(
            row("early", TimerStatus.Completed, elapsedMs = 1_200_000L),
            row("legacy", TimerStatus.Completed, elapsedMs = 1_500_000L),
            row("cancelled", TimerStatus.Cancelled, elapsedMs = 600_000L),
        )
        compose.setContent {
            PomodoroughTheme(darkTheme = false) { CompletedFocusBreakdown(history, tasks = emptyList()) }
        }
        text("2 · 45 min").assertExists()
    }

    @Test
    fun earlyFinishedCompletedTimerShowsAsEnded() {
        val timer = CanonicalTimer(
            id = "timer-early",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 1_500_000L,
            elapsedAtAnchorMs = 1_200_000L,
            anchorAt = "2026-01-01T00:20:00Z",
        )
        compose.setContent { PomodoroughTheme(darkTheme = false) { TimerHero(heroState(timer), noActions) } }
        val readouts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
        assertTrue(readouts.isNotEmpty())
        readouts.forEach { node ->
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertFalse(description, description.contains("remaining"))
            assertTrue(description, description.contains("Session complete"))
            assertEquals(0f, node.config[SemanticsProperties.ProgressBarRangeInfo].current)
        }
    }

    // The row is one node for a screen reader; its texts are in the unmerged tree.
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    private fun description(value: String) = compose.onNodeWithContentDescription(value)

    private fun renderRow(item: HistoryItem) {
        compose.setContent {
            PomodoroughTheme(darkTheme = false) { HistoryRow(item, taskTitle = null, showPending = false) }
        }
    }

    private fun row(id: String, status: String, elapsedMs: Long?): HistoryItem {
        val elapsed = elapsedMs?.let { ",\"elapsedMs\":$it" }.orEmpty()
        return strictJson.decodeFromString(
            "{\"id\":\"$id\",\"timerId\":\"timer-$id\",\"phase\":\"focus\",\"status\":\"$status\"," +
                "\"plannedDurationMs\":1500000$elapsed}",
        )
    }

    private fun heroState(timer: CanonicalTimer) = TimerHeroState(
        timer = timer,
        activeCompletionAlertTimerId = null,
        settings = TimerSettings(),
        longBreakProgress = 0,
        ready = true,
        tasks = emptyList(),
        selectedTaskId = null,
        mutationsEnabled = true,
    )

    private companion object {
        val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = false }
        val noActions = TimerHeroActions({}, {}, {}, {}, {})
    }
}
