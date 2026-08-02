package me.egigoka.pomodorough.timer

import android.Manifest
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

internal fun interface ExpiredTimerCompleting {
    suspend fun finishExpiredTimer(): Boolean
}

internal fun interface TimerCompletionNotifying {
    fun show(): Boolean
}

internal enum class TimerAlarmDeliveryResult {
    NotExpired,
    CompletedAndNotified,
    CompletedWithoutNotification,
}

internal class TimerAlarmDeliveryPolicy(
    private val completion: ExpiredTimerCompleting,
    private val notification: TimerCompletionNotifying,
) {
    suspend fun deliver(): TimerAlarmDeliveryResult {
        if (!completion.finishExpiredTimer()) return TimerAlarmDeliveryResult.NotExpired
        return try {
            if (notification.show()) {
                TimerAlarmDeliveryResult.CompletedAndNotified
            } else {
                TimerAlarmDeliveryResult.CompletedWithoutNotification
            }
        } catch (_: Exception) {
            TimerAlarmDeliveryResult.CompletedWithoutNotification
        }
    }
}

internal class SystemTimerCompletionNotifier(
    context: Context,
) : TimerCompletionNotifying {
    private val appContext = context.applicationContext

    override fun show(): Boolean {
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
        val notification = NotificationCompat.Builder(appContext, ChannelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(appContext.getString(R.string.timer_complete_title))
            .setContentText("Timer arrived. Open Pomodorough for the next service.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(NotificationId, notification)
        return true
    }

    companion object {
        internal const val ChannelId = "timer-arrivals"
        internal const val NotificationId = 25

        internal fun canPostNotification(sdkInt: Int, permissionGranted: Boolean): Boolean =
            sdkInt < 33 || permissionGranted
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as PomodoroughApplication
                val result = TimerAlarmDeliveryPolicy(
                    completion = ExpiredTimerCompleting(
                        application.timerRepository::finishExpiredTimer,
                    ),
                    notification = SystemTimerCompletionNotifier(context),
                ).deliver()
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

    private companion object {
        const val Tag = "PomodoroughAlarm"
    }
}
