package me.egigoka.pomodorough.timer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.MainActivity
import me.egigoka.pomodorough.PomodoroughApplication
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerStatus

internal fun interface ExpiredTimerCompleting {
    suspend fun finishExpiredTimer(): Boolean
}

internal fun interface TimerCompletionNotifying {
    suspend fun show(): Boolean
}

internal enum class TimerAlarmDeliveryResult {
    NotExpired,
    CompletedAndNotified,
    CompletedWithActiveReplacement,
    CompletedWithoutNotification,
}

internal class TimerAlarmDeliveryPolicy(
    private val completion: ExpiredTimerCompleting,
    private val notification: TimerCompletionNotifying,
    private val shouldNotify: () -> Boolean = { true },
) {
    suspend fun deliver(): TimerAlarmDeliveryResult {
        if (!completion.finishExpiredTimer()) return TimerAlarmDeliveryResult.NotExpired
        return try {
            if (!shouldNotify()) {
                TimerAlarmDeliveryResult.CompletedWithActiveReplacement
            } else if (notification.show()) {
                TimerAlarmDeliveryResult.CompletedAndNotified
            } else {
                TimerAlarmDeliveryResult.CompletedWithoutNotification
            }
        } catch (_: Exception) {
            TimerAlarmDeliveryResult.CompletedWithoutNotification
        }
    }
}

internal fun shouldStopCompletionAlert(
    alertTimerID: String?,
    nextTimer: CanonicalTimer?,
): Boolean {
    return alertTimerID != null &&
        (nextTimer == null || nextTimer.id != alertTimerID || nextTimer.status != TimerStatus.Completed)
}

internal fun shouldPostCompletionAlert(timer: CanonicalTimer?): Boolean {
    return timer?.status != TimerStatus.Running && timer?.status != TimerStatus.Paused
}

internal class SystemTimerCompletionNotifier(
    context: Context,
    private val timerId: String,
) : TimerCompletionNotifying {
    private val appContext = context.applicationContext

    override suspend fun show(): Boolean {
        if (!canPostNotification(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        ) return false

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                appContext.getString(R.string.timer_complete_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopSoundIntent = PendingIntent.getBroadcast(
            appContext,
            1,
            Intent(appContext, TimerAlarmReceiver::class.java).setAction(
                TimerAlarmReceiver.StopSoundAction,
            ).putExtra(TimerAlarmReceiver.TimerIdExtra, timerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, ChannelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(appContext.getString(R.string.timer_complete_title))
            .setContentText(appContext.getString(R.string.timer_complete_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .addAction(0, appContext.getString(R.string.stop_sound), stopSoundIntent)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        manager.notify(NotificationId, notification)
        return true
    }

    companion object {
        internal const val ChannelId = "timer-arrivals"
        internal const val NotificationId = 25

        internal fun cancel(context: Context) {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                .cancel(NotificationId)
        }

        internal fun canPostNotification(sdkInt: Int, permissionGranted: Boolean): Boolean {
            return sdkInt < 33 || permissionGranted
        }
    }
}

internal suspend fun completeMatchingExpiredTimer(
    expectedTimerId: String,
    initialize: suspend () -> Unit,
    currentTimerId: () -> String?,
    finish: suspend () -> Boolean,
): Boolean {
    initialize()
    return if (currentTimerId() == expectedTimerId) finish() else false
}

internal suspend fun deliverTimerAlarm(
    repository: TimerRepository,
    timerId: String,
    notifier: TimerCompletionNotifying,
): TimerAlarmDeliveryResult = TimerAlarmDeliveryPolicy(
    completion = ExpiredTimerCompleting { repository.finishExpiredTimer(timerId) },
    notification = TimerCompletionNotifying {
        repository.showCompletionAlert(timerId) { notifier.show() }
    },
    shouldNotify = { shouldPostCompletionAlert(repository.state.value.timer) },
).deliver()

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (isStopSoundAction(intent.action)) {
            val application = context.applicationContext as PomodoroughApplication
            application.timerRepository.stopCompletionAlert(timerId(intent))
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as PomodoroughApplication
                val timerId = timerId(intent) ?: application.timerRepository.state.value.timer?.id
                if (timerId == null) return@launch
                val result = deliverTimerAlarm(
                    repository = application.timerRepository,
                    timerId = timerId,
                    notifier = SystemTimerCompletionNotifier(context, timerId),
                )
                if (result == TimerAlarmDeliveryResult.CompletedWithoutNotification) {
                    Log.w(Tag, "Timer completed without posting a notification")
                }
            } catch (error: Exception) {
                Log.e(Tag, "Could not finish expired timer", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal const val StopSoundAction = "me.egigoka.pomodorough.STOP_TIMER_SOUND"
        internal const val TimerIdExtra = "me.egigoka.pomodorough.TIMER_ID"
        private const val Tag = "PomodoroughAlarm"

        internal fun isStopSoundAction(action: String?): Boolean = action == StopSoundAction
        internal fun timerId(intent: Intent): String? = intent.getStringExtra(TimerIdExtra)
    }
}
