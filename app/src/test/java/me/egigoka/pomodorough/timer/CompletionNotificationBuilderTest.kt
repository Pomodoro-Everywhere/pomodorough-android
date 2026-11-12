package me.egigoka.pomodorough.timer

import android.app.Application
import android.app.Notification
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
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

    @Test
    fun completionNotificationCarriesExactlyThreeLocalizedActions() {
        val notification = SystemTimerCompletionNotifier.completionNotification(context, "t1", Uri.EMPTY)

        val titles = notification.actions.map { it.title.toString() }
        assertEquals(
            listOf(
                context.getString(R.string.stop_sound),
                context.getString(R.string.start_next),
                context.getString(R.string.skip_next),
            ),
            titles,
        )
        assertEquals(listOf("Stop sound", "Start next", "Skip next"), titles)
    }

    @Test
    fun actionsBroadcastTheirOwnReceiverActionWithTimerId() {
        val notification = SystemTimerCompletionNotifier.completionNotification(context, "t1", Uri.EMPTY)

        val intents = notification.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(
            listOf(
                TimerAlarmReceiver.StopSoundAction,
                TimerAlarmReceiver.StartNextAction,
                TimerAlarmReceiver.SkipNextAction,
            ),
            intents.map { it.action },
        )
        intents.forEach { intent ->
            assertEquals("t1", intent.getStringExtra(TimerAlarmReceiver.TimerIdExtra))
            assertEquals(TimerAlarmReceiver::class.java.name, intent.component?.className)
        }
        assertEquals(true, notification.actions.all { shadowOf(it.actionIntent).isBroadcast })
    }

    @Test
    fun completionNotificationStaysInsistent() {
        val notification = SystemTimerCompletionNotifier.completionNotification(context, "t1", Uri.EMPTY)

        assertNotEquals(0, notification.flags and Notification.FLAG_INSISTENT)
    }
}
