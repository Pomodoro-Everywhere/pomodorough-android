package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
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
 * AND-05: while the timer runs, the hero card's top-start corner grows to a
 * 68 dp radius and its curve cut through the "CURRENT SERVICE" label. The
 * label now starts where the curve has left its first line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h914dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunningCardLabelInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cornerCoverageFollowsTheCircle() {
        // 68 - sqrt(68² - 54²)
        assertEquals(26.67f, roundedCornerCoverage(68.dp, 14.dp).value, 0.01f)
        assertEquals(0f, roundedCornerCoverage(14.dp, 14.dp).value, 0f)
        assertEquals(0f, roundedCornerCoverage(14.dp, 20.dp).value, 0f)
    }

    @Test
    fun smallCornersKeepTheNormalPadding() {
        // The idle (56 dp) corner still reaches past 14 dp; the paused (46 dp)
        // and 22 dp corners do not.
        assertEquals(0f, cornerLabelIndent(22.dp, 14.dp).value, 0f)
        assertTrue(cornerLabelIndent(56.dp, 14.dp) > 0.dp)
        assertEquals(0f, cornerLabelIndent(46.dp, 14.dp).value, 0f)
    }

    @Test
    fun runningCardLabelClearsTheCorner() = assertLabelClearsCorner(TimerStatus.Running, 68.dp)

    @Test
    fun idleCardLabelClearsTheCorner() = assertLabelClearsCorner(null, 56.dp)

    // The primary button fills the card's padded width, so its left edge is
    // where the card's 14 dp padding ends.
    private fun assertLabelClearsCorner(status: String?, corner: Dp) {
        val timer = status?.let {
            CanonicalTimer(
                id = "timer-and05", phase = TimerPhase.Focus, status = it,
                plannedDurationMs = 25 * 60_000L, elapsedAtAnchorMs = 0L, anchorAt = "2026-11-13T00:00:00Z",
            )
        }
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                StaticPomodoroughScreen(AppState(ready = true, authStatus = AuthStatus.SignedOut, timer = timer))
            }
        }
        val label = compose.onNodeWithText("CURRENT SERVICE").fetchSemanticsNode().boundsInRoot
        val buttonText = if (status == TimerStatus.Running) "Pause" else "Start focus"
        val button = compose.onNodeWithText(buttonText).fetchSemanticsNode().boundsInRoot
        val needed = with(compose.density) { cornerLabelIndent(corner, 14.dp).toPx() } - 1f
        assertTrue("label starts ${label.left - button.left} px in, needs $needed px", label.left - button.left >= needed)
    }
}
