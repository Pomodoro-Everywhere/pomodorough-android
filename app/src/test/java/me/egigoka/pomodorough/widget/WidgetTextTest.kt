package me.egigoka.pomodorough.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WidgetTextTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun runningStatusShowsWallClockEnd() {
        val status = WidgetText.status(
            context,
            TimerSurfaceTiming.Running(endsAtMs = 1_767_225_600_000L),
            wallClock = { "14:25" },
        )

        assertEquals("Ends 14:25", status)
        assertEquals("Pause", WidgetText.toggleLabel(context, TimerSurfaceTiming.Running(0)))
    }

    @Test
    fun pausedStatusShowsRemainingClock() {
        val status = WidgetText.status(context, TimerSurfaceTiming.Paused(remainingMs = 754_000))

        assertEquals("Paused · 12:34 left", status)
        assertEquals("Resume", WidgetText.toggleLabel(context, TimerSurfaceTiming.Paused(0)))
    }

    @Test
    fun idleStatusIsReady() {
        assertEquals("Ready", WidgetText.status(context, TimerSurfaceTiming.Idle))
        assertEquals("Start", WidgetText.toggleLabel(context, TimerSurfaceTiming.Idle))
    }

    @Test
    fun phaseAndTaskLabelsUseProductStrings() {
        assertEquals("Focus", WidgetText.phaseLabel(context, TimerPhase.Focus))
        assertEquals("Short break", WidgetText.phaseLabel(context, TimerPhase.ShortBreak))
        assertEquals("Long break", WidgetText.phaseLabel(context, TimerPhase.LongBreak))
        val snapshot = WidgetSnapshot(TimerPhase.Focus, taskTitle = null, timing = TimerSurfaceTiming.Idle)
        assertEquals("No task", WidgetText.taskTitle(context, snapshot))
        assertEquals("Review", WidgetText.taskTitle(context, snapshot.copy(taskTitle = "Review")))
    }
}
