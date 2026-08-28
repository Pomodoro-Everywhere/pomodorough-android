package me.egigoka.pomodorough.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider

class PomodoroughViewModel(
    private val repository: TimerRepositoryContract,
) : ViewModel() {
    val state = repository.state
    private var timerTickJob: Job? = null

    init {
        viewModelScope.launch { repository.initialize() }
    }

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
    fun addTask(title: String, onResult: (Boolean) -> Unit) = launch {
        onResult(repository.addTask(title))
    }
    fun deleteTask(taskId: String) = launch { repository.deleteTask(taskId) }
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
                    if (repository.finishExpiredTimer()) return@collectLatest
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

    class Factory(private val repository: TimerRepositoryContract) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PomodoroughViewModel(repository) as T
        }
    }
}
