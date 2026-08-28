package me.egigoka.pomodorough.data

import kotlinx.coroutines.flow.StateFlow
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode

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
    suspend fun finishExpiredTimer(): Boolean
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
    fun onForeground()
    fun onBackground()
}

/** Opt-in contract for repositories that must provide local-account reset. */
interface LocalAccountResetTimerRepositoryContract : TimerRepositoryContract {
    override suspend fun resetLocalAccount()
}

private const val LocalAccountResetUnsupportedMessage =
    "Local account reset is not supported by this repository"
