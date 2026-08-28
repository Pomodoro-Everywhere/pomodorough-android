package me.egigoka.pomodorough.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerStatus

class TimerAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun update(timer: CanonicalTimer?) {
        if (timer?.status != TimerStatus.Running) {
            cancel()
            return
        }
        val anchor = runCatching { Instant.parse(timer.anchorAt).toEpochMilli() }.getOrNull()
        if (anchor == null) {
            cancel()
            return
        }
        val remainingAtAnchor = (timer.plannedDurationMs - timer.elapsedAtAnchorMs).coerceAtLeast(0)
        val triggerAt = anchor + remainingAtAnchor
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(timer.id),
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(timer.id),
            )
        }
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent(null))
    }

    private fun pendingIntent(timerId: String?): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        RequestCode,
        Intent(appContext, TimerAlarmReceiver::class.java).apply {
            timerId?.let { putExtra(TimerAlarmReceiver.TimerIdExtra, it) }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val RequestCode = 21
    }
}
