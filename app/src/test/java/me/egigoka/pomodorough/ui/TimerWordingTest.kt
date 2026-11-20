package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-24: the timer screen uses one word per state. The landscape header
 * names the phase only, the dial chip says "Ready" whenever a phase can be
 * started, and the Network headings use the same "room" as their buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerWordingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun landscapeHeaderNamesThePhaseWithoutAnActionWord() {
        compose.setContent { PomodoroughTheme(darkTheme = false) { LandscapeReadoutHeader(TimerPhase.ShortBreak, Color.Black) } }

        compose.onNodeWithText("SHORT BREAK", useUnmergedTree = true).assertExists()
        listOf("RESET", "RECOVER", "WORK").forEach { routeWord ->
            assertEquals(routeWord, 0, compose.onAllNodes(hasText(routeWord, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun completedTimerChipSaysReady() = assertChipWord(timer(TimerStatus.Completed), "Ready")

    @Test
    fun cancelledTimerChipSaysReady() = assertChipWord(timer(TimerStatus.Cancelled), "Ready")

    @Test
    fun timerThatNeverRanChipSaysReady() = assertChipWord(null, "Ready")

    @Test
    fun pausedTimerChipSaysPaused() = assertChipWord(timer(TimerStatus.Paused), "Paused")

    @Test
    fun networkHeadingsSayRoomLikeTheirButtons() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Create a room", context.getString(R.string.create_a_room))
        assertEquals("CREATE ROOM", context.getString(R.string.create_iroh_room))
        assertEquals("Join a room", context.getString(R.string.join_a_room))
        assertEquals("JOIN ROOM", context.getString(R.string.join_room))
    }

    private fun assertChipWord(timer: CanonicalTimer?, expected: String) {
        compose.setContent { PomodoroughTheme(darkTheme = false) { TimerHero(heroState(timer), noActions) } }

        compose.onNodeWithText(expected, useUnmergedTree = true).assertExists()
        listOf("Ready", "Paused", "Idle", "Next", "Completed", "Cancelled").filter { it != expected }.forEach { word ->
            assertEquals(word, 0, compose.onAllNodes(hasText(word), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private fun timer(status: String) = CanonicalTimer(
        id = "timer-$status",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 1_500_000L,
        elapsedAtAnchorMs = if (status == TimerStatus.Completed) 1_500_000L else 60_000L,
        anchorAt = "2026-01-01T00:00:00Z",
    )

    private fun heroState(timer: CanonicalTimer?) = TimerHeroState(
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
        val noActions = TimerHeroActions({}, {}, {}, {}, {})
    }
}
