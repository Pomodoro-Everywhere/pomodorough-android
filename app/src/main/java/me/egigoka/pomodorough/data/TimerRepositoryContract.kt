package me.egigoka.pomodorough.data

import kotlinx.coroutines.flow.StateFlow
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider

interface TimerRepositoryContract {
    val state: StateFlow<AppState>

    suspend fun initialize()
    suspend fun signIn(credentialProvider: GoogleCredentialProvider)
    suspend fun logout()
    fun refresh()
    suspend fun toggleTimer()
    suspend fun finishTimer()
    suspend fun cancelAndClearTimer()
    suspend fun clearTimer()
    suspend fun selectPhase(phase: String)
    suspend fun changeDuration(phase: String, delta: Int)
    suspend fun setAutoStart(enabled: Boolean)
    suspend fun selectTask(taskId: String?)
    suspend fun addTask(title: String)
    suspend fun deleteTask(taskId: String)
    suspend fun finishExpiredTimer(): Boolean
    suspend fun resolveHistory(strategy: BootstrapStrategy)
    suspend fun recoverCorruptedResolution()
    suspend fun confirmAccountSwitch()
    suspend fun cancelAccountSwitch()
    fun dismissConflict()
    fun dismissNotice()
    fun onForeground()
    fun onBackground()
}
