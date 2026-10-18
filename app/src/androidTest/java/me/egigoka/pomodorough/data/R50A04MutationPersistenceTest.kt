package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R50-A04 with real Room: ordinary mutation writes must not escape to
 * the ViewModel uncaught handler. Covers first-write and final-tx for
 * timer, task, duration, auto-start, and selection-only, plus retry,
 * cancellation, and ViewModel actions.
 */
@RunWith(AndroidJUnit4::class)
class R50A04MutationPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        MutationPersistenceReporter.resetForTest()
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        CrashReporter.delegate = previousDelegate ?: CrashReporter.delegate
        CrashReportingRuntime.reportingEnabled = previousEnabled
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun durationFirstWriteViaViewModelRetainsState() = runBlocking {
        val reported = capture()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val before = snapshot(dao.delegate)
        val uncaught = captureUncaught()
        try {
            dao.fail = "duration-first"
            viewModel(repository).changeDuration(TimerPhase.Focus, 1)
            awaitNotice(repository)
        } finally {
            restoreUncaught(uncaught)
        }
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(storageNotice(), repository.state.value.notice)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is MutationPersistenceReport)
        assertTrue(uncaught.errors.isEmpty())
    }

    @Test
    fun durationFinalTxViaViewModelRetainsState() = runBlocking {
        val reported = capture()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val before = snapshot(dao.delegate)
        dao.fail = "duration-final"
        viewModel(repository).changeDuration(TimerPhase.Focus, 1)
        awaitNotice(repository)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(storageNotice(), repository.state.value.notice)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is MutationPersistenceReport)
    }

    @Test
    fun autoStartFinalTxRetainsStateAndClearsOnRetry() = runBlocking {
        quiet()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val before = snapshot(dao.delegate)
        dao.fail = "autostart-final"
        viewModel(repository).setAutoStart(true)
        awaitNotice(repository)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(false, repository.state.value.settings.autoStartBreaks)
        dao.fail = null
        viewModel(repository).setAutoStart(true)
        awaitSetting(repository, true)
        assertNull(repository.state.value.notice)
        assertEquals(
            true,
            json.decodeFromString<TimerSettings>(snapshot(dao.delegate).local.settingsJson).autoStartBreaks,
        )
    }

    @Test
    fun selectionOnlyFirstAndFinalTxRetainState() = runBlocking {
        quiet()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        repository.addTask("Select A")
        repository.addTask("Select B")
        val taskA = repository.state.value.tasks.first { it.title == "Select A" }
        val before = snapshot(dao.delegate)
        dao.fail = "selection-first"
        viewModel(repository).selectTask(taskA.id)
        awaitNotice(repository)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        dao.fail = null
        dao.fail = "selection-final"
        viewModel(repository).selectTask(taskA.id)
        awaitNotice(repository)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        dao.fail = null
        viewModel(repository).selectTask(taskA.id)
        awaitSelection(repository, taskA.id)
        assertEquals(taskA.id, snapshot(dao.delegate).local.selectedTaskId)
    }

    @Test
    fun timerFinalTxViaViewModelRetainsState() = runBlocking {
        quiet()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val before = snapshot(dao.delegate)
        dao.fail = "timer-final"
        viewModel(repository).toggleTimer()
        awaitNotice(repository)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertNull(repository.state.value.timer)
        assertTrue(dao.delegate.pendingCommands().isEmpty())
        dao.fail = null
        viewModel(repository).toggleTimer()
        awaitTimer(repository)
        assertEquals(1, dao.delegate.pendingCommands().size)
    }

    @Test
    fun taskFirstWriteReturnsRefusalViaViewModel() = runBlocking {
        quiet()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val before = snapshot(dao.delegate)
        dao.fail = "task-first"
        var callback: Boolean? = null
        viewModel(repository).addTask("Failing task") { callback = it }
        awaitNotice(repository)
        assertEquals(false, callback)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(storageNotice(), repository.state.value.notice)
        dao.fail = null
        var retry: Boolean? = null
        viewModel(repository).addTask("Failing task") { retry = it }
        awaitTask(repository, "Failing task")
        assertEquals(true, retry)
    }

    @Test
    fun distinctFailuresStillReportOnceTotal() = runBlocking {
        val reported = capture()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        dao.fail = "duration-first"
        repository.changeDuration(TimerPhase.Focus, 1)
        awaitNotice(repository)
        dao.fail = "timer-final"
        repository.toggleTimer()
        awaitNotice(repository)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is MutationPersistenceReport)
    }

    @Test
    fun retryAfterFailurePersistsAndReopenRestores() = runBlocking {
        quiet()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        dao.fail = "duration-final"
        repository.changeDuration(TimerPhase.Focus, 1)
        awaitNotice(repository)
        dao.fail = null
        repository.changeDuration(TimerPhase.Focus, 1)
        awaitDuration(repository, 1_560_000L)
        repository.shutdownForTest()
        val reopened = repository(dao)
        reopened.initialize()
        awaitSignedIn(reopened)
        assertEquals(1_560_000L, reopened.state.value.settings.durationMsFor(TimerPhase.Focus))
    }

    @Test
    fun cancellationPropagatesWithoutReportOrNotice() = runBlocking {
        val reported = capture()
        val dao = failingDao()
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        dao.fail = "cancel-duration"
        var thrown: Throwable? = null
        try {
            repository.changeDuration(TimerPhase.Focus, 1)
        } catch (error: CancellationException) {
            thrown = error
        }
        assertTrue(thrown is CancellationException)
        assertTrue(reported.isEmpty())
        assertNull(repository.state.value.notice)
    }

    private fun failingDao() = FailingMutationDao(database, database.timerDao())

    private fun viewModel(repository: TimerRepository) = PomodoroughViewModel(repository)

    private fun capture(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        MutationPersistenceReporter.resetForTest()
        return reported
    }

    private fun quiet() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        MutationPersistenceReporter.resetForTest()
    }

    private fun storageNotice(): String = context.getString(R.string.sync_stopped_after_a_local_failure)

    private suspend fun awaitSignedIn(repository: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private suspend fun awaitNotice(repository: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.notice == null) delay(10)
            true
        }
        assertTrue("timed out waiting for notice", done == true)
    }

    private suspend fun awaitSetting(repository: TimerRepository, enabled: Boolean) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.settings.autoStartBreaks != enabled) delay(10)
            true
        }
        assertTrue("timed out waiting for auto-start", done == true)
    }

    private suspend fun awaitSelection(repository: TimerRepository, id: String) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.selectedTaskId != id) delay(10)
            true
        }
        assertTrue("timed out waiting for selection", done == true)
    }

    private suspend fun awaitTimer(repository: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.timer == null) delay(10)
            true
        }
        assertTrue("timed out waiting for timer", done == true)
    }

    private suspend fun awaitTask(repository: TimerRepository, title: String) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.tasks.none { it.title == title }) delay(10)
            true
        }
        assertTrue("timed out waiting for task", done == true)
    }

    private suspend fun awaitDuration(repository: TimerRepository, focusMs: Long) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.settings.durationMsFor(TimerPhase.Focus) != focusMs) delay(10)
            true
        }
        assertTrue("timed out waiting for duration", done == true)
    }

    private fun snapshot(dao: TimerDao): Durable = runBlocking {
        Durable(
            local = requireNotNull(dao.localState()),
            commands = dao.pendingCommands().map { it.id },
            durations = dao.pendingDurationOperations().map { it.id },
            autoStart = dao.pendingAutoStartOperations().map { it.id },
            selected = dao.pendingSelectedTaskOperations().map { it.id },
            tasks = dao.pendingTaskOperations().map { it.id },
        )
    }

    private fun assertDurableUnchanged(before: Durable, after: Durable) {
        assertEquals(before.local, after.local)
        assertEquals(before.commands, after.commands)
        assertEquals(before.durations, after.durations)
        assertEquals(before.autoStart, after.autoStart)
        assertEquals(before.selected, after.selected)
        assertEquals(before.tasks, after.tasks)
    }

    private fun repository(dao: TimerDao): TimerRepository {
        val account = me.egigoka.pomodorough.data.User("account-1", "a1@example.test", "A", "")
        runBlocking {
            if (database.timerDao().localState() == null) {
                database.timerDao().insertState(
                    LocalStateEntity(
                        deviceId = "device-1",
                        settingsJson = json.encodeToString(TimerSettings()),
                        userJson = json.encodeToString(account),
                        ownerUserId = account.id,
                    ),
                )
            }
        }
        return TimerRepository(
            context = context,
            dao = dao,
            api = FakeService(account),
            auth = FakeAuth(),
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private data class Durable(
        val local: LocalStateEntity,
        val commands: List<String>,
        val durations: List<String>,
        val autoStart: List<String>,
        val selected: List<String>,
        val tasks: List<String>,
    )

    private data class Uncaught(val errors: MutableList<Throwable>, val previous: Thread.UncaughtExceptionHandler?)

    private fun captureUncaught(): Uncaught {
        val errors = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> errors += error }
        return Uncaught(errors, previous)
    }

    private fun restoreUncaught(captured: Uncaught) {
        Thread.setDefaultUncaughtExceptionHandler(captured.previous)
    }

    private class FailingMutationDao(
        val db: PomodoroughDatabase,
        val delegate: TimerDao,
    ) : TimerDao by delegate {
        var fail: String? = null

        override suspend fun persistDurationOperation(
            operation: me.egigoka.pomodorough.data.local.PendingDurationOperationEntity,
            state: LocalStateEntity,
        ) {
            when (fail) {
                "duration-first" -> throw IOException("duration first-write failed")
                "duration-final" -> db.withTransaction {
                    delegate.upsertDurationOperation(operation)
                    throw IOException("duration final-txn failed")
                }
                "cancel-duration" -> throw CancellationException("duration gone")
                else -> delegate.persistDurationOperation(operation, state)
            }
        }

        override suspend fun persistAutoStartOperation(
            operation: me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity,
            state: LocalStateEntity,
        ) {
            when (fail) {
                "autostart-first" -> throw IOException("autostart first-write failed")
                "autostart-final" -> db.withTransaction {
                    delegate.insertAutoStartOperation(operation)
                    throw IOException("autostart final-txn failed")
                }
                else -> delegate.persistAutoStartOperation(operation, state)
            }
        }

        override suspend fun persistSelectedTaskOperation(
            operation: me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity,
            state: LocalStateEntity,
        ) {
            when (fail) {
                "selection-first" -> throw IOException("selection first-write failed")
                "selection-final" -> db.withTransaction {
                    delegate.insertSelectedTaskOperation(operation)
                    throw IOException("selection final-txn failed")
                }
                else -> delegate.persistSelectedTaskOperation(operation, state)
            }
        }

        override suspend fun persistCommand(
            command: PendingCommandEntity,
            state: LocalStateEntity,
        ) {
            when (fail) {
                "timer-first" -> throw IOException("timer first-write failed")
                "timer-final" -> db.withTransaction {
                    delegate.insertCommand(command)
                    throw IOException("timer final-txn failed")
                }
                else -> delegate.persistCommand(command, state)
            }
        }

        override suspend fun persistCommands(
            commands: List<PendingCommandEntity>,
            state: LocalStateEntity,
        ) {
            if (fail == "timer-final") {
                db.withTransaction {
                    delegate.insertCommands(commands)
                    throw IOException("timer batch final-txn failed")
                }
            } else {
                delegate.persistCommands(commands, state)
            }
        }

        override suspend fun persistTaskOperation(
            operation: me.egigoka.pomodorough.data.local.PendingTaskOperationEntity,
            state: LocalStateEntity,
            selectedTaskOperation: me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity?,
        ) {
            when (fail) {
                "task-first" -> throw IOException("task first-write failed")
                "task-final" -> db.withTransaction {
                    delegate.insertTaskOperation(operation)
                    throw IOException("task final-txn failed")
                }
                else -> delegate.persistTaskOperation(operation, state, selectedTaskOperation)
            }
        }
    }

    private class FakeAuth : AuthSession {
        override suspend fun signIn(
            credentialProvider: GoogleCredentialProvider,
            deviceId: String,
        ) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override suspend fun deleteAccount(confirmation: String) = Unit
        override fun clear() = Unit
    }

    private class FakeService(val profile: me.egigoka.pomodorough.data.User) : PomodoroughService {
        private val empty = SyncResponse(
            acknowledgements = emptyList(),
            revision = 0,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = "2026-01-01T00:00:00Z",
            serverHlcWallMs = 1_767_225_600_000,
            serverHlcCounter = 0,
            durationAcknowledgements = emptyList(),
            durationsMs = DurationsMs(),
            taskAcknowledgements = emptyList(),
            tasks = emptyList(),
        )
        override suspend fun me(accessToken: String) = MeResponse(profile, "")
        override suspend fun bootstrap(accessToken: String) = empty
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            error("unused")
    }
}
