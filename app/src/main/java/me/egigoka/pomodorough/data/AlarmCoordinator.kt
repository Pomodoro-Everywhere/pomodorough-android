package me.egigoka.pomodorough.data

import android.content.SharedPreferences
import me.egigoka.pomodorough.timer.TimerAlarmScheduler

internal sealed interface AlarmCoordinatorEvent {
    data class CompletionAlertChanged(val timerId: String?) : AlarmCoordinatorEvent
}

internal fun interface AlarmCoordinatorEventSink {
    fun emit(event: AlarmCoordinatorEvent)
}

internal interface AlarmSchedulerPort {
    fun update(timer: CanonicalTimer?)
    fun cancel()
}

internal interface CompletionAlertStore {
    fun load(): String?
    fun save(timerId: String?)
}

internal fun interface CompletionNotificationCanceller {
    fun cancel()
}

internal fun interface CompletionAlertPolicy {
    fun shouldStop(alertTimerId: String?, timer: CanonicalTimer?): Boolean
}

internal sealed interface AlarmTransition {
    data class Scheduled(val timer: CanonicalTimer?) : AlarmTransition
    data object Cancelled : AlarmTransition
    data class CompletionAlert(val timerId: String?, val changed: Boolean) : AlarmTransition
}

internal class AlarmCoordinator(
    private val scheduler: AlarmSchedulerPort,
    private val alertStore: CompletionAlertStore,
    private val notificationCanceller: CompletionNotificationCanceller,
    private val completionAlertPolicy: CompletionAlertPolicy,
    private val eventSink: AlarmCoordinatorEventSink,
) {
    private val completionAlertLock = Any()
    @Volatile private var currentCompletionAlertTimerId = alertStore.load()

    val completionAlertTimerId: String? get() = currentCompletionAlertTimerId

    fun schedule(timer: CanonicalTimer?, ownedTimerId: String?): AlarmTransition.Scheduled {
        val eligibleTimer = timer?.takeIf { it.id == ownedTimerId }
        scheduler.update(eligibleTimer)
        return AlarmTransition.Scheduled(eligibleTimer)
    }

    fun cancelAlarm(): AlarmTransition.Cancelled {
        scheduler.cancel()
        return AlarmTransition.Cancelled
    }

    fun cancelForAccountClear() {
        cancelAlarm()
        stopCompletionAlert(currentCompletionAlertTimerId)
    }

    fun markCompletionAlert(timerId: String): AlarmTransition.CompletionAlert {
        synchronized(completionAlertLock) {
            currentCompletionAlertTimerId = timerId
            alertStore.save(timerId)
            emitCompletionAlert(timerId)
        }
        return AlarmTransition.CompletionAlert(timerId, changed = true)
    }

    fun stopCompletionAlert(timerId: String?): AlarmTransition.CompletionAlert {
        if (timerId == null) return AlarmTransition.CompletionAlert(null, changed = false)
        val cleared = synchronized(completionAlertLock) {
            if (currentCompletionAlertTimerId != timerId) return@synchronized false
            currentCompletionAlertTimerId = null
            alertStore.save(null)
            emitCompletionAlert(null)
            true
        }
        if (!cleared) return AlarmTransition.CompletionAlert(timerId, changed = false)
        notificationCanceller.cancel()
        return AlarmTransition.CompletionAlert(null, changed = true)
    }

    fun reconcileCompletionAlert(timer: CanonicalTimer?): AlarmTransition.CompletionAlert {
        val timerId = currentCompletionAlertTimerId
        if (!completionAlertPolicy.shouldStop(timerId, timer)) {
            return AlarmTransition.CompletionAlert(timerId, changed = false)
        }
        return stopCompletionAlert(timerId)
    }

    private fun emitCompletionAlert(timerId: String?) {
        eventSink.emit(AlarmCoordinatorEvent.CompletionAlertChanged(timerId))
    }
}

internal class TimerAlarmSchedulerPort(
    private val scheduler: TimerAlarmScheduler,
) : AlarmSchedulerPort {
    override fun update(timer: CanonicalTimer?) = scheduler.update(timer)
    override fun cancel() = scheduler.cancel()
}

internal class SharedPreferencesCompletionAlertStore(
    private val preferences: SharedPreferences,
    private val key: String,
) : CompletionAlertStore {
    override fun load(): String? = preferences.getString(key, null)

    override fun save(timerId: String?) {
        preferences.edit().apply {
            if (timerId == null) remove(key) else putString(key, timerId)
        }.commit()
    }
}
