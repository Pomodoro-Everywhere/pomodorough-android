package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * AND-21: the dial description is spoken text. Counts take their plural form and
 * a zero part is left out, so one minute left reads "1 minute remaining".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerSpokenRemainingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun countsTakeTheirPluralFormAndZeroPartsAreLeftOut() {
        val cases = listOf(
            1L to 0L, 0L to 1L, 1L to 1L, 2L to 30L, 25L to 0L, 0L to 0L,
        )
        compose.setContent {
            Column {
                cases.forEach { (minutes, seconds) -> Text("[" + spokenRemainingTime(minutes, seconds) + "]") }
            }
        }

        listOf(
            "[1 minute]", "[1 second]", "[1 minute 1 second]", "[2 minutes 30 seconds]",
            "[25 minutes]", "[0 seconds]",
        ).forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun oneMinuteOnThePausedDialReadsAsOneMinute() {
        val timer = CanonicalTimer(
            id = "timer-one-minute",
            phase = TimerPhase.Focus,
            status = TimerStatus.Paused,
            plannedDurationMs = 60_000L,
            elapsedAtAnchorMs = 0L,
            anchorAt = "2026-01-01T00:00:00Z",
        )
        compose.setContent { PomodoroughTheme(darkTheme = false) { TimerHero(heroState(timer), noActions) } }

        val descriptions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString() }
            .toSet()
        assertEquals(setOf("1 minute remaining, Focus, Paused"), descriptions)
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
        val noActions = TimerHeroActions({}, {}, {}, {}, {})
    }
}
