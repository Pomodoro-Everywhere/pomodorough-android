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
import kotlinx.serialization.SerializationException
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
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A02 (moved from R54-A01 F3): a token vault that refuses to clear
 * rejected credentials must not escape sign-in, restore, the restore retry,
 * or session expiry. The account stays signed out with the unreadable
 * credential notice and a local reset offer, the rejected tokens are never
 * replayed, and the failure reports one bounded category. Real Room and
 * bundled Core; only the server and token vault are faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A02CredentialClearFailureTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
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
    fun malformedRestoreWithRefusedClearAsksForReset() = runBlocking {
        val (repo, service, auth) = harness(ProfileMode.Malformed)
        auth.clearFailure = IllegalStateException("r54 vault clear refused")

        repo.initialize()

        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service, calls = 1)
        auth.clearFailure = null
        repo.resetLocalAccount()
        assertFalse("reset clears the recovery requirement", repo.state.value.localAccountResetRequired)
        assertEquals(AuthCredentialState.Empty, auth.credentials)
    }

    @Test
    fun retriedMalformedRestoreWithRefusedClearStaysContained() = runBlocking {
        val (repo, service, auth) = harness(ProfileMode.Offline)
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        service.profile = ProfileMode.Malformed
        auth.clearFailure = IllegalStateException("r54 vault clear refused")
        repo.dismissNotice()

        repo.refresh()

        awaitCondition("retry published its outcome") { repo.state.value.notice != null || uncaught.isNotEmpty() }
        awaitAccountTransitionsIdle(repo)
        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service, calls = 2)
    }

    @Test
    fun malformedSignInWithRefusedClearAsksForReset() = runBlocking {
        val (repo, service, auth) = harness(ProfileMode.Malformed, AuthCredentialState.Empty)
        repo.initialize()
        assertEquals(0, service.profileCalls.get())
        auth.clearFailure = IllegalStateException("r54 vault clear refused")

        repo.signIn(FakeCredentialProvider())

        assertEquals(1, auth.signInCalls)
        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service, calls = 1)
    }

    @Test
    fun expiredRestoreWithRefusedClearAsksForReset() = runBlocking {
        val (repo, service, auth) = harness(ProfileMode.Expired)
        auth.clearFailure = IllegalStateException("r54 vault clear refused")

        repo.initialize()

        assertRecoveryRequired(repo, auth)
        assertRejectedTokensNotReplayed(repo, service, calls = 1)
    }

    @Test
    fun malformedRestoreWithClearedCredentialsKeepsOrdinaryNotice() = runBlocking {
        val (repo, _, auth) = harness(ProfileMode.Malformed)

        repo.initialize()

        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(AuthCredentialState.Empty, auth.credentials)
        assertEquals(1, auth.clearCalls)
        // The ordinary notice is the localized sentence, never the exception text.
        assertEquals(
            context.getString(R.string.account_restore_failed_profile_rejected),
            repo.state.value.notice,
        )
        assertFalse(repo.state.value.localAccountResetRequired)
        assertTrue("a clean clear stays silent", reported.isEmpty())
        assertTrue(uncaught.isEmpty())
    }

    private fun assertRecoveryRequired(repo: TimerRepository, auth: FakeClearAuth) {
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(context.getString(R.string.unreadable_credential), repo.state.value.notice)
        assertTrue("reset must be offered", repo.state.value.localAccountResetRequired)
        assertEquals("the vault still holds the rejected tokens", AuthCredentialState.Active, auth.credentials)
        assertEquals(1, auth.clearCalls)
        assertEquals(
            listOf("Credential clear failed: stale tokens need recovery"),
            reported.map { it.message },
        )
    }

    private suspend fun assertRejectedTokensNotReplayed(repo: TimerRepository, service: FakeClearService, calls: Int) {
        service.profile = ProfileMode.Healthy
        repo.refresh()
        repo.onForeground()
        repo.initialize()
        awaitAccountTransitionsIdle(repo)
        delay(100)
        assertEquals("rejected tokens must not reach the server again", calls, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    private fun harness(
        profile: ProfileMode,
        credentials: AuthCredentialState = AuthCredentialState.Active,
    ): Harness {
        val service = FakeClearService(account).apply { this.profile = profile }
        val auth = FakeClearAuth().apply { this.credentials = credentials }
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

    private enum class ProfileMode { Offline, Expired, Malformed, Healthy }

    private class FakeClearAuth : AuthSession {
        @Volatile var credentials = AuthCredentialState.Active
        @Volatile var clearFailure: RuntimeException? = null
        @Volatile var signInCalls = 0
        @Volatile var clearCalls = 0

        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair {
            signInCalls += 1
            credentials = AuthCredentialState.Active
            return TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")
        }

        override fun hasTokens() = credentials == AuthCredentialState.Active
        override fun credentialState() = credentials
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() {
            credentials = AuthCredentialState.Empty
        }

        override fun clear() {
            clearCalls += 1
            clearFailure?.let { throw it }
            credentials = AuthCredentialState.Empty
        }
    }

    private class FakeCredentialProvider : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String) = "id-token"
    }

    private class FakeClearService(private val account: User) : PomodoroughService {
        @Volatile var profile = ProfileMode.Offline
        val profileCalls = AtomicInteger()
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
            return when (profile) {
                ProfileMode.Offline -> throw IOException("r54 restore offline")
                ProfileMode.Expired -> throw AuthenticationRequired()
                ProfileMode.Malformed -> throw SerializationException("r54 malformed profile")
                ProfileMode.Healthy -> MeResponse(account, "")
            }
        }

        override suspend fun bootstrap(accessToken: String) = empty
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("r54 revision stream offline")
    }
}
