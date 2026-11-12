package me.egigoka.pomodorough.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.PomodoroughApplication
import me.egigoka.pomodorough.crash.CrashReporter

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as PomodoroughApplication
                application.timerRepository.rescheduleAlarmFromLocal()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // R54-A08: a failed reschedule must not crash the boot broadcast; the next
                // local initialization schedules the alarm again.
                Log.e(Tag, "Could not reschedule the timer alarm after boot", error)
                CrashReporter.report(error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val Tag = "PomodoroughBoot"
    }
}
