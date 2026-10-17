package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
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
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R49-A01 with real Room: failed phase write retains committed memory.
 * Covers DAO failure, same-choice retry, next Start, publication,
 * reopen, and one bounded infrastructure diagnostic.
 */
@RunWith(AndroidJUnit4::class)
class R49A01PhaseSelectionPersistenceTest {
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
        PhaseSelectionReporter.resetForTest()
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
        PhaseSelectionReporter.resetForTest()
    }

    @Test
    fun failedWriteRetainsCommittedMemoryAndReportsOnce() = runBlocking {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        PhaseSelectionReporter.resetForTest()
        val dao = FailingPhaseDao(database.timerDao())
        dao.failUpdateState = true
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val durableBefore = requireNotNull(dao.delegate.localState())

        repository.selectPhase(TimerPhase.LongBreak)
        repository.selectPhase(TimerPhase.LongBreak)

        val durable = requireNotNull(dao.delegate.localState())
        assertEquals(durableBefore.settingsJson, durable.settingsJson)
        assertEquals(TimerPhase.Focus, decodePhase(durable))
        assertEquals(TimerPhase.Focus, repository.state.value.settings.selectedPhase)
        assertEquals(2, dao.updateCalls)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is PhaseSelectionReport)
        assertEquals(
            context.getString(R.string.sync_stopped_after_a_local_failure),
            repository.state.value.notice,
        )
    }

    @Test
    fun sameChoiceRetryPersistsAfterRestore() = runBlocking {
        quietReports()
        val dao = FailingPhaseDao(database.timerDao())
        dao.failUpdateState = true
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val generationBefore = generation(repository)

        repository.selectPhase(TimerPhase.LongBreak)
        assertEquals(generationBefore, generation(repository))
        dao.failUpdateState = false

        repository.selectPhase(TimerPhase.LongBreak)

        val durable = requireNotNull(dao.delegate.localState())
        assertEquals(TimerPhase.LongBreak, decodePhase(durable))
        assertEquals(TimerPhase.LongBreak, repository.state.value.settings.selectedPhase)
        assertEquals(2, dao.updateCalls)
        assertEquals(generationBefore + 1, generation(repository))
    }

    @Test
    fun nextStartUsesCommittedPhase() = runBlocking {
        quietReports()
        val dao = FailingPhaseDao(database.timerDao())
        dao.failUpdateState = true
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)

        repository.selectPhase(TimerPhase.LongBreak)
        repository.toggleTimer()
        val firstStarts = dao.delegate.pendingCommands()
            .map { it.toModel() }.filter { it.type == CommandType.Start }
        assertEquals(listOf(TimerPhase.Focus), firstStarts.map { it.phase })

        repository.cancelAndClearTimer()
        dao.failUpdateState = false
        repository.selectPhase(TimerPhase.LongBreak)
        repository.toggleTimer()
        val starts = dao.delegate.pendingCommands()
            .map { it.toModel() }.filter { it.type == CommandType.Start }
        assertEquals(listOf(TimerPhase.Focus, TimerPhase.LongBreak), starts.map { it.phase })
    }

    @Test
    fun successfulRetryClearsStaleNoticeAndPublishesNewPhase() = runBlocking {
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        PhaseSelectionReporter.resetForTest()
        val dao = FailingPhaseDao(database.timerDao())
        dao.failUpdateState = true
        val repository = repository(dao)
        repository.initialize()
        awaitSignedIn(repository)
        val generationBefore = generation(repository)

        repository.selectPhase(TimerPhase.LongBreak)
        assertEquals(TimerPhase.Focus, repository.state.value.settings.selectedPhase)
        assertEquals(
            context.getString(R.string.sync_stopped_after_a_local_failure),
            repository.state.value.notice,
        )

        dao.failUpdateState = false
        repository.selectPhase(TimerPhase.LongBreak)

        val durable = requireNotNull(dao.delegate.localState())
        assertEquals(TimerPhase.LongBreak, decodePhase(durable))
        assertEquals(TimerPhase.LongBreak, repository.state.value.settings.selectedPhase)
        assertEquals(null, repository.state.value.notice)
        assertEquals(generationBefore + 1, generation(repository))
        assertEquals(2, dao.updateCalls)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is PhaseSelectionReport)

        repository.shutdownForTest()
        val reopened = repository(dao)
        reopened.initialize()
        awaitSignedIn(reopened)
        assertEquals(TimerPhase.LongBreak, reopened.state.value.settings.selectedPhase)
    }

    @Test
    fun reopenRestoresCommittedPhase() = runBlocking {
        quietReports()
        val dao = FailingPhaseDao(database.timerDao())
        dao.failUpdateState = true
        val first = repository(dao)
        first.initialize()
        awaitSignedIn(first)
        first.selectPhase(TimerPhase.LongBreak)
        first.shutdownForTest()

        val reopenedAfterFailure = repository(dao)
        reopenedAfterFailure.initialize()
        awaitSignedIn(reopenedAfterFailure)
        assertEquals(TimerPhase.Focus, reopenedAfterFailure.state.value.settings.selectedPhase)
        reopenedAfterFailure.shutdownForTest()

        dao.failUpdateState = false
        val retry = repository(dao)
        retry.initialize()
        awaitSignedIn(retry)
        retry.selectPhase(TimerPhase.LongBreak)
        retry.shutdownForTest()

        val reopenedAfterRetry = repository(dao)
        reopenedAfterRetry.initialize()
        awaitSignedIn(reopenedAfterRetry)
        assertEquals(TimerPhase.LongBreak, reopenedAfterRetry.state.value.settings.selectedPhase)
    }

    private fun quietReports() {
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        PhaseSelectionReporter.resetForTest()
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
            api = FakePhaseService(account),
            auth = FakePhaseAuth(),
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

    private fun decodePhase(local: LocalStateEntity): String {
        return json.decodeFromString<TimerSettings>(local.settingsJson).selectedPhase
    }

    private fun generation(repository: TimerRepository): Long {
        val field = TimerRepository::class.java.getDeclaredField("selectedPhaseGeneration")
        field.isAccessible = true
        return field.getLong(repository)
    }

    private class FailingPhaseDao(val delegate: TimerDao) : TimerDao by delegate {
        var failUpdateState = false
        var updateCalls = 0

        override suspend fun updateState(state: LocalStateEntity) {
            updateCalls += 1
            if (failUpdateState) throw IOException("phase updateState failed")
            delegate.updateState(state)
        }
    }

    private class FakePhaseAuth : AuthSession {
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

    private class FakePhaseService(val profile: User) : PomodoroughService {
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
