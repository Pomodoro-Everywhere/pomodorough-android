package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import me.egigoka.pomodorough.data.TimerPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UX round 2, B5 (AND-22): the stepper control that would pass a bound is disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DurationStepperBoundsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun minusIsDisabledAtOneMinute() {
        render(minutes = 1)
        decrease().assertIsNotEnabled()
        increase().assertIsEnabled()
    }

    @Test
    fun plusIsDisabledAtOneHundredEightyMinutes() {
        render(minutes = 180)
        decrease().assertIsEnabled()
        increase().assertIsNotEnabled()
    }

    @Test
    fun bothStepWhenTheDurationIsBetweenTheBounds() {
        render(minutes = 25)
        decrease().assertIsEnabled()
        increase().assertIsEnabled()
    }

    @Test
    fun boundsAreTheSharedDurationLimits() {
        assertEquals(listOf(false, true, true), listOf(1, 2, 180).map(::canDecreaseDuration))
        assertEquals(listOf(true, true, false), listOf(1, 179, 180).map(::canIncreaseDuration))
    }

    private fun decrease() = compose.onNodeWithContentDescription("Decrease Focus duration")

    private fun increase() = compose.onNodeWithContentDescription("Increase Focus duration")

    private fun render(minutes: Int) {
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                Row {
                    PhaseDurationControls(
                        phase = TimerPhase.Focus,
                        minutes = minutes,
                        enabled = true,
                        selected = false,
                        selectedTextColor = Color.Black,
                        onChangeDuration = { _, _ -> },
                    )
                }
            }
        }
    }
}
