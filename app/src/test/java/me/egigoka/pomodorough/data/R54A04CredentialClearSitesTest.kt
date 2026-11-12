package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A04: the two remaining unguarded `auth.clear()` calls, in account switch
 * cancellation and in expired history-resolution recovery, follow A02's rule.
 * A vault that refuses to clear must not escape the action. The action
 * completes signed out with the unreadable credential notice and a local reset
 * offer, the rejected tokens are never replayed, and the failure reports one
 * bounded category. Real Room and bundled Core; only the server and token vault
 * are faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A04CredentialClearSitesTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
    private val incoming = User("account-2", "a2@example.test", "B", "")
    private val reported = CopyOnWriteArrayList<Throwable>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            database.timerDao().insertState(
                LocalStateEntity(
                    deviceId = "device-1",
                    settingsJson = json.encodeToString(TimerSettings()),
                    userJson = json.encodeToString(account),
                    ownerUserId = account.id,
                ),
            )
        }
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
        AccountBootstrapReporter.resetForTest()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        CrashReporter.delegate = previousDelegate ?: CrashReporter.delegate
        CrashReportingRuntime.reportingEnabled = previousEnabled
        CorruptStateReporter.resetForTest()
        AccountBootstrapReporter.resetForTest()
    }

    @Test
    fun cancelledAccountSwitchWithRefusedClearAsksForReset() = runBlocking {
        val (repo, service, auth) = pendingAccountSwitch()
        auth.logoutFailure = IOException("r54 a04 logout offline")
        auth.clearFailure = IllegalStateException("r54 a04 vault clear refused")

        val cancel = runCatching { repo.cancelAccountSwitch() }

        assertNull("a refused clear must not escape the cancellation", cancel.exceptionOrNull())
        assertNull("the switch is resolved, not left submitting", repo.state.value.accountSwitch)
        assertEquals(1, auth.logoutCalls)
        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service)
    }

    @Test
    fun cancelledAccountSwitchWithClearedCredentialsKeepsLogoutNotice() = runBlocking {
        val (repo, _, auth) = pendingAccountSwitch()
        auth.logoutFailure = IOException("r54 a04 logout offline")

        repo.cancelAccountSwitch()

        assertNull(repo.state.value.accountSwitch)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(AuthCredentialState.Empty, auth.credentials)
        assertEquals(1, auth.clearCalls)
        assertEquals(
            "The new account was removed from this device, but the server sign-out was not confirmed.",
            repo.state.value.notice,
        )
        assertFalse(repo.state.value.localAccountResetRequired)
        assertTrue("a clean clear stays silent", reported.isEmpty())
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun expiredResolutionRecoveryWithRefusedClearAsksForReset() = runBlocking {
        val (repo, service, auth) = corruptedResolution()
        service.bootstrapFailure = AuthenticationRequired()
        auth.clearFailure = IllegalStateException("r54 a04 vault clear refused")

        val recovery = runCatching { repo.recoverCorruptedResolution() }

        assertNull("a refused clear must not escape the recovery", recovery.exceptionOrNull())
        val resolution = repo.state.value.historyResolution
        assertNotNull("the expired recovery keeps the resolution card", resolution)
        resolution!!
        assertFalse("the recovery is resolved, not left submitting", resolution.submitting)
        assertEquals(ResolutionRecovery.Repreview, resolution.recovery)
        assertEquals(
            context.getString(R.string.session_expired_sign_in_again_to_recheck_history),
            resolution.error,
        )
        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service)
    }

    @Test
    fun expiredResolutionRecoveryWithClearedCredentialsShowsNoCredentialNotice() = runBlocking {
        val (repo, service, auth) = corruptedResolution()
        service.bootstrapFailure = AuthenticationRequired()

        repo.recoverCorruptedResolution()

        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(AuthCredentialState.Empty, auth.credentials)
        assertEquals(1, auth.clearCalls)
        assertEquals(
            context.getString(R.string.session_expired_sign_in_again_to_recheck_history),
            repo.state.value.historyResolution?.error,
        )
        assertNotEquals(context.getString(R.string.unreadable_credential), repo.state.value.notice)
        assertFalse(repo.state.value.localAccountResetRequired)
        assertTrue("a clean clear stays silent", reported.isEmpty())
        assertTrue(uncaught.isEmpty())
    }

    private suspend fun pendingAccountSwitch(): Harness {
        val harness = harness(profileUser = incoming)
        harness.repo.initialize()
        awaitCondition("restore detected the account switch") {
            harness.repo.state.value.accountSwitch != null
        }
        assertFalse(harness.repo.state.value.accountSwitch!!.submitting)
        assertEquals(0, harness.auth.clearCalls)
        return harness
    }

    private suspend fun corruptedResolution(): Harness {
        database.timerDao().upsertBootstrapResolution(
            PendingBootstrapResolutionEntity(
                requestId = "r54-a04-resolution",
                deviceId = "device-1",
                expectedRevision = 0,
                strategy = "not-a-strategy",
                commandsJson = "[]",
                taskOperationsJson = "[]",
                durationOperationsJson = "[]",
                ownerUserId = account.id,
                userJson = json.encodeToString(account),
            ),
        )
        val harness = harness(profileUser = account)
        harness.repo.initialize()
        awaitCondition("restore marked the saved resolution corrupted") {
            harness.repo.state.value.historyResolution?.recovery == ResolutionRecovery.Repreview
        }
        assertEquals(AuthStatus.SignedIn, harness.repo.state.value.authStatus)
        assertEquals(0, harness.auth.clearCalls)
        return harness
    }

    private fun assertRecoveryRequired(repo: TimerRepository, auth: FakeClearAuth) {
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNull(repo.state.value.user)
        assertEquals(context.getString(R.string.unreadable_credential), repo.state.value.notice)
        assertTrue("reset must be offered", repo.state.value.localAccountResetRequired)
        assertEquals("the vault still holds the rejected tokens", AuthCredentialState.Active, auth.credentials)
        assertEquals(1, auth.clearCalls)
        assertEquals(
            listOf("Credential clear failed: stale tokens need recovery"),
            reported.map { it.message },
        )
    }

    private suspend fun assertRejectedTokensNotReplayed(repo: TimerRepository, service: FakeClearService) {
        val profileCalls = service.profileCalls.get()
        val bootstrapCalls = service.bootstrapCalls.get()
        service.bootstrapFailure = null
        repo.refresh()
        repo.onForeground()
        repo.initialize()
        awaitAccountTransitionsIdle(repo)
        delay(100)
        assertEquals("rejected tokens must not reach the server again", profileCalls, service.profileCalls.get())
        assertEquals(bootstrapCalls, service.bootstrapCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    private fun harness(profileUser: User): Harness {
        val service = FakeClearService(profileUser)
        val auth = FakeClearAuth()
        val repo = TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = service,
            auth = auth,
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
        return Harness(repo, service, auth)
    }

    // The workspace lock is FIFO, so a pass through it means every request queued earlier ran.
    private suspend fun awaitAccountTransitionsIdle(repo: TimerRepository) {
        val accountSync = TimerRepository::class.java.getDeclaredField("centralizedAccountSync")
            .apply { isAccessible = true }
            .get(repo) as CentralizedAccountSyncAggregate
        withTimeout(10_000) { accountSync.workspace.serialize {} }
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private data class Harness(
        val repo: TimerRepository,
        val service: FakeClearService,
        val auth: FakeClearAuth,
    )

    private class FakeClearAuth : AuthSession {
        @Volatile var credentials = AuthCredentialState.Active
        @Volatile var clearFailure: RuntimeException? = null
        @Volatile var logoutFailure: Exception? = null
        @Volatile var logoutCalls = 0
        @Volatile var clearCalls = 0

        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair {
            credentials = AuthCredentialState.Active
            return TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")
        }

        override fun hasTokens() = credentials == AuthCredentialState.Active
        override fun credentialState() = credentials
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() {
            logoutCalls += 1
            logoutFailure?.let { throw it }
            credentials = AuthCredentialState.Empty
        }

        override fun clear() {
            clearCalls += 1
            clearFailure?.let { throw it }
            credentials = AuthCredentialState.Empty
        }
    }

    private class FakeClearService(private val profileUser: User) : PomodoroughService {
        @Volatile var bootstrapFailure: Exception? = null
        val profileCalls = AtomicInteger()
        val bootstrapCalls = AtomicInteger()
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

        override suspend fun me(accessToken: String): MeResponse {
            profileCalls.incrementAndGet()
            return MeResponse(profileUser, "")
        }

        override suspend fun bootstrap(accessToken: String): SyncResponse {
            bootstrapCalls.incrementAndGet()
            bootstrapFailure?.let { throw it }
            return empty
        }

        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("r54 a04 revision stream offline")
    }
}
