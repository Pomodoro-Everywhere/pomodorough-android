package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
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
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R51-A05: expected offline deletion failures stay quiet with
 * outcome-unknown wording; unexpected marker, credential-clear, vault,
 * and Room-scrub failures use finite stage-and-cause categories.
 * Real Room plus production repository plus production ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R51A05AccountDeletionDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false
    private var previousHook: ((Throwable, Throwable) -> Unit)? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        previousHook = CrashReporter.onReporterFailure
        AccountDeletionReporter.resetForTest()
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
        CrashReporter.onReporterFailure = previousHook
        AccountDeletionReporter.resetForTest()
    }

    @Test
    fun offlineIOExceptionStaysQuietTwiceRetainsPrepared() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IOException("offline R51A05"))
        val repo = signedInRepository(dao, auth)
        repeat(2) { repo.deleteAccount("DELETE") }
        assertTrue(reported.isEmpty())
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(outcomeUnknown(), repo.state.value.notice)
        assertEquals(0, dao.delegate.pendingTaskOperations().size)
    }

    @Test
    fun retryableHttpFailureStaysQuiet() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = ApiException(503, "retry later"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertTrue(reported.isEmpty())
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(outcomeUnknown(), repo.state.value.notice)
    }

    @Test
    fun authExpiryStaysQuiet() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = AuthenticationRequired())
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertTrue(reported.isEmpty())
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(outcomeUnknown(), repo.state.value.notice)
    }

    @Test
    fun unexpectedRemoteDeleteReportsBounded() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IllegalStateException("remote boom"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(deletionFailed(), repo.state.value.notice)
        assertSingleCategory(reported, AccountDeletionFailure.RemoteDelete)
    }

    @Test
    fun markerFailureReportsBoundedMarker() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth()
        val repo = signedInRepository(dao, auth)
        dao.fail = "remote-first"
        repo.deleteAccount("DELETE")
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        assertEquals(deletionFailed(), repo.state.value.notice)
        assertEquals(1, auth.deleteCalls)
        assertSingleCategory(reported, AccountDeletionFailure.DeletionMarker)
    }

    @Test
    fun reportEnvelopeHasFixedMessageWithoutCauseOrPayload() {
        val canary = "R51A05-SENTINEL-{\"secret\":\"x\"}"
        val reported = captureReports()
        AccountDeletionReporter.report(AccountDeletionFailure.DeletionMarker)
        val single = reported.single()
        assertTrue(single is AccountDeletionReport)
        assertEquals(AccountDeletionFailure.DeletionMarker.reportMessage, single.message)
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    @Test
    fun offlineWordingHidesRawMessage() = runBlocking {
        captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IOException("raw-net-CANARY-7z"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertEquals(outcomeUnknown(), repo.state.value.notice)
        assertTrue(!repo.state.value.notice.orEmpty().contains("CANARY"))
    }

    @Test
    fun causeWordingHidesRawMessage() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IllegalStateException("raw-remote-CANARY-9q"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertEquals(deletionFailed(), repo.state.value.notice)
        assertTrue(!repo.state.value.notice.orEmpty().contains("CANARY"))
        assertTrue(!reported.single().stackTraceToString().contains("CANARY"))
    }

    @Test
    fun duplicateCategoryReportsOnce() {
        val reported = captureReports()
        AccountDeletionReporter.report(AccountDeletionFailure.RoomScrub)
        AccountDeletionReporter.report(AccountDeletionFailure.RoomScrub)
        assertEquals(1, reported.size)
    }

    @Test
    fun repeatedInfraRetriesStayBounded() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth()
        val repo = signedInRepository(dao, auth)
        dao.fail = "remote-first"
        repo.deleteAccount("DELETE")
        repo.deleteAccount("DELETE")
        assertEquals(1, reported.size)
        assertSingleCategory(reported, AccountDeletionFailure.DeletionMarker)
    }

    @Test
    fun distinctStagesReportDistinctly() {
        val reported = captureReports()
        AccountDeletionReporter.report(AccountDeletionFailure.DeletionMarker)
        AccountDeletionReporter.report(AccountDeletionFailure.CredentialClear)
        assertEquals(2, reported.size)
    }

    @Test
    fun offlineRetrySucceedsAfterRecovery() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IOException("offline"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertEquals("prepared", dao.delegate.localState()?.accountDeletionState)
        auth.deleteFailure = null
        AccountDeletionReporter.resetForTest()
        repo.deleteAccount("DELETE")
        awaitScrubbed(repo, dao.delegate)
        assertEquals(1, auth.deleteCalls)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun credentialClearReportsBounded() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(clearFailure = IllegalStateException("clear boom"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertNull(dao.delegate.localState()?.accountDeletionState)
        assertNull(dao.delegate.localState()?.ownerUserId)
        assertSingleCategory(reported, AccountDeletionFailure.CredentialClear)
    }

    @Test
    fun vaultClearReportsBounded() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(clearFailure = vaultError("vault-CANARY-3k"))
        val repo = signedInRepository(dao, auth)
        repo.deleteAccount("DELETE")
        assertNull(dao.delegate.localState()?.accountDeletionState)
        assertNull(dao.delegate.localState()?.ownerUserId)
        assertSingleCategory(reported, AccountDeletionFailure.VaultClear)
        assertTrue(!reported.single().stackTraceToString().contains("CANARY"))
    }

    @Test
    fun roomScrubReportsBoundedViaRetry() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth()
        val repo = signedInRepository(dao, auth)
        dao.fail = "scrub-final"
        repo.deleteAccount("DELETE")
        assertEquals("remote_committed", dao.delegate.localState()?.accountDeletionState)
        assertEquals(1, auth.deleteCalls)
        assertSingleCategory(reported, AccountDeletionFailure.RoomScrub)
    }

    @Test
    fun recoveryFailureUsesFixedNoticeWithoutRaw() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        dao.fail = "scrub-final"
        insertRemoteCommitted(dao.delegate)
        val repo = repository(dao)
        repo.initialize()
        awaitNotice(repo)
        assertEquals(recoveryFailed(), repo.state.value.notice)
        assertTrue(!repo.state.value.notice.orEmpty().contains("final scrub"))
        assertSingleCategory(reported, AccountDeletionFailure.RoomScrub)
    }

    @Test
    fun offlineViaViewModelShowsOutcomeUnknown() = runBlocking {
        captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = IOException("offline vm"))
        val repo = signedInRepository(dao, auth)
        val uncaught = captureUncaught()
        mainForViewModel()
        try {
            viewModel(repo).deleteAccount("DELETE")
            awaitNotice(repo)
        } finally {
            restoreMain()
            restoreUncaught(uncaught)
        }
        assertEquals(outcomeUnknown(), repo.state.value.notice)
        assertTrue(uncaught.errors.isEmpty())
    }

    @Test
    fun infraViaViewModelShowsDeletionFailed() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val repo = signedInRepository(dao, FakeDeletionAuth())
        val uncaught = captureUncaught()
        mainForViewModel()
        try {
            dao.fail = "remote-first"
            viewModel(repo).deleteAccount("DELETE")
            awaitNotice(repo)
        } finally {
            restoreMain()
            restoreUncaught(uncaught)
        }
        assertEquals(deletionFailed(), repo.state.value.notice)
        assertTrue(uncaught.errors.isEmpty())
        assertSingleCategory(reported, AccountDeletionFailure.DeletionMarker)
    }

    @Test
    fun cancellationPropagatesWithoutReportOrNotice() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeDeletionAuth(deleteFailure = CancellationException("gone"))
        val repo = signedInRepository(dao, auth)
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
    fun reportingDisabledSuppressesDelegate() = runBlocking {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = false
        AccountDeletionReporter.resetForTest()
        val dao = failingDao()
        val repo = signedInRepository(dao, FakeDeletionAuth())
        dao.fail = "remote-first"
        repo.deleteAccount("DELETE")
        assertTrue(reported.isEmpty())
        assertEquals(deletionFailed(), repo.state.value.notice)
    }

    @Test
    fun reporterFailureInvokesHookWithoutThrowing() = runBlocking {
        val hooks = mutableListOf<Pair<Throwable, Throwable>>()
        CrashReporter.delegate = { throw RuntimeException("delegate boom") }
        CrashReporter.onReporterFailure = { original, failure -> hooks += original to failure }
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
        val dao = failingDao()
        val repo = signedInRepository(dao, FakeDeletionAuth())
        dao.fail = "remote-first"
        repo.deleteAccount("DELETE")
        assertEquals(1, hooks.size)
        assertTrue(hooks.single().first is AccountDeletionReport)
        assertEquals(deletionFailed(), repo.state.value.notice)
    }

    @Test
    fun budgetBoundedByCategoryCount() {
        val reported = captureReports()
        val all = AccountDeletionFailure.entries.toList()
        all.forEach(AccountDeletionReporter::report)
        all.forEach(AccountDeletionReporter::report)
        assertEquals(all.size, reported.size)
    }

    @Test
    fun classificationNeverMentionsCore() {
        val roots = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val failures = File(checkNotNull(roots), "me/egigoka/pomodorough/data/AccountDeletionFailures.kt")
        val retry = File(checkNotNull(roots), "me/egigoka/pomodorough/data/AccountDeletionScrubRetry.kt")
        listOf(failures.readText(), retry.readText()).forEach { text ->
            assertTrue(!text.contains("SharedCoreException"))
            assertTrue(!text.contains("CoreProjection"))
            assertTrue(!text.contains("shared_core"))
            assertTrue(!text.contains("projectionFailureMessage"))
        }
        assertTrue(failures.readText().contains("CancellationException"))
    }

    private fun failingDao() = FailingDeletionDao(database, database.timerDao())

    private fun repository(dao: TimerDao, auth: FakeDeletionAuth = FakeDeletionAuth()): TimerRepository {
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
            api = FakeDeletionService(account),
            auth = auth,
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private suspend fun signedInRepository(dao: TimerDao, auth: FakeDeletionAuth): TimerRepository {
        val repo = repository(dao, auth)
        repo.initialize()
        awaitSignedIn(repo)
        return repo
    }

    private fun insertRemoteCommitted(delegate: TimerDao) = runBlocking {
        val account = User("account-1", "a1@example.test", "A", "")
        delegate.insertState(
            LocalStateEntity(
                deviceId = "device-1",
                settingsJson = json.encodeToString(TimerSettings()),
                userJson = json.encodeToString(account),
                ownerUserId = account.id,
                accountDeletionState = "remote_committed",
            ),
        )
    }

    private fun viewModel(repository: TimerRepository) = PomodoroughViewModel(repository)

    private fun captureReports(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountDeletionReporter.resetForTest()
        return reported
    }

    private fun assertSingleCategory(reported: List<Throwable>, expected: AccountDeletionFailure) {
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountDeletionReport)
        assertEquals(expected.reportMessage, single.message)
        assertTrue(single.cause == null)
    }

    // Vault-split fragility note: this fake stacktrace is the only vault
    // signal. R8/minify or a TokenVault/TokenStore/AuthRepository rename
    // silently reclassifies VaultClear as CredentialClear (same notice,
    // narrower report). No frame-independent signal exists; see
    // quality-R51-tests.md A05 and test-R51-regressions.md vault notes.
    // Hardening needs a prod seam (explicit vault error type), out of
    // scope for this test-only pass.
    private fun vaultError(message: String) = IllegalStateException(message).apply {
        stackTrace = arrayOf(
            StackTraceElement("me.egigoka.pomodorough.data.auth.TokenVault", "clear", "TokenVault.kt", 140),
        )
    }

    private fun outcomeUnknown(): String = context.getString(R.string.account_deletion_outcome_unknown)

    private fun deletionFailed(): String =
        context.getString(R.string.account_deletion_failed_no_local_data_was_removed)

    private fun recoveryFailed(): String = context.getString(R.string.account_deletion_recovery_failed)

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

    private class FailingDeletionDao(val db: PomodoroughDatabase, val delegate: TimerDao) : TimerDao by delegate {
        var fail: String? = null

        override suspend fun updateState(state: LocalStateEntity) {
            when {
                fail == "remote-first" && state.accountDeletionState == "remote_committed" ->
                    throw IOException("remote marker write failed")
                else -> delegate.updateState(state)
            }
        }

        override suspend fun clearAccount(state: LocalStateEntity) {
            if (fail == "scrub-final") throw IOException("final scrub transaction failed")
            delegate.clearAccount(state)
        }
    }

    private class FakeDeletionAuth(
        var deleteFailure: Throwable? = null,
        var clearFailure: Throwable? = null,
    ) : AuthSession {
        var deleteCalls = 0
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override suspend fun deleteAccount(confirmation: String) {
            deleteFailure?.let { throw it }
            deleteCalls += 1
        }

        override fun clear() {
            clearFailure?.let { throw it }
        }
    }

    private class FakeDeletionService(val profile: User) : PomodoroughService {
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
