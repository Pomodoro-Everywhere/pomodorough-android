package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.api.decodeServerResponse
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R55-A03 L1-r1 (adopted from the ver-R55-A03-r1 probe): a bootstrap body
 * with a null canonical field fails restore and sign-in exactly like a body
 * whose serverHlcWallMs is null. Same auth status, same notice, stored head
 * and pending queues unchanged, no crash report.
 *
 * R55-A04: an integer field that is not a plain JSON integer literal fails
 * the same way. The body goes through the API decoder, as
 * PomodoroughApi.executeJson does.
 */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R55A03CanonicalHeadNullBootstrapTest {
    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val reports = CopyOnWriteArrayList<Throwable>()
    private val repositories = mutableListOf<TimerRepository>()
    private val databases = mutableListOf<PomodoroughDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashReporter.delegate = { reports += it }
        CrashReportingRuntime.reportingEnabled = true
    }

    @After
    fun tearDown() {
        runBlocking { repositories.asReversed().forEach { it.shutdownForTest() } }
        databases.forEach { it.close() }
        AccountBootstrapReporter.resetForTest()
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun restoreWithANullPairFailsLikeANullServerClock() = runBlocking {
        val reference = restoreOutcome(NullServerClock)
        assertEquals(AuthStatus.SignedOut, reference.auth)
        assertEquals(context.getString(R.string.account_restore_failed_unreadable_reply), reference.notice)
        assertEquals("the seeded head is unchanged", SeedWall to 0L, reference.head)
        assertEquals("no crash report", emptyList<String>(), reference.reports)
        for ((label, overrides) in NullVariants) {
            assertEquals(label, reference, restoreOutcome(overrides))
        }
        assertEquals(AuthStatus.SignedIn, restoreOutcome(HeadPair).auth)
    }

    @Test
    fun signInWithANullPairFailsLikeANullServerClock() = runBlocking {
        val reference = signInOutcome(NullServerClock)
        assertEquals(AuthStatus.SignedOut, reference.auth)
        assertEquals(context.getString(R.string.sign_in_failed_unreadable_reply), reference.notice)
        assertEquals("no head is installed", null to null, reference.head)
        assertEquals("no crash report", emptyList<String>(), reference.reports)
        for ((label, overrides) in NullVariants) {
            assertEquals(label, reference, signInOutcome(overrides))
        }
        assertEquals(AuthStatus.SignedIn, signInOutcome(HeadPair).auth)
    }

    @Test
    fun restoreWithANonIntegerLiteralFailsLikeANullServerClock() = runBlocking {
        val reference = restoreOutcome(NullServerClock)
        for ((label, overrides) in LiteralVariants) {
            assertEquals(label, reference, restoreOutcome(overrides))
        }
    }

    @Test
    fun signInWithANonIntegerLiteralFailsLikeANullServerClock() = runBlocking {
        val reference = signInOutcome(NullServerClock)
        for ((label, overrides) in LiteralVariants) {
            assertEquals(label, reference, signInOutcome(overrides))
        }
    }

    private data class Outcome(
        val auth: AuthStatus,
        val notice: String?,
        val conflict: String?,
        val head: Pair<Long?, Long?>,
        val pending: List<String>,
        val reports: List<String>,
    )

    private suspend fun restoreOutcome(overrides: Map<String, JsonElement>): Outcome {
        val database = freshDatabase(signedIn = true)
        val repository = repository(database, FakeAuth(tokens = true), overrides)
        repository.initialize()
        awaitSettled(repository)
        return outcome(database, repository)
    }

    private suspend fun signInOutcome(overrides: Map<String, JsonElement>): Outcome {
        val database = freshDatabase(signedIn = false)
        val auth = FakeAuth(tokens = false)
        val repository = repository(database, auth, overrides)
        repository.initialize()
        awaitCondition("signed out start") { repository.state.value.authStatus == AuthStatus.SignedOut }
        auth.tokens = true
        repository.signIn(NoCredentialProvider)
        awaitSettled(repository)
        return outcome(database, repository)
    }

    private fun repository(
        database: PomodoroughDatabase,
        auth: FakeAuth,
        overrides: Map<String, JsonElement>,
    ) = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = BootstrapService(json, overrides),
        auth = auth,
        json = json,
        networkAvailable = { true },
        currentTimeMillis = { ServerWall },
    ).also(repositories::add)

    private suspend fun freshDatabase(signedIn: Boolean): PomodoroughDatabase {
        reports.clear()
        AccountBootstrapReporter.resetForTest()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build().also(databases::add)
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = "device-1",
                settingsJson = json.encodeToString(TimerSettings()),
                userJson = if (signedIn) json.encodeToString(Account) else null,
                ownerUserId = if (signedIn) Account.id else null,
                safeCanonicalHeadWallMs = if (signedIn) SeedWall else null,
                safeCanonicalHeadCounter = if (signedIn) 0L else null,
            ),
        )
        return database
    }

    private suspend fun outcome(database: PomodoroughDatabase, repository: TimerRepository): Outcome {
        val dao = database.timerDao()
        val local = requireNotNull(dao.localState())
        val pending = dao.pendingCommands().map { it.id } + dao.pendingTaskOperations().map { it.id } +
            dao.pendingDurationOperations().map { it.id } + dao.pendingAutoStartOperations().map { it.id } +
            dao.pendingSelectedTaskOperations().map { it.id }
        val state = repository.state.value
        return Outcome(
            auth = state.authStatus,
            notice = state.notice,
            conflict = state.conflict,
            head = local.safeCanonicalHeadWallMs to local.safeCanonicalHeadCounter,
            pending = pending.sorted(),
            reports = reports.map { "${it.javaClass.simpleName}: ${it.message}" },
        )
    }

    private suspend fun awaitSettled(repository: TimerRepository) {
        awaitCondition("restore or sign-in settles") {
            val state = repository.state.value
            state.authStatus == AuthStatus.SignedIn ||
                (state.authStatus == AuthStatus.SignedOut && state.notice != null)
        }
        // A write or report that lands after the notice must still change the outcome.
        delay(LateWriteWindowMs)
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private class FakeAuth(@Volatile var tokens: Boolean) : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair {
            tokens = true
            return TokenPair("a", "2026-07-21T00:00:00Z", "r", "2026-08-20T00:00:00Z")
        }
        override fun hasTokens() = tokens
        override fun credentialState() = if (tokens) AuthCredentialState.Active else AuthCredentialState.Empty
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() {
            tokens = false
        }
    }

    private object NoCredentialProvider : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String) = "id-token"
    }

    /** Answers bootstrap and resolution with the base body plus [overrides]; sync with a valid body. */
    private class BootstrapService(
        private val json: Json,
        private val overrides: Map<String, JsonElement>,
    ) : PomodoroughService {
        private val wireJson = Json(from = json) { encodeDefaults = true; explicitNulls = true }

        override suspend fun me(accessToken: String) = MeResponse(Account, "")
        override suspend fun bootstrap(accessToken: String) = decode(overrides)
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            decode(overrides)
        override suspend fun sync(accessToken: String, request: SyncRequest) = decode(HeadPair)
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource = SilentStream

        // The base body has no canonical keys; each case adds the ones it needs.
        private fun decode(fields: Map<String, JsonElement>): SyncResponse {
            val base = wireJson.encodeToJsonElement(SyncResponse.serializer(), serverState()).jsonObject -
                setOf("canonicalHlcWallMs", "canonicalHlcCounter")
            return json.decodeServerResponse(SyncResponse.serializer(), JsonObject(base + fields).toString())
        }

        private fun serverState() = SyncResponse(
            acknowledgements = emptyList(),
            revision = 0,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = ServerTime,
            serverHlcWallMs = ServerWall,
            serverHlcCounter = 2L,
            durationAcknowledgements = emptyList(),
            durationsMs = DurationsMs(),
            taskAcknowledgements = emptyList(),
            tasks = emptyList(),
        )
    }

    private object SilentStream : EventSource {
        override fun request(): Request = Request.Builder().url("https://example.test/revisions").build()
        override fun cancel() = Unit
    }

    private companion object {
        const val LateWriteWindowMs = 300L
        val Account = User("account-1", "a1@example.test", "A", "")
        const val ServerTime = "2026-07-20T00:06:00Z"
        val ServerWall = Instant.parse(ServerTime).toEpochMilli()
        val HeadWall = ServerWall - 60_000L
        val SeedWall = ServerWall - 120_000L
        val HeadPair = mapOf("canonicalHlcWallMs" to JsonPrimitive(HeadWall), "canonicalHlcCounter" to JsonPrimitive(0L))

        // Today's malformed reply: the reference every variant must match.
        val NullServerClock = HeadPair + ("serverHlcWallMs" to JsonNull)

        val NullVariants = listOf(
            "both null" to mapOf("canonicalHlcWallMs" to JsonNull, "canonicalHlcCounter" to JsonNull),
            "counter null" to mapOf("canonicalHlcWallMs" to JsonPrimitive(HeadWall), "canonicalHlcCounter" to JsonNull),
            "wall null alone" to mapOf<String, JsonElement>("canonicalHlcWallMs" to JsonNull),
        )

        val LiteralVariants = listOf(
            "quoted server wall" to HeadPair + ("serverHlcWallMs" to JsonPrimitive(ServerWall.toString())),
            "exponent server counter" to HeadPair + ("serverHlcCounter" to JsonUnquotedLiteral("2e0")),
            "quoted canonical wall" to HeadPair + ("canonicalHlcWallMs" to JsonPrimitive(HeadWall.toString())),
            "negative zero canonical counter" to HeadPair + ("canonicalHlcCounter" to JsonUnquotedLiteral("-0")),
            "quoted revision" to HeadPair + ("revision" to JsonPrimitive("0")),
        )
    }
}
