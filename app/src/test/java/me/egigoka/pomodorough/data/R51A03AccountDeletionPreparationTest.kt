package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R51-A03: first deletion-marker write must not escape to uncaught.
 * Real Room + production repository + production ViewModel, no emulator.
 * Covers deleteAccount first/final, logout first/final, reset final,
 * deleteTask first/final, retry, reopen, remote counts, cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R51A03AccountDeletionPreparationTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        AccountDeletionReporter.resetForTest()
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
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun deletePreparedFirstWriteRetainsAndReportsBounded() = runBlocking {
        val reported = captureDeletion()
        val dao = failingDao()
        val auth = FakeDeleteAuth()
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        val before = snapshot(dao.delegate)
        dao.fail = "prepared-first"
        repo.deleteAccount("DELETE")
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(deletionFailedNotice(), repo.state.value.notice)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is AccountDeletionReport)
        assertEquals(0, auth.deleteCalls)
        assertEquals(AuthStatus.SignedIn, repo.state.value.authStatus)
    }

    @Test
    fun deletePreparedFirstWriteViaViewModelHasNoUncaught() = runBlocking {
        val reported = captureDeletion()
        val dao = failingDao()
        val auth = FakeDeleteAuth()
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        val before = snapshot(dao.delegate)
        val uncaught = captureUncaught()
        mainForViewModel()
        try {
            dao.fail = "prepared-first"
            viewModel(repo).deleteAccount("DELETE")
            awaitNotice(repo)
        } finally {
            restoreMain()
            restoreUncaught(uncaught)
        }
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(deletionFailedNotice(), repo.state.value.notice)
        assertEquals(1, reported.size)
        assertTrue(uncaught.errors.isEmpty())
        assertEquals(0, auth.deleteCalls)
    }

    @Test
    fun deletePreparedFailureRetrySucceedsAndReopenRestores() = runBlocking {
        quietDeletion()
        val dao = failingDao()
        val auth = FakeDeleteAuth()
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        dao.fail = "prepared-first"
        repo.deleteAccount("DELETE")
        awaitNotice(repo)
        dao.fail = null
        AccountDeletionReporter.resetForTest()
        repo.deleteAccount("DELETE")
        awaitScrubbed(repo, dao.delegate)
        assertEquals(1, auth.deleteCalls)
        repo.shutdownForTest()
        repositories.remove(repo)
        val reopened = repository(dao.delegate)
        reopened.initialize()
        assertNull(reopened.state.value.user)
        assertEquals(AuthStatus.SignedOut, reopened.state.value.authStatus)
    }

    @Test
    fun deleteRemoteMarkerFailureRetainsPrepared() = runBlocking {
        val reported = captureAll()
        val dao = failingDao()
        val auth = FakeDeleteAuth()
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        dao.fail = "remote-first"
        repo.deleteAccount("DELETE")
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(deletionFailedNotice(), repo.state.value.notice)
        assertEquals(1, auth.deleteCalls)
        assertEquals(1, reported.size)
    }

    @Test
    fun deleteScrubFinalTxRetainsRemoteCommittedThenRetries() = runBlocking {
        quietDeletion()
        val dao = failingDao()
        val auth = FakeDeleteAuth()
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        dao.fail = "scrub-final"
        repo.deleteAccount("DELETE")
        assertEquals("remote_committed", dao.delegate.localState()?.accountDeletionState)
        assertEquals(1, auth.deleteCalls)
        dao.fail = null
        repo.deleteAccount("DELETE")
        awaitScrubbed(repo, dao.delegate)
    }

    @Test
    fun logoutFirstWriteRetainsOwner() = runBlocking {
        val reported = captureDeletion()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        val owner = dao.delegate.localState()?.ownerUserId
        dao.fail = "logout-first"
        repo.logout()
        assertEquals(owner, dao.delegate.localState()?.ownerUserId)
        assertNull(dao.delegate.localState()?.accountDeletionState)
        assertTrue(repo.state.value.notice != null)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun logoutCommitsScrubMarkerBeforeCredentialRetirement() = runBlocking {
        quietDeletion()
        val probe = OrderingProbeDao(database.timerDao())
        val auth = OrderingLogoutAuth(probe)
        val repo = repository(probe, auth)
        repo.initialize()
        awaitSignedIn(repo)
        repo.logout()
        assertEquals(1, auth.retirements)
        assertTrue("marker must commit before credential retirement", auth.markerCommittedAtRetirement)
        assertNull(probe.delegate.localState()?.ownerUserId)
        assertNull(probe.delegate.localState()?.accountDeletionState)
    }

    @Test
    fun healthyLogoutReopenStaysScrubbed() = runBlocking {
        quietDeletion()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        repo.addTask("Scrub me")
        awaitTask(repo, "Scrub me")
        repo.logout()
        assertNull(dao.delegate.localState()?.ownerUserId)
        assertNull(dao.delegate.localState()?.accountDeletionState)
        repo.shutdownForTest()
        repositories.remove(repo)
        val reopened = repository(dao.delegate)
        reopened.initialize()
        assertNull(reopened.state.value.user)
        assertEquals(AuthStatus.SignedOut, reopened.state.value.authStatus)
        assertNull(dao.delegate.localState()?.ownerUserId)
        assertNull(dao.delegate.localState()?.accountDeletionState)
        assertTrue(reopened.state.value.tasks.isEmpty())
        assertEquals(0, reopened.state.value.pendingCount)
    }

    @Test
    fun logoutFinalTxViaViewModelHasNoUncaught() = runBlocking {
        quietDeletion()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        val uncaught = captureUncaught()
        mainForViewModel()
        try {
            dao.fail = "logout-final"
            viewModel(repo).logout()
            awaitNotice(repo)
        } finally {
            restoreMain()
            restoreUncaught(uncaught)
        }
        assertTrue(uncaught.errors.isEmpty())
        assertTrue(repo.state.value.notice != null)
    }

    @Test
    fun resetFinalTxRetainsPrepared() = runBlocking {
        quietDeletion()
        val dao = failingDao()
        insertPrepared(dao.delegate)
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        dao.fail = "reset-final"
        repo.resetLocalAccount()
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertTrue(repo.state.value.notice != null)
        dao.fail = null
        repo.resetLocalAccount()
        assertNull(dao.delegate.localState()?.accountDeletionState)
    }

    @Test
    fun deleteTaskFirstWriteReturnsRefusal() = runBlocking {
        quietAll()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        repo.addTask("Delete me")
        awaitTask(repo, "Delete me")
        val before = snapshot(dao.delegate)
        dao.fail = "task-first"
        val taskId = repo.state.value.tasks.first { it.title == "Delete me" }.id
        viewModel(repo).deleteTask(taskId)
        awaitNotice(repo)
        assertDurableUnchanged(before, snapshot(dao.delegate))
    }

    @Test
    fun deleteTaskFinalTxRetainsState() = runBlocking {
        quietAll()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        repo.addTask("Delete final")
        awaitTask(repo, "Delete final")
        val before = snapshot(dao.delegate)
        dao.fail = "task-final"
        val taskId = repo.state.value.tasks.first { it.title == "Delete final" }.id
        repo.deleteTask(taskId)
        awaitNotice(repo)
        assertDurableUnchanged(before, snapshot(dao.delegate))
    }

    @Test
    fun cancellationPropagatesWithoutReportOrNotice() = runBlocking {
        val reported = captureDeletion()
        val dao = failingDao()
        val repo = repository(dao, FakeDeleteAuth())
        repo.initialize()
        awaitSignedIn(repo)
        dao.fail = "prepared-cancel"
        var thrown: Throwable? = null
        try {
            repo.deleteAccount("DELETE")
        } catch (error: CancellationException) {
            thrown = error
        }
        assertTrue(thrown is CancellationException)
        assertTrue(reported.isEmpty())
        assertNull(repo.state.value.notice)
    }

    @Test
    fun boundedCategoryHasFixedMessageWithoutPayload() {
        val canary = "R51A03-SENTINEL-{\"secret\":\"x\"}"
        val reported = captureDeletion()
        AccountDeletionReporter.report(AccountDeletionFailure.PreparationStorage)
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountDeletionReport)
        assertEquals(AccountDeletionFailure.PreparationStorage.reportMessage, single.message)
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    @Test
    fun duplicatePreparationReportsOnce() {
        val reported = captureDeletion()
        AccountDeletionReporter.report(AccountDeletionFailure.PreparationStorage)
        AccountDeletionReporter.report(AccountDeletionFailure.PreparationStorage)
        assertEquals(1, reported.size)
    }

    @Test
    fun storageClassificationNeverMentionsCore() {
        val text = productionFailuresText()
        assertTrue(text.contains("CancellationException"))
        assertTrue(text.contains("PreparationStorage"))
        assertTrue(!text.contains("SharedCoreException"))
        assertTrue(!text.contains("shared_core"))
        assertTrue(!text.contains("projectionFailureMessage"))
    }

    private fun failingDao() = FailingDeletionDao(database, database.timerDao())

    private fun repository(dao: TimerDao, auth: FakeDeleteAuth = FakeDeleteAuth()): TimerRepository {
        val account = User("account-1", "a1@example.test", "A", "")
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
            api = FakeDeleteService(account),
            auth = auth,
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private fun insertPrepared(delegate: TimerDao) = runBlocking {
        val account = User("account-1", "a1@example.test", "A", "")
        delegate.insertState(
            LocalStateEntity(
                deviceId = "device-1",
                settingsJson = json.encodeToString(TimerSettings()),
                userJson = json.encodeToString(account),
                ownerUserId = account.id,
                accountDeletionState = "prepared",
            ),
        )
    }

    private fun viewModel(repository: TimerRepository) = PomodoroughViewModel(repository)

    private fun captureDeletion(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
        return reported
    }

    private fun captureAll(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
        return reported
    }

    private fun quietDeletion() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
    }

    private fun quietAll() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    private fun deletionFailedNotice(): String = context.getString(R.string.account_deletion_failed_no_local_data_was_removed)

    private suspend fun awaitSignedIn(repo: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private suspend fun awaitNotice(repo: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.notice == null) delay(10)
            true
        }
        assertTrue("timed out waiting for notice", done == true)
    }

    private suspend fun awaitScrubbed(repo: TimerRepository, delegate: TimerDao) {
        val done = withTimeoutOrNull(5_000) {
            while (delegate.localState()?.ownerUserId != null) delay(10)
            true
        }
        assertTrue("timed out waiting for scrub", done == true)
        assertNull(delegate.localState()?.accountDeletionState)
    }

    private suspend fun awaitTask(repo: TimerRepository, title: String) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.tasks.none { it.title == title }) delay(10)
            true
        }
        assertTrue("timed out waiting for task", done == true)
    }

    private fun snapshot(dao: TimerDao): Durable = runBlocking {
        Durable(
            local = requireNotNull(dao.localState()),
            commands = dao.pendingCommands().map { it.id },
            tasks = dao.pendingTaskOperations().map { it.id },
        )
    }

    private fun assertDurableUnchanged(before: Durable, after: Durable) {
        assertEquals(before.local, after.local)
        assertEquals(before.commands, after.commands)
        assertEquals(before.tasks, after.tasks)
    }

    private data class Durable(val local: LocalStateEntity, val commands: List<String>, val tasks: List<String>)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun mainForViewModel() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun restoreMain() {
        Dispatchers.resetMain()
    }

    private fun productionFailuresText(): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).firstOrNull(File::isDirectory)
        return File(checkNotNull(root) { "production source root" }, "me/egigoka/pomodorough/data/AccountDeletionFailures.kt").readText()
    }

    private class OrderingProbeDao(val delegate: TimerDao) : TimerDao by delegate {
        var markerCommitted = false

        override suspend fun updateState(state: LocalStateEntity) {
            delegate.updateState(state)
            if (state.accountDeletionState == "local_scrub_required") markerCommitted = true
        }
    }

    private class OrderingLogoutAuth(val probe: OrderingProbeDao) : FakeDeleteAuth() {
        var retirements = 0
        var markerCommittedAtRetirement = false

        override fun prepareLogout() {
            retirements += 1
            markerCommittedAtRetirement = probe.markerCommitted
        }
    }

    private class FailingDeletionDao(val db: PomodoroughDatabase, val delegate: TimerDao) : TimerDao by delegate {
        var fail: String? = null

        override suspend fun updateState(state: LocalStateEntity) {
            when {
                fail == "prepared-first" && state.accountDeletionState == "prepared" ->
                    throw IOException("prepared first-write failed")
                fail == "prepared-cancel" && state.accountDeletionState == "prepared" ->
                    throw CancellationException("prepared gone")
                fail == "remote-first" && state.accountDeletionState == "remote_committed" ->
                    throw IOException("remote marker write failed")
                fail == "logout-first" && state.accountDeletionState == "local_scrub_required" ->
                    throw IOException("logout marker write failed")
                else -> delegate.updateState(state)
            }
        }

        override suspend fun clearAccount(state: LocalStateEntity) {
            if (fail in setOf("scrub-final", "logout-final", "reset-final")) {
                throw IOException("final scrub transaction failed")
            }
            delegate.clearAccount(state)
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

    private open class FakeDeleteAuth : AuthSession {
        var deleteCalls = 0
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override suspend fun deleteAccount(confirmation: String) {
            deleteCalls += 1
        }
        override fun clear() = Unit
    }

    private class FakeDeleteService(val profile: User) : PomodoroughService {
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
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) = error("unused")
    }
}
