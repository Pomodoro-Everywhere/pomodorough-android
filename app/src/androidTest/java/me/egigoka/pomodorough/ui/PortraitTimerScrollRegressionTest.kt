package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PortraitTimerScrollRegressionTest(
    private val viewport: String,
    private val width: Int,
    private val height: Int,
    private val fontScale: Float,
) {
    @get:Rule
    val composeRule = createComposeRule()

    private val clicks = mutableListOf<String>()

    @Test
    fun idleTimerCanScrollFromMessageToStartAndBack() {
        showPortrait(AppState())
        scrollBackToMessage()
        clickVisible(R.string.start_phase, label(R.string.focus).lowercase())
        scrollBackToMessage()
        composeRule.runOnIdle { assertEquals(listOf("toggle"), clicks) }
    }

    @Test
    fun activeTimerCanReachPrimaryAndBothSecondaryControls() {
        showPortrait(AppState(timer = timer(TimerStatus.Paused)))
        clickVisible(R.string.resume)
        clickVisible(R.string.finish)
        clickVisible(R.string.cancel)
        scrollBackToMessage()
        composeRule.runOnIdle { assertEquals(listOf("toggle", "finish", "cancel"), clicks) }
    }

    @Test
    fun completedTimerCanReachStopSoundBelowPrimaryControl() {
        showPortrait(AppState(timer = timer(TimerStatus.Completed), completionAlertTimerId = "scroll-timer"))
        clickVisible(R.string.stop_sound)
        clickVisible(R.string.start_phase, label(R.string.focus).lowercase())
        scrollBackToMessage()
        composeRule.runOnIdle { assertEquals(listOf("stopSound", "toggle"), clicks) }
    }

    private fun showPortrait(state: AppState) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.fontScale = this@PortraitTimerScrollRegressionTest.fontScale
            }
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density) {
                PomodoroughTheme {
                    // Fixed test viewports reproduce phone and half-open pane constraints.
                    // Render the real outer screen and hero: testing either alone misses R43-A01.
                    Box(Modifier.size(width.dp, height.dp)) {
                        PortraitTimerScreen(
                            state.copy(ready = true, authStatus = AuthStatus.SignedOut, notice = viewport),
                            mutationsEnabled = true,
                            actions = actions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertCountEquals(1)
    }

    private fun clickVisible(resource: Int, vararg arguments: Any) {
        composeRule.onNodeWithText(label(resource, *arguments))
            .performScrollTo()
            .assertIsDisplayed()
            .performTouchInput { click() }
    }

    private fun scrollBackToMessage() {
        composeRule.onNodeWithContentDescription(label(R.string.heads_up_message, viewport))
            .performScrollTo().assertIsDisplayed()
    }

    private fun label(resource: Int, vararg arguments: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    private fun actions() = TimerContentActions(
        header = AppHeaderActions({}, {}, {}),
        hero = TimerHeroActions(
            onToggleTimer = { clicks += "toggle" },
            onFinishTimer = { clicks += "finish" },
            onCancelTimer = { clicks += "cancel" },
            onStopSound = { clicks += "stopSound" },
            onSelectTask = {},
        ),
        onDismissConflict = {},
        onDismissNotice = {},
    )

    private fun timer(status: String) = CanonicalTimer(
        id = "scroll-timer",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 25 * 60_000L,
        elapsedAtAnchorMs = 25 * 60_000L,
        anchorAt = "2026-09-23T09:00:00Z",
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, fontScale={3}")
        fun viewports(): List<Array<Any>> = listOf(1f, 2f).flatMap { scale ->
            listOf(
                arrayOf("small phone", 320, 480, scale),
                arrayOf("tabletop portrait pane", 320, 240, scale),
                arrayOf("side-by-side portrait pane", 240, 480, scale),
            )
        }
    }
}
