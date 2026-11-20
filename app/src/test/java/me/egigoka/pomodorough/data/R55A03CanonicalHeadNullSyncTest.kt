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
 * R55-A03 L1: a sync response whose canonical pair holds an explicit JSON
 * null fails closed, as a lone field does. Only a missing key means an older
 * server. The body is decoded with the application's API decoder, as
 * PomodoroughApi does, so the repository sees exactly what production sees:
 * the sync stops with an error, and the stored head and the pending queues
 * stay as they were. Accepting the null pair would install the server clock
 * as the head and drop the acknowledged operations.
 *
 * R55-A04: an integer field that is not a plain JSON integer literal (a
 * quoted number, an exponent, -0) fails the same way. No case sends a crash
 * report: a malformed reply is a terminal sync failure, not an app defect.
 */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R55A03CanonicalHeadNullSyncTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val service = NullPairService(json)
    private val repositories = mutableListOf<TimerRepository>()
    private val reports = CopyOnWriteArrayList<Throwable>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        CrashReporter.delegate = { reports += it }
        CrashReportingRuntime.reportingEnabled = true
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            database.close()
        }
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun aNullCounterStopsTheSyncAndKeepsTheHeadAndThePendingQueues() = runBlocking {
        service.rowSyncOverrides = mapOf("canonicalHlcWallMs" to JsonPrimitive(HeadWall), "canonicalHlcCounter" to JsonNull)

        assertSyncFailsClosed(NullFieldError)
    }

    @Test
    fun aNullPairStopsTheSyncInsteadOfFallingBackToTheServerClock() = runBlocking {
        service.rowSyncOverrides = mapOf("canonicalHlcWallMs" to JsonNull, "canonicalHlcCounter" to JsonNull)

        assertSyncFailsClosed(NullFieldError)
    }

    @Test
    fun aQuotedServerClockStopsTheSync() = runBlocking {
        service.rowSyncOverrides = NewerPair + ("serverHlcWallMs" to JsonPrimitive(ServerWall.toString()))

        assertSyncFailsClosed(literalError("serverHlcWallMs"))
    }

    @Test
    fun anExponentCanonicalCounterStopsTheSync() = runBlocking {
        service.rowSyncOverrides = NewerPair + ("canonicalHlcCounter" to JsonUnquotedLiteral("0e0"))

        assertSyncFailsClosed(literalError("canonicalHlcCounter"))
    }

    @Test
    fun aNegativeZeroRevisionStopsTheSync() = runBlocking {
        service.rowSyncOverrides = NewerPair + ("revision" to JsonUnquotedLiteral("-0"))

        assertSyncFailsClosed(literalError("revision"))
    }

    private suspend fun assertSyncFailsClosed(expectedConflict: String) {
        val repository = readyRepository()
        assertEquals(HeadWall to 0L, storedHead())

        assertTrue("add task", repository.addTask("Alpha"))
        awaitCondition("the sync carrying the task settles") {
            service.rowRequests.isNotEmpty() &&
                (repository.state.value.conflict != null || repository.state.value.syncStatus == SyncStatus.Synced)
        }

        val sent = service.rowRequests.single()
        assertEquals("the stored head is unchanged", HeadWall to 0L, storedHead())
        assertEquals("the pending queues are unchanged", sentIds(sent).sorted(), pendingIds().sorted())
        assertEquals(expectedConflict, repository.state.value.conflict)
        assertEquals("no crash report", emptyList<Throwable>(), reports.toList())
    }

    private suspend fun readyRepository(): TimerRepository {
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = "device-1",
                settingsJson = json.encodeToString(TimerSettings()),
                userJson = json.encodeToString(Account),
                ownerUserId = Account.id,
            ),
        )
        val repository = TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = service,
            auth = SignedInAuth,
            json = json,
            networkAvailable = { true },
            currentTimeMillis = { ServerWall },
        ).also(repositories::add)
        repository.initialize()
        awaitCondition("the bootstrap installs the canonical head") {
            repository.state.value.authStatus == AuthStatus.SignedIn &&
                runBlocking { storedHead() } == HeadWall to 0L
        }
        return repository
    }

    private suspend fun storedHead(): Pair<Long?, Long?> {
        val local = requireNotNull(database.timerDao().localState())
        return local.safeCanonicalHeadWallMs to local.safeCanonicalHeadCounter
    }

    private suspend fun pendingIds(): List<String> {
        val dao = database.timerDao()
        return dao.pendingCommands().map { it.id } + dao.pendingTaskOperations().map { it.id } +
            dao.pendingDurationOperations().map { it.id } + dao.pendingAutoStartOperations().map { it.id } +
            dao.pendingSelectedTaskOperations().map { it.id }
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private object SignedInAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    /**
     * Answers every request with a valid body that carries the canonical pair
     * (HeadWall, 0). A sync that carries rows acknowledges all of them as
     * applied and overrides the body's fields with [rowSyncOverrides].
     */
    private class NullPairService(private val json: Json) : PomodoroughService {
        private val wireJson = Json(from = json) { encodeDefaults = true; explicitNulls = true }
        val rowRequests = CopyOnWriteArrayList<SyncRequest>()
        @Volatile var rowSyncOverrides: Map<String, JsonElement> = emptyMap()

        override suspend fun me(accessToken: String) = MeResponse(Account, "")
        override suspend fun bootstrap(accessToken: String) = decode(serverState(revision = 0), HeadPair)
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            decode(serverState(revision = 0), HeadPair)

        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
            if (sentIds(request).isEmpty()) return decode(serverState(revision = 0), HeadPair)
            rowRequests += request
            return decode(acknowledged(request), rowSyncOverrides)
        }

        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource = SilentStream

        // The body goes through the API decoder, as PomodoroughApi.executeJson does.
        private fun decode(response: SyncResponse, pair: Map<String, JsonElement>): SyncResponse {
            val body = JsonObject(wireJson.encodeToJsonElement(SyncResponse.serializer(), response).jsonObject + pair)
            return json.decodeServerResponse(SyncResponse.serializer(), body.toString())
        }

        private fun acknowledged(request: SyncRequest) = serverState(revision = 1).copy(
            acknowledgements = request.commands.map { Acknowledgement(it.id, "applied") },
            taskAcknowledgements = request.taskOperations.map { TaskAcknowledgement(it.id, "applied") },
            durationAcknowledgements = request.durationOperations.map { DurationAcknowledgement(it.id, "applied") },
            autoStartAcknowledgements = request.autoStartOperations.map { AutoStartAcknowledgement(it.id, "applied") },
            selectedTaskAcknowledgements = request.selectedTaskOperations.map {
                SelectedTaskAcknowledgement(it.id, "applied")
            },
            tasks = request.taskOperations.filter { it.type == TaskOperationType.Upsert }
                .map { FocusTask(it.taskId, requireNotNull(it.title)) },
            selectedTaskId = request.selectedTaskOperations.lastOrNull()?.taskId,
        )

        private fun serverState(revision: Long) = SyncResponse(
            acknowledgements = emptyList(),
            revision = revision,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = ServerTime,
            serverHlcWallMs = ServerWall,
            serverHlcCounter = ServerCounter,
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
        val Account = User("account-1", "a1@example.test", "A", "")
        const val ServerTime = "2026-07-20T00:06:00Z"
        const val ServerCounter = 2L
        const val NullFieldError = "Canonical HLC head field must be an integer, not null"
        val ServerWall = Instant.parse(ServerTime).toEpochMilli()
        val HeadWall = ServerWall - 60_000L
        val HeadPair = mapOf("canonicalHlcWallMs" to JsonPrimitive(HeadWall), "canonicalHlcCounter" to JsonPrimitive(0L))

        // A valid newer head, so a row sync that is wrongly accepted moves the stored head.
        val NewerPair = mapOf(
            "canonicalHlcWallMs" to JsonPrimitive(HeadWall + 30_000L),
            "canonicalHlcCounter" to JsonPrimitive(0L),
        )

        fun literalError(field: String) = "Server field response.$field must be a plain JSON integer"

        fun sentIds(request: SyncRequest): List<String> =
            request.commands.map { it.id } + request.taskOperations.map { it.id } +
                request.durationOperations.map { it.id } + request.autoStartOperations.map { it.id } +
                request.selectedTaskOperations.map { it.id }
    }
}
