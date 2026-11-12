package me.egigoka.pomodorough.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.PendingTaskDeletion
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider

class PomodoroughViewModel(
    private val repository: TimerRepositoryContract,
    // R48-A01: foreground expiry delivers its alert through the same
    // shared coordinator as the alarm receiver. Null keeps legacy
    // fakes silent; production wires the system completion notifier.
    private val completionAlert: (suspend (timerId: String) -> Boolean)? = null,
) : ViewModel() {
    val state = repository.state
    private var timerTickJob: Job? = null

    // R43-A07: sensitive invite text is memory-only. It survives tab
    // switches and config recreation in ViewModel memory, but must not
    // survive process recreation, so it is never in SavedState. Invites
    // grant full read/write access and reveal peer IPs.
    var joinInviteDraft by mutableStateOf("")
        private set

    fun updateJoinInviteDraft(value: String) {
        joinInviteDraft = value
    }

    fun clearJoinInviteDraft() {
        joinInviteDraft = ""
    }

    init {
        viewModelScope.launch { repository.initialize() }
    }

    // R54-A02: a startup storage-read failure leaves initialization open; this reads again.
    fun retryStartup() = launch { repository.initialize() }

    suspend fun signIn(credentialProvider: GoogleCredentialProvider) {
        repository.signIn(credentialProvider)
    }
    fun logout() = launch { repository.logout() }
    fun resetLocalAccount() = launch { repository.resetLocalAccount() }
    fun deleteAccount(confirmation: String) = launch { repository.deleteAccount(confirmation) }
    fun refresh() = repository.refresh()
    fun toggleTimer() = launch { repository.toggleTimer() }
    fun finishTimer() = launch { repository.finishTimer() }
    fun cancelTimer() = launch { repository.cancelAndClearTimer() }
    fun clearTimer() = launch { repository.clearTimer() }
    fun selectPhase(phase: String) = launch { repository.selectPhase(phase) }
    fun changeDuration(phase: String, delta: Int) = launch {
        repository.changeDuration(phase, delta)
    }
    fun setAutoStart(enabled: Boolean) = launch { repository.setAutoStart(enabled) }
    fun selectTask(taskId: String?) = launch { repository.selectTask(taskId) }
    fun addTask(title: String, onResult: (Boolean) -> Unit) {
        launch { onResult(repository.addTask(title)) }
    }
    fun deleteTask(taskId: String) = launch { repository.deleteTask(taskId) }

    // The task board defers a delete until the undo snackbar settles, so the
    // row hides while nothing has left the workspace yet. At most one deletion
    // waits: a second request flushes the first. deleteTask stays immediate
    // for callers that need the repository write right away.
    // UX round 2: the repository owns the pending deletion, so the tile and the
    // notification actions see it too. This mirrors it for the task board.
    var pendingTaskDeletion by mutableStateOf<PendingTaskDeletion?>(null)
        private set

    init {
        viewModelScope.launch { repository.pendingTaskDeletion.collect { pendingTaskDeletion = it } }
    }

    fun requestTaskDeletion(taskId: String) {
        if (editingTaskId == taskId) editingTaskId = null
        launch { repository.requestTaskDeletion(taskId) }
    }

    fun undoTaskDeletion() = launch { repository.undoTaskDeletion() }

    // Only the snackbar for the still-pending deletion may confirm it; a
    // late dismissal from a replaced snackbar must not delete the newer task.
    fun confirmTaskDeletion(taskId: String) {
        if (pendingTaskDeletion?.taskId == taskId) flushTaskDeletion()
    }

    // UX round 2, B4: rotation and other configuration changes stop the
    // activity but bring the screen straight back with this view model, so
    // the undo window stays open. Only a real stop commits the delete.
    fun endUndoWindowOnStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) flushTaskDeletion()
    }

    fun flushTaskDeletion() = launch { repository.completePendingTaskDeletion() }

    var editingTaskId by mutableStateOf<String?>(null)
        private set

    fun beginTaskRename(taskId: String) {
        editingTaskId = taskId
    }

    fun cancelTaskRename() {
        editingTaskId = null
    }

    fun renameTask(taskId: String, title: String) {
        editingTaskId = null
        launch { repository.renameTask(taskId, title) }
    }
    fun resolveHistory(strategy: BootstrapStrategy) = launch { repository.resolveHistory(strategy) }
    fun recoverHistoryResolution() = launch { repository.recoverCorruptedResolution() }
    fun confirmAccountSwitch() = launch { repository.confirmAccountSwitch() }
    fun cancelAccountSwitch() = launch { repository.cancelAccountSwitch() }
    fun dismissConflict() = repository.dismissConflict()
    fun dismissNotice() = repository.dismissNotice()
    fun setReplicationMode(mode: ReplicationMode) = launch { repository.setReplicationMode(mode) }
    fun createIrohRoom(name: String) = launch { repository.createIrohRoom(name) }
    fun joinIrohRoom(invite: String) = launch { repository.joinIrohRoom(invite) }
    fun leaveIrohRoom() = launch { repository.leaveIrohRoom() }
    fun refreshIrohInvite() = launch { repository.refreshIrohInvite() }
    fun syncIrohNow() = launch { repository.syncIrohNow() }
    fun confirmIrohIdentityRecovery() = launch { repository.confirmIrohIdentityRecovery() }
    fun onForeground() {
        repository.onForeground()
        timerTickJob?.cancel()
        timerTickJob = viewModelScope.launch {
            state.map { appState ->
                appState.timer?.takeIf { it.status == TimerStatus.Running }?.id
            }.distinctUntilChanged().collectLatest { timerId ->
                if (timerId == null) return@collectLatest
                while (isActive) {
                    delay(500)
                    if (finishExpiredTimerAndAlert(timerId)) return@collectLatest
                }
            }
        }
    }

    fun onBackground() {
        timerTickJob?.cancel()
        timerTickJob = null
        repository.onBackground()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // R54-A17: the finish publishes the completed timer before it returns, and
    // collectLatest cancels this tick for that change. The finish has already
    // cancelled the exact alarm by then, so the tick is the only alert left:
    // the finish and its alert run to the end.
    private suspend fun finishExpiredTimerAndAlert(timerId: String): Boolean =
        withContext(NonCancellable) {
            repository.finishExpiredTimer().also { finished ->
                if (finished) deliverCompletionAlert(timerId)
            }
        }

    private suspend fun deliverCompletionAlert(timerId: String) {
        val notify = completionAlert ?: return
        try {
            repository.consumeCompletionAlert(timerId, notify)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // expected-silent: completion is durable; a failed alert
            // must not crash the foreground ticker loop.
        }
    }

    class Factory(
        private val repository: TimerRepositoryContract,
        private val completionAlert: (suspend (timerId: String) -> Boolean)? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PomodoroughViewModel(repository, completionAlert) as T
        }
    }
}
