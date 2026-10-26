package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import java.io.IOException
import java.io.StringWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.crash.SentryScrubber
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.auth.TokenStore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R52-L01: provider infrastructure gets its own fixed cause.
 * Real TimerRepository plus real AuthRepository sign-in: provider
 * configuration reports credential_provider with no transport,
 * typed dismissal/interruption and offline challenge stay quiet,
 * coroutine cancellation propagates, and a refused credential-store
 * write reports credential_write. Post-scrub serialized events keep
 * the fixed taxonomy with no secret canaries. Healthy retry recovers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R52L01AccountBootstrapProviderTest {
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
    fun providerConfigurationReportsCredentialProviderAndRecovers() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        assertEquals(AuthStatus.SignedOut, harness.repo.state.value.authStatus)
        harness.provider.failure = GetCredentialProviderConfigurationException(
            "no provider R52L01-SENTINEL-provider",
        )
        val before = harness.localState()
        harness.repo.signIn(harness.provider)
        assertFailureNotice(harness)
        assertEquals(before, harness.localState())
        assertFalse(harness.hasTokens())
        // Production order fetches the challenge before the provider
        // runs; rejection must stop before the token exchange and write.
        assertEquals(1, harness.service.challengeCalls)
        assertEquals(0, harness.service.exchangeCalls)
        assertSingleFixedReport(reported, "Account bootstrap sign_in failed: credential_provider")
        harness.repo.signIn(harness.provider)
        assertEquals(1, reported.size)
        assertPostScrub(reported.single(), "Account bootstrap sign_in failed: credential_provider")
        harness.provider.failure = null
        AccountBootstrapReporter.resetForTest()
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
        assertTrue(harness.hasTokens())
    }

    @Test
    fun dismissalStaysQuietAndRecovers() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        harness.provider.failure = GetCredentialCancellationException("R52L01-dismissed")
        val before = harness.localState()
        harness.repo.signIn(harness.provider)
        assertFailureNotice(harness)
        assertEquals(before, harness.localState())
        assertTrue(reported.isEmpty())
        assertEquals(0, harness.service.exchangeCalls)
        harness.provider.failure = null
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
    }

    @Test
    fun interruptionStaysQuietAndRecovers() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        harness.provider.failure = GetCredentialInterruptedException("R52L01-interrupted")
        harness.repo.signIn(harness.provider)
        assertFailureNotice(harness)
        assertTrue(reported.isEmpty())
        harness.provider.failure = null
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
    }

    @Test
    fun offlineChallengeStaysQuietAndRecovers() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        harness.service.challengeFailure = IOException("R52L01-offline")
        harness.repo.signIn(harness.provider)
        assertFailureNotice(harness)
        assertTrue(reported.isEmpty())
        assertEquals(1, harness.service.challengeCalls)
        assertEquals(0, harness.service.exchangeCalls)
        assertFalse(harness.hasTokens())
        harness.service.challengeFailure = null
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
    }

    @Test
    fun coroutineCancellationPropagatesWithoutNotice() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        harness.provider.failure = CancellationException("R52L01-gone")
        val thrown = runCatching { harness.repo.signIn(harness.provider) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(reported.isEmpty())
        assertNull(harness.repo.state.value.notice)
        harness.provider.failure = null
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
    }

    @Test
    fun credentialStoreWriteFailureReportsCredentialWriteAndRecovers() = runBlocking {
        val reported = captureReports()
        val harness = harness()
        harness.repo.initialize()
        harness.store.writeFailure = IOException("R52L01-SENTINEL-store")
        val before = harness.localState()
        harness.repo.signIn(harness.provider)
        assertFailureNotice(harness)
        assertEquals(before, harness.localState())
        assertFalse(harness.hasTokens())
        assertEquals(1, harness.service.exchangeCalls)
        assertEquals(1, harness.store.writeCalls)
        assertSingleFixedReport(reported, "Account bootstrap sign_in failed: credential_write")
        assertPostScrub(reported.single(), "Account bootstrap sign_in failed: credential_write")
        harness.store.writeFailure = null
        AccountBootstrapReporter.resetForTest()
        harness.repo.signIn(harness.provider)
        awaitSignedIn(harness.repo)
    }

    private fun captureReports(): MutableList<Throwable> {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
        return reported
    }

    private fun assertFailureNotice(harness: Harness) {
        assertEquals(AuthStatus.SignedOut, harness.repo.state.value.authStatus)
        assertNotNull(harness.repo.state.value.notice)
    }

    private fun assertSingleFixedReport(reported: List<Throwable>, fixed: String) {
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountBootstrapReport)
        assertEquals(fixed, single.message)
        assertTrue(single.cause == null)
    }

    private fun assertPostScrub(report: Throwable, fixed: String) {
        val event = SentryEvent(report).apply {
            message = Message().apply { formatted = report.message }
        }
        SentryScrubber.scrubEvent(event)
        assertEquals(fixed, event.message?.formatted)
        val writer = StringWriter()
        JsonSerializer(SentryOptions()).serialize(event, writer)
        val serialized = writer.toString()
        assertTrue(serialized.contains(fixed))
        assertCanariesAbsent(serialized)
    }

    private fun assertCanariesAbsent(serialized: String) {
        listOf("R52L01-SENTINEL", "id-token-R52L01", "access-R52L01", "refresh-R52L01").forEach {
            assertFalse("Leaked $it in scrubbed event", serialized.contains(it))
        }
    }

    private suspend fun awaitSignedIn(repo: TimerRepository) {
        val done = withTimeoutOrNull(15_000) {
            while (repo.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private fun harness(): Harness {
        val service = FakeSignInService()
        val store = FakeTokenStore()
        val provider = FakeCredentialProvider()
        val auth = AuthRepository(service, store, "client-id")
        val dao = database.timerDao()
        val repo = TimerRepository(
            context = context,
            dao = dao,
            api = service,
            auth = auth,
            json = json,
            networkAvailable = { true },
        ).also(repositories::add)
        return Harness(repo, dao, service, store, provider)
    }

    private data class Harness(
        val repo: TimerRepository,
        val dao: TimerDao,
        val service: FakeSignInService,
        val store: FakeTokenStore,
        val provider: FakeCredentialProvider,
    ) {
        suspend fun localState(): LocalStateEntity? = dao.localState()

        fun hasTokens(): Boolean = store.tokens != null
    }

    private class FakeTokenStore : TokenStore {
        var tokens: TokenPair? = null
        var writeFailure: Throwable? = null
        var writeCalls = 0

        override fun read(): TokenPair? = tokens

        override fun write(tokens: TokenPair) {
            writeCalls += 1
            writeFailure?.let { throw it }
            this.tokens = tokens
        }

        override fun clear() {
            tokens = null
        }
    }

    private class FakeCredentialProvider(
        var token: String = "id-token-R52L01-SENTINEL",
        var failure: Throwable? = null,
    ) : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String): String {
            failure?.let { throw it }
            return token
        }
    }

    private class FakeSignInService(
        val profile: User = User("account-1", "a1@example.test", "A", ""),
    ) : PomodoroughService {
        var challengeCalls = 0
        var challengeFailure: Throwable? = null
        var exchangeCalls = 0
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

        override suspend fun createChallenge(): NativeChallenge {
            challengeCalls += 1
            challengeFailure?.let { throw it }
            return NativeChallenge("challenge", "nonce", "2026-01-01T00:05:00Z")
        }

        override suspend fun exchange(request: NativeExchangeRequest): TokenPair {
            exchangeCalls += 1
            return TokenPair("access-R52L01", "2999-01-01T00:00:00Z", "refresh-R52L01", "2999-02-01T00:00:00Z")
        }

        override suspend fun refresh(refreshToken: String): TokenPair = error("unused")
        override suspend fun me(accessToken: String) = MeResponse(profile, "")
        override suspend fun bootstrap(accessToken: String) = empty
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("unused")
    }
}
