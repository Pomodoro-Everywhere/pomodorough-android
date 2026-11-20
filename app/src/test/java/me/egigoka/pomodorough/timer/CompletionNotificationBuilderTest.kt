package me.egigoka.pomodorough.timer

import android.app.Application
import android.app.Notification
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.data.TimerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class CompletionNotificationBuilderTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val afterFocus = CompletionNextPhases(start = TimerPhase.ShortBreak, skipTo = TimerPhase.Focus)

    // AND-24: the notification uses the timer screen's words ("Session complete.",
    // "Start short break", "Skip to focus"), not "Service complete" and "Start next".
    @Test
    fun completionNotificationUsesTheTimerScreenWords() {
        val notification = notification(afterFocus)

        assertEquals("Session complete", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Next: Short break", notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(
            listOf("Stop sound", "Start short break", "Skip to focus"),
            notification.actions.map { it.title.toString() },
        )
    }

    @Test
    fun skipIsLeftOutWhenTheCoreOffersNoSkip() {
        val notification = notification(CompletionNextPhases(start = TimerPhase.Focus, skipTo = null))

        assertEquals(listOf("Stop sound", "Start focus"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun actionsBroadcastTheirOwnReceiverActionWithTimerIdAndOfferedPhase() {
        val notification = notification(afterFocus)

        val intents = notification.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(
            listOf(
                TimerAlarmReceiver.StopSoundAction,
                TimerAlarmReceiver.StartNextAction,
                TimerAlarmReceiver.SkipNextAction,
            ),
            intents.map { it.action },
        )
        assertEquals(listOf(null, TimerPhase.ShortBreak, TimerPhase.Focus), intents.map(TimerAlarmReceiver::offeredPhase))
        intents.forEach { intent ->
            assertEquals("t1", intent.getStringExtra(TimerAlarmReceiver.TimerIdExtra))
            assertEquals(TimerAlarmReceiver::class.java.name, intent.component?.className)
        }
        assertEquals(true, notification.actions.all { shadowOf(it.actionIntent).isBroadcast })
    }

    @Test
    fun completionNotificationStaysInsistent() {
        assertNotEquals(0, notification(afterFocus).flags and Notification.FLAG_INSISTENT)
    }

    private fun notification(next: CompletionNextPhases): Notification =
        SystemTimerCompletionNotifier.completionNotification(context, "t1", Uri.EMPTY, next)
}
