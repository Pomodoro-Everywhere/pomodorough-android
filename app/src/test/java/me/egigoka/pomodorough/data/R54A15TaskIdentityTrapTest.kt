package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dylibso.chicory.runtime.TrapException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.core.TrappingCoreModule
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A15: the task identity callers contain a shared-core runtime trap. Production routes every
 * operation through SharedCore.dispatch, and so does this repository: task identity goes to a
 * module that traps inside Chicory, and every other operation goes to the bundled core. A trapped
 * add or delete returns without an exception, shows the unavailable notice, sends one typed
 * report and leaves the durable rows alone. A cancelled identity dispatch still reaches the caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A15TaskIdentityTrapTest {
    private val reported = CopyOnWriteArrayList<Throwable>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
    private val repositories = mutableListOf<TimerRepository>()
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var bundledCore: SharedCore
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    /** Serves task identity when set; the bundled core serves it otherwise. */
    @Volatile private var identityCore: SharedCore? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        bundledCore = SharedCore.fromAssets(context.assets)
        val state = LocalStateEntity(
            deviceId = "device-1",
            settingsJson = json.encodeToString(TimerSettings()),
            userJson = json.encodeToString(account),
            ownerUserId = account.id,
        )
        runBlocking { database.timerDao().insertState(state) }
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
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
        CorruptStateReporter.resetForTest()
    }

    @Test
    fun trappedIdentityRefusesTheAddWithoutEscaping() = runBlocking {
        val repo = startedRepository()
        val before = durable()
        identityCore = SharedCore(TrappingCoreModule.instance())

        val outcome = runCatching { repo.addTask("r54 a15 task") }

        assertEquals("the add must return false, not throw", Result.success(false), outcome)
        assertEquals(emptyList<FocusTask>(), repo.state.value.tasks)
        assertTrapContained(repo, before)
        identityCore = null
        assertTrue("a contained trap leaves the repository usable", repo.addTask("r54 a15 task"))
    }

    @Test
    fun trappedIdentityRefusesTheDeleteWithoutEscaping() = runBlocking {
        val repo = startedRepository()
        check(repo.addTask("r54 a15 task")) { "seed task was not saved" }
        val task = repo.state.value.tasks.single()
        val before = durable()
        identityCore = SharedCore(TrappingCoreModule.instance())

        val outcome = runCatching { repo.deleteTask(task.id) }

        assertEquals("the delete must return, not throw", Result.success(Unit), outcome)
        assertEquals("the task stays", listOf(task), repo.state.value.tasks)
        assertTrapContained(repo, before)
    }

    @Test
    fun cancelledIdentityDispatchReachesTheCaller() = runBlocking {
        val repo = startedRepository()
        val before = durable()
        val cancellation = CancellationException("r54 a15 cancelled identity")
        identityCore = SharedCore(TrappingCoreModule.instance(), dispatchResultOverride = { throw cancellation })

        assertSame(cancellation, runCatching { repo.addTask("r54 a15 task") }.exceptionOrNull())
        assertEquals("cancellation is not a core failure", emptyList<Throwable>(), reported.toList())
        assertEquals(before, durable())
    }

    private fun assertTrapContained(repo: TimerRepository, before: Durable) {
        assertEquals(context.getString(R.string.shared_core_unavailable), repo.state.value.notice)
        val report = reported.single()
        assertTrue("the report must be typed: $report", report is SharedCoreException.Abi)
        assertTrue("the report keeps the Chicory trap: ${report.cause}", report.cause is TrapException)
        assertEquals("durable rows must not change", before, durable())
    }

    private suspend fun startedRepository(): TimerRepository {
        val repo = TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = OfflineService(),
            auth = ActiveAuth(),
            json = json,
            networkAvailable = { false },
            sharedCoreDispatch = { operation, input ->
                val core = identityCore?.takeIf { operation == TaskIdentityOperation } ?: bundledCore
                core.dispatch(operation, input)
            },
        ).also(repositories::add)
        repo.initialize()
        check(repo.state.value.ready) { "startup must load the workspace" }
        return repo
    }

    private fun durable(): Durable = runBlocking {
        val dao = database.timerDao()
        Durable(
            local = requireNotNull(dao.localState()),
            taskOperations = dao.pendingTaskOperations().map { it.id },
            selectedTaskOperations = dao.pendingSelectedTaskOperations().map { it.id },
        )
    }

    private data class Durable(
        val local: LocalStateEntity,
        val taskOperations: List<String>,
        val selectedTaskOperations: List<String>,
    )

    private class ActiveAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) =
            error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override fun prepareLogout() = Unit
        override suspend fun logout() = Unit
        override suspend fun retryPendingLogout() = Unit
        override fun hasPendingLogout() = false
        override fun clear() = error("a core trap must not clear credentials")
    }

    private class OfflineService : PomodoroughService {
        override suspend fun me(accessToken: String): MeResponse = throw IOException("r54 offline")
        override suspend fun bootstrap(accessToken: String): SyncResponse = throw IOException("r54 offline")
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            throw IOException("r54 offline")
        override suspend fun sync(accessToken: String, request: SyncRequest) = throw IOException("r54 offline")
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("r54 revision stream offline")
    }

    private companion object {
        const val TaskIdentityOperation = "task.identity.v1"
    }
}
