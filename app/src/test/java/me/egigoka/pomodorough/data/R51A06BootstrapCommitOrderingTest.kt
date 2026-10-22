package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R51-A06: bootstrap commit must precede generation fencing. The DAO
 * delegate runs the normal Room transaction and throws at the final
 * write; Room rolls back, the attempt stays current, failure handlers
 * publish and report, Loading exits, and retry recovers. Real bundled
 * Core plus real Room, no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R51A06BootstrapCommitOrderingTest {
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
        AccountBootstrapReporter.resetForTest()
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
        AccountBootstrapReporter.resetForTest()
    }

    @Test
    fun restoreFinalWriteIoRollsBackQuietAndExitsLoading() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val repo = repository(dao)
        val before = snapshot(dao.delegate)
        dao.fail = "final-io"
        repo.initialize()
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(1, dao.updateCalls)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertTrue(reported.isEmpty())
        assertTrue(repo.addTask("signed-out-local-still-usable"))
        assertTrue(repo.state.value.tasks.any { it.title == "signed-out-local-still-usable" })
    }

    @Test
    fun restoreFinalWriteNonIoReportsBootstrapCommit() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val repo = repository(dao)
        val before = snapshot(dao.delegate)
        dao.fail = "final-nonio"
        repo.initialize()
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountBootstrapReport)
        assertEquals("Account bootstrap restore failed: bootstrap_commit", single.message)
    }

    @Test
    fun restoreFirstWriteNonIoKeepsFailureOwnership() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val repo = repository(dao)
        val before = snapshot(dao.delegate)
        dao.fail = "early-nonio"
        repo.initialize()
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertEquals(1, reported.size)
        assertEquals(
            "Account bootstrap restore failed: bootstrap_commit",
            reported.single().message,
        )
    }

    @Test
    fun signInFinalWriteNonIoReportsSignInCommit() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeBootAuth(tokens = false)
        val repo = repository(dao, auth)
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        auth.tokens = true
        dao.fail = "final-nonio"
        repo.signIn(FakeCredentialProvider())
        assertTrue(dao.updateCalls >= 1)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertEquals(1, reported.size)
        assertEquals(
            "Account bootstrap sign_in failed: bootstrap_commit",
            reported.single().message,
        )
    }

    @Test
    fun signInFinalWriteIoStaysQuiet() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val auth = FakeBootAuth(tokens = false)
        val repo = repository(dao, auth)
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        auth.tokens = true
        dao.fail = "final-io"
        repo.signIn(FakeCredentialProvider())
        assertTrue(dao.updateCalls >= 1)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun failedCommitRetryRecoversAndAdmitsMutations() = runBlocking {
        quietReports()
        val dao = failingDao()
        val repo = repository(dao)
        dao.fail = "final-io"
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        dao.fail = null
        AccountBootstrapReporter.resetForTest()
        repo.initialize()
        awaitSignedIn(repo)
        assertTrue(dao.updateCalls >= 2)
        assertTrue(repo.addTask("recovered-task"))
        assertTrue(repo.state.value.tasks.any { it.title == "recovered-task" })
        val durable = snapshot(dao.delegate)
        assertEquals("account-1", durable.local.ownerUserId)
        assertTrue(durable.commands.isEmpty())
        assertTrue(durable.tasks.isNotEmpty())
    }

    @Test
    fun cancelDuringFinalWritePropagatesWithoutReportOrFencing() = runBlocking {
        val reported = captureReports()
        val dao = failingDao()
        val repo = repository(dao)
        val before = snapshot(dao.delegate)
        dao.fail = "final-cancel"
        val thrown = runCatching { repo.initialize() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertDurableUnchanged(before, snapshot(dao.delegate))
        assertTrue(reported.isEmpty())
        assertNull(repo.state.value.notice)
        dao.fail = null
        AccountBootstrapReporter.resetForTest()
        repo.initialize()
        awaitSignedIn(repo)
    }

    @Test
    fun reconstructedRepositoryCommitsHealthyRevision() = runBlocking {
        quietReports()
        val dao = failingDao()
        val repo = repository(dao)
        dao.fail = "final-nonio"
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        val reopened = TimerRepository(
            context = context,
            dao = dao.delegate,
            api = FakeBootService(),
            auth = FakeBootAuth(),
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
        reopened.initialize()
        awaitSignedIn(reopened)
        assertTrue(reopened.addTask("healthy-revision"))
        assertTrue(reopened.state.value.tasks.any { it.title == "healthy-revision" })
    }

    @Test
    fun staleAttemptOwnershipRejectedAfterFencing() {
        val controller = AccountWorkspaceController {}
        val stale = controller.attemptIdentity(null)
        controller.advanceGeneration(AccountWorkspaceReason.AuthenticationCompleted)
        assertTrue(!controller.owns(stale, null))
        assertTrue(controller.owns(controller.attemptIdentity(null), null))
    }

    @Test
    fun commitFailureKeepsAttemptCurrentSoRetryRecovers() = runBlocking {
        // Behavioral replacement for the former wiring source pin.
        // Commit runs before generation fencing: a final-write failure
        // keeps the attempt current, handlers publish and report, and
        // an explicit retry recovers. Fence-first would strand the retry.
        val reported = captureReports()
        val dao = failingDao()
        val repo = repository(dao)
        dao.fail = "final-nonio"
        repo.initialize()
        assertEquals(1, dao.updateCalls)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNotNull(repo.state.value.notice)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is AccountBootstrapReport)
        dao.fail = null
        AccountBootstrapReporter.resetForTest()
        repo.initialize()
        awaitSignedIn(repo)
        assertTrue(dao.updateCalls >= 2)
        assertTrue(repo.addTask("ordering-retry-task"))
    }

    @Test
    fun genuineStoredFailuresNeedNoNewCategory() {
        val io = classifyAccountBootstrapFailure(
            AccountBootstrapStage.Restore,
            IOException("commit-canary"),
        )
        assertNull(io)
        val room = IllegalStateException("commit-canary")
        val restore = classifyAccountBootstrapFailure(AccountBootstrapStage.Restore, room)
        assertEquals(AccountBootstrapCause.BootstrapCommit, restore?.cause)
        val signIn = classifyAccountBootstrapFailure(AccountBootstrapStage.SignIn, room)
        assertEquals(AccountBootstrapCause.BootstrapCommit, signIn?.cause)
        // Loosened from ==5: taxonomy may grow, but known members stay.
        val causes = AccountBootstrapCause.values().toList()
        assertTrue("taxonomy grows explicitly, was 5: $causes", causes.size >= 5)
        assertTrue(causes.contains(AccountBootstrapCause.BootstrapCommit))
        assertTrue(causes.contains(AccountBootstrapCause.CredentialWrite))
    }

    private fun failingDao() = FailingBootstrapDao(database, database.timerDao())

    private fun repository(dao: TimerDao, auth: FakeBootAuth = FakeBootAuth()): TimerRepository {
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
            api = FakeBootService(account),
            auth = auth,
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private fun captureReports(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
        return reported
    }

    private fun quietReports() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
    }

    private suspend fun awaitSignedIn(repo: TimerRepository) {
        val done = withTimeoutOrNull(15_000) {
            while (repo.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private fun snapshot(dao: TimerDao): Durable = runBlocking {
        Durable(
            local = requireNotNull(dao.localState()),
            commands = dao.pendingCommands().map { it.id },
            tasks = dao.pendingTaskOperations().map { it.id },
            durations = dao.pendingDurationOperations().map { it.id },
            autoStarts = dao.pendingAutoStartOperations().map { it.id },
            selected = dao.pendingSelectedTaskOperations().map { it.id },
            resolution = dao.pendingBootstrapResolution()?.requestId,
        )
    }

    private fun assertDurableUnchanged(before: Durable, after: Durable) {
        assertEquals(before.local, after.local)
        assertEquals(before.commands, after.commands)
        assertEquals(before.tasks, after.tasks)
        assertEquals(before.durations, after.durations)
        assertEquals(before.autoStarts, after.autoStarts)
        assertEquals(before.selected, after.selected)
        assertEquals(before.resolution, after.resolution)
    }

    private data class Durable(
        val local: LocalStateEntity,
        val commands: List<String>,
        val tasks: List<String>,
        val durations: List<String>,
        val autoStarts: List<String>,
        val selected: List<String>,
        val resolution: String?,
    )

    private class FailingBootstrapDao(
        val db: PomodoroughDatabase,
        val delegate: TimerDao,
    ) : TimerDao by delegate {
        var fail: String? = null
        var updateCalls = 0

        override suspend fun updateMutationState(
            state: LocalStateEntity,
            commands: List<PendingCommandEntity>,
            taskOperations: List<PendingTaskOperationEntity>,
            durationOperations: List<PendingDurationOperationEntity>,
            autoStartOperations: List<PendingAutoStartOperationEntity>,
            selectedTaskOperations: List<PendingSelectedTaskOperationEntity>,
        ) {
            updateCalls += 1
            when (fail) {
                "final-io", "final-nonio", "final-cancel" -> db.withTransaction {
                    delegate.updateMutationState(
                        state,
                        commands,
                        taskOperations,
                        durationOperations,
                        autoStartOperations,
                        selectedTaskOperations,
                    )
                    throw failureFor(checkNotNull(fail))
                }
                "early-nonio" -> throw failureFor(checkNotNull(fail))
                else -> delegate.updateMutationState(
                    state,
                    commands,
                    taskOperations,
                    durationOperations,
                    autoStartOperations,
                    selectedTaskOperations,
                )
            }
        }

        private fun failureFor(mode: String): Exception = when (mode) {
            "final-io", "early-io" -> IOException("bootstrap commit failed")
            "final-cancel", "early-cancel" -> CancellationException("bootstrap gone")
            else -> IllegalStateException("bootstrap commit failed")
        }
    }

    private class FakeBootAuth(var tokens: Boolean = true) : AuthSession {
        override suspend fun signIn(
            credentialProvider: GoogleCredentialProvider,
            deviceId: String,
        ) = TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")

        override fun hasTokens() = tokens
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FakeCredentialProvider : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String) = "id-token"
    }

    private class FakeBootService(val profile: User = User("account-1", "a1@example.test", "A", "")) :
        PomodoroughService {
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
