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

    /** The core now proposes a phase other than the one the button named. */
    OfferedPhaseChanged,
}

/**
 * Performs the same repository calls as the in-app primary and Skip buttons.
 * The next phase is resolved here from published state. A button names the phase it
 * offered when the notification was posted; if the core now proposes another phase,
 * the tap changes nothing, so a stale notification cannot start or skip a phase the
 * core no longer proposes. An intent without an offered phase is checked only
 * against the current proposal.
 */
internal class CompletionNotificationActionHandler(
    private val repository: TimerRepositoryContract,
    private val stopAlert: (String?) -> Unit,
    private val cancelNotification: () -> Unit,
) {
    suspend fun handle(
        action: CompletionNotificationAction,
        timerId: String?,
        offeredPhase: String? = null,
    ): CompletionNotificationOutcome {
        repository.initialize()
        stopAlert(timerId)
        cancelNotification()
        return when (action) {
            CompletionNotificationAction.StartNext -> startNext(offeredPhase)
            CompletionNotificationAction.SkipNext -> skipNext(offeredPhase)
        }
    }

    private suspend fun startNext(offeredPhase: String?): CompletionNotificationOutcome {
        val state = repository.state.value
        val status = state.timer?.status
        if (status == TimerStatus.Running || status == TimerStatus.Paused) {
            Log.w(Tag, "Start next ignored: a timer is already active")
            return CompletionNotificationOutcome.TimerAlreadyActive
        }
        if (offeredPhase != null && offeredPhase != CompletionNextPhases.from(state).start) {
            Log.w(Tag, "Start next ignored: the core now proposes another phase")
            return CompletionNotificationOutcome.OfferedPhaseChanged
        }
        repository.toggleTimer()
        return CompletionNotificationOutcome.Started
    }

    private suspend fun skipNext(offeredPhase: String?): CompletionNotificationOutcome {
        val destination = CompletionNextPhases.from(repository.state.value).skipTo
        if (destination == null) {
            Log.w(Tag, "Skip next ignored: core proposes no skip destination")
            return CompletionNotificationOutcome.NoSkipDestination
        }
        if (offeredPhase != null && offeredPhase != destination) {
            Log.w(Tag, "Skip next ignored: the core now proposes another destination")
            return CompletionNotificationOutcome.OfferedPhaseChanged
        }
        repository.selectPhase(destination)
        return CompletionNotificationOutcome.PhaseSkipped
    }

    private companion object {
        const val Tag = "PomodoroughAlarm"
    }
}
