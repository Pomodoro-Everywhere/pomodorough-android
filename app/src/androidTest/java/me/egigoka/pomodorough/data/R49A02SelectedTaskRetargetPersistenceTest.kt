package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R49-A02 with real Room: selection plus retarget commit atomically.
 * Each failure mode throws from a distinct point inside a real Room
 * transaction: selection-write before any insert, retarget-write after
 * the selection insert, final-txn after both inserts. Rollback leaves
 * no durable partial in every mode.
 */
@RunWith(AndroidJUnit4::class)
class R49A02SelectedTaskRetargetPersistenceTest {
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
        SelectedTaskRetargetReporter.resetForTest()
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
        SelectedTaskRetargetReporter.resetForTest()
    }

    @Test
    fun successPersistsBothWithSingleProjection() = runBlocking {
        quietReports()
        val dao = FailingRetargetDao(database, database.timerDao())
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val ids = setupPausedTimerOnA(repository)
        dao.resetCounts()
        val before = durableSnapshot(dao.delegate)

        repository.selectTask(ids.second)

        val after = durableSnapshot(dao.delegate)
        assertEquals(1, dao.selectedRetargetCalls)
        assertEquals(0, dao.selectedCalls)
        assertEquals(0, dao.commandCalls)
        assertEquals(before.selectedOps.size + 1, after.selectedOps.size)
        assertEquals(before.commands.size + 1, after.commands.size)
        assertEquals(ids.second, after.local.selectedTaskId)
        assertEquals(ids.second, repository.state.value.selectedTaskId)
        assertEquals(ids.second, repository.state.value.timer?.taskId)
        assertEquals(CommandType.Retarget, after.commands.last().toModel().type)
        assertEquals(ids.second, after.commands.last().toModel().taskId)
        assertEquals(after.local.deviceSequence, before.local.deviceSequence + 1)
        assertTrue(after.local.hlcWallMs >= before.local.hlcWallMs)
        assertTrue(after.selectedOps.last().neverSent)
        assertTrue(after.commands.last().neverSent)
    }

    @Test
    fun selectionWriteFailureLeavesCommittedState() = runBlocking {
        runAtomicFailureCase("selection-write")
    }

    @Test
    fun retargetWriteFailureLeavesCommittedState() = runBlocking {
        runAtomicFailureCase("retarget-write")
    }

    @Test
    fun finalTransactionFailureLeavesCommittedState() = runBlocking {
        runAtomicFailureCase("final-txn")
    }

    @Test
    fun duplicateDistinctFailuresStillReportOnceTotal() = runBlocking {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        SelectedTaskRetargetReporter.resetForTest()
        val dao = FailingRetargetDao(database, database.timerDao())
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val ids = setupPausedTimerOnA(repository)
        val before = durableSnapshot(dao.delegate)
        dao.failSelectedRetarget = "retarget-write"
        repository.selectTask(ids.second)
        dao.failSelectedRetarget = "final-txn"
        repository.selectTask(ids.second)
        val after = durableSnapshot(dao.delegate)
        assertEquals(before.local, after.local)
        assertEquals(before.selectedOps.map { it.id }, after.selectedOps.map { it.id })
        assertEquals(before.commands.map { it.id }, after.commands.map { it.id })
        assertEquals(ids.first, repository.state.value.selectedTaskId)
        assertEquals(ids.first, repository.state.value.timer?.taskId)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is SelectedTaskRetargetReport)
    }

    @Test
    fun retryAfterFinalTransactionFailurePersistsAndReopenRestores() = runBlocking {
        quietReports()
        val dao = FailingRetargetDao(database, database.timerDao())
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val ids = setupPausedTimerOnA(repository)
        dao.failSelectedRetarget = "final-txn"
        repository.selectTask(ids.second)
        assertEquals(ids.first, repository.state.value.selectedTaskId)
        assertEquals(1, dao.selectedInnerInserts)
        assertEquals(1, dao.commandInnerInserts)
        dao.failSelectedRetarget = null
        dao.resetCounts()
        repository.selectTask(ids.second)
        assertEquals(ids.second, repository.state.value.selectedTaskId)
        assertEquals(ids.second, repository.state.value.timer?.taskId)
        repository.shutdownForTest()
        val reopened = repository(dao)
        reopened.initialize()
        awaitSignedIn(reopened)
        assertEquals(ids.second, reopened.state.value.selectedTaskId)
        assertEquals(ids.second, reopened.state.value.timer?.taskId)
    }

    @Test
    fun retryAfterFailurePersistsAndReopenRestores() = runBlocking {
        quietReports()
        val dao = FailingRetargetDao(database, database.timerDao())
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val ids = setupPausedTimerOnA(repository)
        dao.failSelectedRetarget = "selection-write"
        repository.selectTask(ids.second)
        assertEquals(ids.first, repository.state.value.selectedTaskId)
        dao.failSelectedRetarget = null
        dao.resetCounts()
        repository.selectTask(ids.second)
        assertEquals(ids.second, repository.state.value.selectedTaskId)
        assertEquals(ids.second, repository.state.value.timer?.taskId)
        repository.shutdownForTest()
        val reopened = repository(dao)
        reopened.initialize()
        awaitSignedIn(reopened)
        assertEquals(ids.second, reopened.state.value.selectedTaskId)
        assertEquals(ids.second, reopened.state.value.timer?.taskId)
    }

    private suspend fun runAtomicFailureCase(mode: String) {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        SelectedTaskRetargetReporter.resetForTest()
        val dao = FailingRetargetDao(database, database.timerDao())
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val ids = setupPausedTimerOnA(repository)
        val before = durableSnapshot(dao.delegate)
        val beforeSelected = repository.state.value.selectedTaskId
        val beforeTimerTask = repository.state.value.timer?.taskId
        dao.failSelectedRetarget = mode
        repository.selectTask(ids.second)
        val after = durableSnapshot(dao.delegate)
        assertEquals(before.local, after.local)
        assertEquals(before.selectedOps.map { it.id }, after.selectedOps.map { it.id })
        assertEquals(before.commands.map { it.id }, after.commands.map { it.id })
        assertEquals(ids.first, repository.state.value.selectedTaskId)
        assertEquals(beforeSelected, repository.state.value.selectedTaskId)
        assertEquals(beforeTimerTask, repository.state.value.timer?.taskId)
        assertEquals(ids.first, repository.state.value.timer?.taskId)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is SelectedTaskRetargetReport)
        when (mode) {
            "selection-write" -> {
                assertEquals(0, dao.selectedInnerInserts)
                assertEquals(0, dao.commandInnerInserts)
            }
            "retarget-write" -> {
                assertEquals(1, dao.selectedInnerInserts)
                assertEquals(0, dao.commandInnerInserts)
            }
            "final-txn" -> {
                assertEquals(1, dao.selectedInnerInserts)
                assertEquals(1, dao.commandInnerInserts)
            }
        }
        assertEquals(
            context.getString(R.string.sync_stopped_after_a_local_failure),
            repository.state.value.notice,
        )
    }

    private suspend fun setupPausedTimerOnA(repository: TimerRepository): Pair<String, String> {
        repository.addTask("Task A")
        repository.addTask("Task B")
        val taskA = repository.state.value.tasks.first { it.title == "Task A" }
        val taskB = repository.state.value.tasks.first { it.title == "Task B" }
        repository.selectTask(taskA.id)
        repository.toggleTimer()
        repository.toggleTimer()
        assertEquals(TimerStatus.Paused, repository.state.value.timer?.status)
        assertEquals(taskA.id, repository.state.value.timer?.taskId)
        assertEquals(taskA.id, repository.state.value.selectedTaskId)
        return taskA.id to taskB.id
    }

    private fun durableSnapshot(dao: TimerDao): DurableSnapshot = runBlocking {
        DurableSnapshot(
            local = requireNotNull(dao.localState()),
            selectedOps = dao.pendingSelectedTaskOperations(),
            commands = dao.pendingCommands(),
        )
    }

    private fun quietReports() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        SelectedTaskRetargetReporter.resetForTest()
    }

    private fun repository(dao: TimerDao): TimerRepository {
        val account = User("account-1", "account-1@example.com", "account", "")
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
            api = FakeRetargetService(account),
            auth = FakeRetargetAuth(),
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private suspend fun awaitSignedIn(repository: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repository.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private data class DurableSnapshot(
        val local: LocalStateEntity,
        val selectedOps: List<PendingSelectedTaskOperationEntity>,
        val commands: List<PendingCommandEntity>,
    )

    private class FailingRetargetDao(
        val db: PomodoroughDatabase,
        val delegate: TimerDao,
    ) : TimerDao by delegate {
        var failSelectedRetarget: String? = null
        var selectedRetargetCalls = 0
        var selectedCalls = 0
        var commandCalls = 0
        var selectedInnerInserts = 0
        var commandInnerInserts = 0

        fun resetCounts() {
            selectedRetargetCalls = 0
            selectedCalls = 0
            commandCalls = 0
            selectedInnerInserts = 0
            commandInnerInserts = 0
        }

        override suspend fun persistSelectedTaskRetarget(
            selectedOperation: PendingSelectedTaskOperationEntity,
            command: PendingCommandEntity,
            state: LocalStateEntity,
        ) {
            selectedRetargetCalls += 1
            val mode = failSelectedRetarget
            if (mode == null) {
                delegate.persistSelectedTaskRetarget(selectedOperation, command, state)
                return
            }
            db.withTransaction {
                when (mode) {
                    "selection-write" -> throw IOException("retarget selection-write failed")
                    "retarget-write" -> {
                        delegate.insertSelectedTaskOperation(selectedOperation)
                        selectedInnerInserts += 1
                        throw IOException("retarget retarget-write failed")
                    }
                    "final-txn" -> {
                        delegate.insertSelectedTaskOperation(selectedOperation)
                        selectedInnerInserts += 1
                        delegate.insertCommand(command)
                        commandInnerInserts += 1
                        throw IOException("retarget final-txn failed")
                    }
                    else -> throw IOException("retarget $mode failed")
                }
            }
        }

        override suspend fun persistSelectedTaskOperation(
            operation: PendingSelectedTaskOperationEntity,
            state: LocalStateEntity,
        ) {
            selectedCalls += 1
            delegate.persistSelectedTaskOperation(operation, state)
        }

        override suspend fun persistCommand(command: PendingCommandEntity, state: LocalStateEntity) {
            commandCalls += 1
            delegate.persistCommand(command, state)
        }
    }

    private class FakeRetargetAuth : AuthSession {
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

    private class FakeRetargetService(val profile: User) : PomodoroughService {
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
