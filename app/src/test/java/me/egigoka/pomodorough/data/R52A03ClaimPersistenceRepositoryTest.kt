package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
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
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.sse.EventSourceListener
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
 * R52-A03: a refused claim-proof commit must throw out of the real
 * repository path, never read as successful empty work. DAO-seam fault
 * on the never-sent retirement funnel; fails if the rethrow is reverted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R52A03ClaimPersistenceRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun retireNeverSentRefusalThrowsFromRealRepository() = runBlocking {
        val dao = RetireRefusingDao(database.timerDao())
        val repo = repository(dao)
        repo.initialize()
        awaitSignedIn(repo)
        repo.addTask("R52A03 claim proof")
        awaitTask(repo, "R52A03 claim proof")
        assertTrue(dao.delegate.pendingTaskOperations().isNotEmpty())
        dao.refuse = true
        val host = repo.centralizedSyncRuntimeHost()
        val identity = SyncAttemptIdentity(host.accountGeneration(), "r52-a03-repo")
        var thrown: Throwable? = null
        var returnedNull = false
        try {
            returnedNull = host.prepareSyncAttempt(identity) == null
        } catch (error: IOException) {
            thrown = error
        }
        assertEquals("R52A03-retire-refused", thrown?.message)
        assertFalse("refused commit must throw, never read as empty success", returnedNull)
        assertEquals("R52A03-retire-refused", thrown?.message)
    }

    private fun repository(dao: TimerDao): TimerRepository {
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
            api = FakeClaimService(account),
            auth = FakeClaimAuth(),
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private suspend fun awaitSignedIn(repo: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private suspend fun awaitTask(repo: TimerRepository, title: String) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.tasks.none { it.title == title }) delay(10)
            true
        }
        assertTrue("timed out waiting for task", done == true)
    }

    private class RetireRefusingDao(val delegate: TimerDao) : TimerDao by delegate {
        var refuse = false

        override suspend fun retireNeverSent(
            commandIds: List<String>,
            taskIds: List<String>,
            durationIds: List<String>,
            autoStartIds: List<String>,
            selectedIds: List<String>,
        ) {
            if (refuse) throw IOException("R52A03-retire-refused")
            delegate.retireNeverSent(commandIds, taskIds, durationIds, autoStartIds, selectedIds)
        }
    }

    private class FakeClaimAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FakeClaimService(val profile: User) : PomodoroughService {
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
        override fun revisionStream(accessToken: String, listener: EventSourceListener) = error("unused")
    }
}
