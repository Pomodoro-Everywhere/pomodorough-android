package me.egigoka.pomodorough.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode

private val NoPendingTaskDeletion: StateFlow<PendingTaskDeletion?> = MutableStateFlow(null)

interface TimerRepositoryContract {
    val state: StateFlow<AppState>

    suspend fun initialize()
    suspend fun signIn(credentialProvider: GoogleCredentialProvider)
    suspend fun logout()
    suspend fun resetLocalAccount() {
        throw UnsupportedOperationException(LocalAccountResetUnsupportedMessage)
    }
    suspend fun deleteAccount(confirmation: String)
    fun refresh()
    suspend fun toggleTimer()
    suspend fun finishTimer()
    suspend fun cancelAndClearTimer()
    suspend fun clearTimer()
    suspend fun selectPhase(phase: String)
    suspend fun changeDuration(phase: String, delta: Int)
    suspend fun setAutoStart(enabled: Boolean)
    suspend fun selectTask(taskId: String?)
    suspend fun addTask(title: String): Boolean
    suspend fun deleteTask(taskId: String)

    // UX round 2: the repository owns the undo window of a task delete. The
    // defaults keep implementations without a window compiling: they delete
    // at once and never have a pending deletion.
    val pendingTaskDeletion: StateFlow<PendingTaskDeletion?> get() = NoPendingTaskDeletion
    suspend fun requestTaskDeletion(taskId: String) = deleteTask(taskId)
    suspend fun undoTaskDeletion() = Unit
    suspend fun completePendingTaskDeletion() = Unit
    // Task identity hashes the title, so a rename replaces the record.
    // Default rejects so legacy implementations keep compiling.
    suspend fun renameTask(taskId: String, title: String) {
        throw UnsupportedOperationException("Task rename is not supported by this repository")
    }
    suspend fun finishExpiredTimer(): Boolean
    // R48-A01: unified once-only alert consumption shared by the
    // foreground ticker and the alarm receiver. Default rejects so
    // legacy implementations keep their behavior until they override.
    suspend fun consumeCompletionAlert(
        timerId: String,
        notify: suspend (timerId: String) -> Boolean,
    ): Boolean = false
    suspend fun resolveHistory(strategy: BootstrapStrategy)
    suspend fun recoverCorruptedResolution()
    suspend fun confirmAccountSwitch()
    suspend fun cancelAccountSwitch()
    fun dismissConflict()
    fun dismissNotice()
    suspend fun setReplicationMode(mode: ReplicationMode)
    suspend fun createIrohRoom(name: String)
    suspend fun joinIrohRoom(invite: String)
    suspend fun leaveIrohRoom()
    suspend fun refreshIrohInvite()
    suspend fun syncIrohNow()
    suspend fun confirmIrohIdentityRecovery() {
        throw UnsupportedOperationException("Iroh identity recovery is unavailable")
    }
    fun onForeground()
    fun onBackground()
}

/** Opt-in contract for repositories that must provide local-account reset. */
interface LocalAccountResetTimerRepositoryContract : TimerRepositoryContract {
    override suspend fun resetLocalAccount()
}

private const val LocalAccountResetUnsupportedMessage =
    "Local account reset is not supported by this repository"
