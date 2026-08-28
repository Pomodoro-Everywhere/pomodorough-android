package external.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.LocalAccountResetTimerRepositoryContract
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRepositoryContractCompatibilityTest {
    @Test
    fun legacyImplementationCompilesAndRejectsUnsupportedLocalReset() = runTest {
        val repository: TimerRepositoryContract = LegacyTimerRepository()

        val failure = runCatching { repository.resetLocalAccount() }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertEquals("Local account reset is not supported by this repository", failure?.message)
    }

    @Test
    fun resetCapabilityRequiresAndUsesExplicitImplementation() = runTest {
        val repository = ResetCapableRepository()

        repository.resetLocalAccount()

        assertTrue(repository.resetCalled)
    }
}

/** Minimal external implementation matching TimerRepositoryContract before local reset existed. */
private open class LegacyTimerRepository : TimerRepositoryContract {
    override val state: StateFlow<AppState> = MutableStateFlow(AppState())

    override suspend fun initialize() = Unit
    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) = Unit
    override suspend fun logout() = Unit
    override suspend fun deleteAccount(confirmation: String) = Unit
    override fun refresh() = Unit
    override suspend fun toggleTimer() = Unit
    override suspend fun finishTimer() = Unit
    override suspend fun cancelAndClearTimer() = Unit
    override suspend fun clearTimer() = Unit
    override suspend fun selectPhase(phase: String) = Unit
    override suspend fun changeDuration(phase: String, delta: Int) = Unit
    override suspend fun setAutoStart(enabled: Boolean) = Unit
    override suspend fun selectTask(taskId: String?) = Unit
    override suspend fun addTask(title: String) = false
    override suspend fun deleteTask(taskId: String) = Unit
    override suspend fun finishExpiredTimer() = false
    override suspend fun resolveHistory(strategy: BootstrapStrategy) = Unit
    override suspend fun recoverCorruptedResolution() = Unit
    override suspend fun confirmAccountSwitch() = Unit
    override suspend fun cancelAccountSwitch() = Unit
    override fun dismissConflict() = Unit
    override fun dismissNotice() = Unit
    override suspend fun setReplicationMode(mode: ReplicationMode) = Unit
    override suspend fun createIrohRoom(name: String) = Unit
    override suspend fun joinIrohRoom(invite: String) = Unit
    override suspend fun leaveIrohRoom() = Unit
    override suspend fun refreshIrohInvite() = Unit
    override suspend fun syncIrohNow() = Unit
    override fun onForeground() = Unit
    override fun onBackground() = Unit
}

private class ResetCapableRepository :
    LegacyTimerRepository(),
    LocalAccountResetTimerRepositoryContract {
    var resetCalled = false

    override suspend fun resetLocalAccount() {
        resetCalled = true
    }
}
