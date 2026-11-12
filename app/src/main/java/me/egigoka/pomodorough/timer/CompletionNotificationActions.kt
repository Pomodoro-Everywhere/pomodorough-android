package me.egigoka.pomodorough.timer

import android.util.Log
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.TimerStatus

/** Buttons on the phase-complete notification, keyed by their broadcast intent action. */
internal enum class CompletionNotificationAction(val intentAction: String) {
    StartNext(TimerAlarmReceiver.StartNextAction),
    SkipNext(TimerAlarmReceiver.SkipNextAction),
    ;

    companion object {
        fun fromIntentAction(action: String?): CompletionNotificationAction? =
            entries.firstOrNull { it.intentAction == action }
    }
}

internal enum class CompletionNotificationOutcome {
    Started,
    TimerAlreadyActive,
    PhaseSkipped,
    NoSkipDestination,
}

/**
 * Performs the same repository calls as the in-app primary and Skip buttons.
 * The next phase is resolved here from published state, never when the notification is built,
 * so a stale notification cannot start or skip a phase the core no longer proposes.
 */
internal class CompletionNotificationActionHandler(
    private val repository: TimerRepositoryContract,
    private val stopAlert: (String?) -> Unit,
    private val cancelNotification: () -> Unit,
) {
    suspend fun handle(
        action: CompletionNotificationAction,
        timerId: String?,
    ): CompletionNotificationOutcome {
        repository.initialize()
        stopAlert(timerId)
        cancelNotification()
        return when (action) {
            CompletionNotificationAction.StartNext -> startNext()
            CompletionNotificationAction.SkipNext -> skipNext()
        }
    }

    private suspend fun startNext(): CompletionNotificationOutcome {
        val status = repository.state.value.timer?.status
        if (status == TimerStatus.Running || status == TimerStatus.Paused) {
            Log.w(Tag, "Start next ignored: a timer is already active")
            return CompletionNotificationOutcome.TimerAlreadyActive
        }
        repository.toggleTimer()
        return CompletionNotificationOutcome.Started
    }

    private suspend fun skipNext(): CompletionNotificationOutcome {
        val state = repository.state.value
        val destination = state.skipDestinationPhase?.takeIf { it != state.settings.selectedPhase }
        if (destination == null) {
            Log.w(Tag, "Skip next ignored: core proposes no skip destination")
            return CompletionNotificationOutcome.NoSkipDestination
        }
        repository.selectPhase(destination)
        return CompletionNotificationOutcome.PhaseSkipped
    }

    private companion object {
        const val Tag = "PomodoroughAlarm"
    }
}
