package me.egigoka.pomodorough.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class PomodoroughViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initializesAndForwardsEveryUserAction() = runTest(mainDispatcherRule.dispatcher) {
        val repository = RecordingTimerRepository()
        val credentialProvider = object : GoogleCredentialProvider {
            override suspend fun identityToken(serverClientId: String, nonce: String) =
                "identity-token"
        }
        val viewModel = PomodoroughViewModel(repository)
        runCurrent()

        val signInJob = launch { viewModel.signIn(credentialProvider) }
        viewModel.logout()
        viewModel.refresh()
        viewModel.toggleTimer()
        viewModel.finishTimer()
        viewModel.cancelTimer()
        viewModel.clearTimer()
        viewModel.selectPhase("short_break")
        viewModel.changeDuration("focus", 5)
        viewModel.setAutoStart(true)
        viewModel.selectTask("task-1")
        viewModel.addTask("Write tests")
        viewModel.deleteTask("task-1")
        viewModel.resolveHistory(BootstrapStrategy.Merge)
        viewModel.recoverHistoryResolution()
        viewModel.confirmAccountSwitch()
        viewModel.cancelAccountSwitch()
        viewModel.dismissConflict()
        viewModel.dismissNotice()
        runCurrent()
        signInJob.join()

        assertSame(repository.state, viewModel.state)
        assertSame(credentialProvider, repository.credentialProvider)
        assertEquals("initialize", repository.events.first())
        assertEquals(
            setOf(
                "initialize",
                "signIn",
                "refresh",
                "dismissConflict",
                "dismissNotice",
                "logout",
                "toggleTimer",
                "finishTimer",
                "cancelAndClearTimer",
                "clearTimer",
                "selectPhase:short_break",
                "changeDuration:focus:5",
                "setAutoStart:true",
                "selectTask:task-1",
                "addTask:Write tests",
                "deleteTask:task-1",
                "resolveHistory:Merge",
                "recoverCorruptedResolution",
                "confirmAccountSwitch",
                "cancelAccountSwitch",
            ),
            repository.events.toSet(),
        )
        assertEquals(20, repository.events.size)
    }

    @Test
    fun foregroundPollsRunningTimerAndBackgroundStopsPolling() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingTimerRepository()
            val viewModel = PomodoroughViewModel(repository)
            runCurrent()
            repository.mutableState.value = AppState(timer = runningTimer())

            viewModel.onForeground()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val completionsBeforeBackground = repository.events.count { it == "finishExpiredTimer" }

            viewModel.onBackground()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, completionsBeforeBackground)
            assertEquals(
                completionsBeforeBackground,
                repository.events.count { it == "finishExpiredTimer" },
            )
            assertEquals(1, repository.events.count { it == "onForeground" })
            assertEquals(1, repository.events.count { it == "onBackground" })
        }

    @Test
    fun replacingForegroundLoopDoesNotDuplicatePolling() = runTest(mainDispatcherRule.dispatcher) {
        val repository = RecordingTimerRepository()
        val viewModel = PomodoroughViewModel(repository)
        runCurrent()
        repository.mutableState.value = AppState(timer = runningTimer())

        viewModel.onForeground()
        runCurrent()
        viewModel.onForeground()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, repository.events.count { it == "finishExpiredTimer" })
        viewModel.onBackground()
        runCurrent()
    }

    @Test
    fun successfulExpiryStopsPollingWithoutWaitingForStateEmission() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingTimerRepository().apply { finishExpiredResult = true }
            val viewModel = PomodoroughViewModel(repository)
            runCurrent()
            repository.mutableState.value = AppState(timer = runningTimer())

            viewModel.onForeground()
            runCurrent()
            advanceTimeBy(1_500)
            runCurrent()

            assertEquals(1, repository.events.count { it == "finishExpiredTimer" })
            viewModel.onBackground()
            runCurrent()
        }

    private fun runningTimer() = CanonicalTimer(
        id = "timer-1",
        phase = "focus",
        status = TimerStatus.Running,
        plannedDurationMs = 60_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class RecordingTimerRepository : TimerRepositoryContract {
    val mutableState = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = mutableState
    val events = mutableListOf<String>()
    var credentialProvider: GoogleCredentialProvider? = null
    var finishExpiredResult = false

    override suspend fun initialize() = record("initialize")
    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) {
        this.credentialProvider = credentialProvider
        record("signIn")
    }
    override suspend fun logout() = record("logout")
    override fun refresh() = record("refresh")
    override suspend fun toggleTimer() = record("toggleTimer")
    override suspend fun finishTimer() = record("finishTimer")
    override suspend fun cancelAndClearTimer() = record("cancelAndClearTimer")
    override suspend fun clearTimer() = record("clearTimer")
    override suspend fun selectPhase(phase: String) = record("selectPhase:$phase")
    override suspend fun changeDuration(phase: String, delta: Int) =
        record("changeDuration:$phase:$delta")
    override suspend fun setAutoStart(enabled: Boolean) = record("setAutoStart:$enabled")
    override suspend fun selectTask(taskId: String?) = record("selectTask:$taskId")
    override suspend fun addTask(title: String) = record("addTask:$title")
    override suspend fun deleteTask(taskId: String) = record("deleteTask:$taskId")
    override suspend fun finishExpiredTimer(): Boolean {
        record("finishExpiredTimer")
        return finishExpiredResult
    }
    override suspend fun resolveHistory(strategy: BootstrapStrategy) =
        record("resolveHistory:${strategy.name}")
    override suspend fun recoverCorruptedResolution() = record("recoverCorruptedResolution")
    override suspend fun confirmAccountSwitch() = record("confirmAccountSwitch")
    override suspend fun cancelAccountSwitch() = record("cancelAccountSwitch")
    override fun dismissConflict() = record("dismissConflict")
    override fun dismissNotice() = record("dismissNotice")
    override fun onForeground() = record("onForeground")
    override fun onBackground() = record("onBackground")

    private fun record(event: String) {
        events += event
    }
}
