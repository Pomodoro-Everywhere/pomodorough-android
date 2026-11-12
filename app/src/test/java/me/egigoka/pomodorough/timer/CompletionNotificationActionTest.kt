package me.egigoka.pomodorough.timer

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompletionNotificationActionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val repository = RecordingRepository()
    private val stoppedAlerts = mutableListOf<String?>()
    private var cancelledNotifications = 0
    private val handler = CompletionNotificationActionHandler(
        repository = repository,
        stopAlert = { stoppedAlerts += it },
        cancelNotification = { cancelledNotifications += 1 },
    )

    @Test
    fun intentActionsResolveToNotificationButtons() {
        assertEquals(
            CompletionNotificationAction.StartNext,
            CompletionNotificationAction.fromIntentAction(TimerAlarmReceiver.StartNextAction),
        )
        assertEquals(
            CompletionNotificationAction.SkipNext,
            CompletionNotificationAction.fromIntentAction(TimerAlarmReceiver.SkipNextAction),
        )
        assertNull(CompletionNotificationAction.fromIntentAction(TimerAlarmReceiver.StopSoundAction))
        assertNull(CompletionNotificationAction.fromIntentAction(null))
    }

    @Test
    fun startNextTogglesTimerAfterStoppingAlert() = runTest {
        repository.mutableState.value = AppState(timer = timer(TimerStatus.Completed))

        val outcome = handler.handle(CompletionNotificationAction.StartNext, "t1")

        assertEquals(CompletionNotificationOutcome.Started, outcome)
        assertEquals(listOf("initialize", "toggleTimer"), repository.events)
        assertEquals(listOf<String?>("t1"), stoppedAlerts)
        assertEquals(1, cancelledNotifications)
    }

    @Test
    fun startNextIgnoresStaleNotificationWhileTimerRuns() = runTest {
        repository.mutableState.value = AppState(timer = timer(TimerStatus.Running))

        val outcome = handler.handle(CompletionNotificationAction.StartNext, "t1")

        assertEquals(CompletionNotificationOutcome.TimerAlreadyActive, outcome)
        assertEquals(listOf("initialize"), repository.events)
        assertEquals(1, cancelledNotifications)
    }

    @Test
    fun skipNextSelectsCoreDestinationPhase() = runTest {
        repository.mutableState.value = AppState(
            timer = timer(TimerStatus.Completed),
            settings = TimerSettings(selectedPhase = TimerPhase.ShortBreak),
            skipDestinationPhase = TimerPhase.Focus,
        )

        val outcome = handler.handle(CompletionNotificationAction.SkipNext, "t1")

        assertEquals(CompletionNotificationOutcome.PhaseSkipped, outcome)
        assertEquals(listOf("initialize", "selectPhase:${TimerPhase.Focus}"), repository.events)
        assertEquals(listOf<String?>("t1"), stoppedAlerts)
        assertEquals(1, cancelledNotifications)
    }

    @Test
    fun skipNextWithoutDestinationChangesNothing() = runTest {
        repository.mutableState.value = AppState(
            settings = TimerSettings(selectedPhase = TimerPhase.Focus),
            skipDestinationPhase = TimerPhase.Focus,
        )

        val outcome = handler.handle(CompletionNotificationAction.SkipNext, null)

        assertEquals(CompletionNotificationOutcome.NoSkipDestination, outcome)
        assertEquals(listOf("initialize"), repository.events)
        assertEquals(listOf<String?>(null), stoppedAlerts)
    }

    @Test
    fun receiverCancellerRemovesPostedCompletionNotification() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            SystemTimerCompletionNotifier.NotificationId,
            SystemTimerCompletionNotifier.completionNotification(context, "t1", android.net.Uri.EMPTY),
        )
        assertEquals(1, shadowOf(manager).size())
        val cancelling = CompletionNotificationActionHandler(
            repository = repository,
            stopAlert = {},
            cancelNotification = { SystemTimerCompletionNotifier.cancel(context) },
        )

        cancelling.handle(CompletionNotificationAction.StartNext, "t1")

        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun stopSoundIntentStaysOutsideCompletionActions() {
        val intent = Intent(TimerAlarmReceiver.StopSoundAction)
        assertEquals(true, TimerAlarmReceiver.isStopSoundAction(intent.action))
        assertNull(CompletionNotificationAction.fromIntentAction(intent.action))
    }

    private fun timer(status: String) = CanonicalTimer(
        id = "t1",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 60_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )
}

private class RecordingRepository : TimerRepositoryContract {
    val mutableState = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = mutableState
    val events = mutableListOf<String>()

    override suspend fun initialize() = record("initialize")
    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) = record("signIn")
    override suspend fun logout() = record("logout")
    override suspend fun deleteAccount(confirmation: String) = record("deleteAccount")
    override fun refresh() = record("refresh")
    override suspend fun toggleTimer() = record("toggleTimer")
    override suspend fun finishTimer() = record("finishTimer")
    override suspend fun cancelAndClearTimer() = record("cancelAndClearTimer")
    override suspend fun clearTimer() = record("clearTimer")
    override suspend fun selectPhase(phase: String) = record("selectPhase:$phase")
    override suspend fun changeDuration(phase: String, delta: Int) = record("changeDuration")
    override suspend fun setAutoStart(enabled: Boolean) = record("setAutoStart")
    override suspend fun selectTask(taskId: String?) = record("selectTask")
    override suspend fun addTask(title: String): Boolean {
        record("addTask")
        return true
    }
    override suspend fun deleteTask(taskId: String) = record("deleteTask")
    override suspend fun finishExpiredTimer(): Boolean {
        record("finishExpiredTimer")
        return false
    }
    override suspend fun resolveHistory(strategy: BootstrapStrategy) = record("resolveHistory")
    override suspend fun recoverCorruptedResolution() = record("recoverCorruptedResolution")
    override suspend fun confirmAccountSwitch() = record("confirmAccountSwitch")
    override suspend fun cancelAccountSwitch() = record("cancelAccountSwitch")
    override fun dismissConflict() = record("dismissConflict")
    override fun dismissNotice() = record("dismissNotice")
    override suspend fun setReplicationMode(mode: ReplicationMode) = record("setReplicationMode")
    override suspend fun createIrohRoom(name: String) = record("createIrohRoom")
    override suspend fun joinIrohRoom(invite: String) = record("joinIrohRoom")
    override suspend fun leaveIrohRoom() = record("leaveIrohRoom")
    override suspend fun refreshIrohInvite() = record("refreshIrohInvite")
    override suspend fun syncIrohNow() = record("syncIrohNow")
    override fun onForeground() = record("onForeground")
    override fun onBackground() = record("onBackground")

    private fun record(event: String) {
        events += event
    }
}
