package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import org.junit.Assert.assertEquals

/**
 * How a test startup ends without a local workspace. [reportMessage] is the one bounded report
 * that startup makes; the Sentry message text is a contract, so it is spelled out here.
 */
internal enum class StartupWithoutWorkspace(val reportMessage: String) {
    /** R54-A02: the first local-state read fails, and startup stays open for a retry. */
    LocalStateRead("Local storage read failed: workspace state"),

    /** The projection runtime fails before any read, and startup completes as failed. */
    ProjectionRuntime("Shared Core projection failed at startup"),
}

/**
 * R54-A08 and R54-A09: a production repository on real Room whose startup leaves the local
 * workspace unset. Only the failing read or Core runtime, the server and the token vault are
 * faked. Durable rows are seeded with one synthetic account and a task or a running timer.
 */
internal class StartupWithoutWorkspaceHarness {
    val reported = CopyOnWriteArrayList<Throwable>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
    private val repositories = mutableListOf<TimerRepository>()
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    fun open() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
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

    fun close() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        CrashReporter.delegate = previousDelegate ?: CrashReporter.delegate
        CrashReportingRuntime.reportingEnabled = previousEnabled
        CorruptStateReporter.resetForTest()
    }

    /** Adds one task through a healthy repository and returns the durable rows. */
    suspend fun seedTask(title: String): Durable = seed { repo ->
        check(repo.addTask(title)) { "seed task was not saved" }
    }.also { check(it.taskOperations.isNotEmpty()) { "seed must persist pending work" } }

    /** Starts a focus timer through a healthy repository and returns the durable rows. */
    suspend fun seedRunningTimer(): Durable = seed { repo ->
        repo.toggleTimer()
        check(repo.state.value.timer?.status == TimerStatus.Running) { "seed timer is not running" }
    }.also { check(it.commands.isNotEmpty()) { "seed must persist the start command" } }

    private suspend fun seed(change: suspend (TimerRepository) -> Unit): Durable {
        val seed = repository(database.timerDao())
        seed.initialize()
        change(seed)
        seed.shutdownForTest()
        repositories.remove(seed)
        return durable()
    }

    /** Starts a repository that ends startup without a local workspace. */
    suspend fun start(failure: StartupWithoutWorkspace): FailedStartup {
        val dao = StartupReadDao(database.timerDao())
        if (failure == StartupWithoutWorkspace.LocalStateRead) dao.stateFailure = IOException("r54 disk read")
        val core: ((String, String) -> JsonElement)? = when (failure) {
            StartupWithoutWorkspace.LocalStateRead -> null
            StartupWithoutWorkspace.ProjectionRuntime -> { _, _ -> throw IllegalStateException("r54 core runtime") }
        }
        val repo = repository(dao, core)
        repo.initialize()
        if (failure == StartupWithoutWorkspace.ProjectionRuntime) {
            check(repo.state.value.notice == context.getString(R.string.shared_core_invalid_output)) {
                "startup must fail in the projection runtime"
            }
        }
        return FailedStartup(repo, dao)
    }

    /**
     * A repository whose projection runtime check throws what [failure] returns at the time of
     * the check, so one test can cancel a startup and then fail the next one.
     */
    fun repositoryWithProjectionFailure(failure: () -> Exception): TimerRepository =
        repository(database.timerDao()) { _, _ -> throw failure() }

    /**
     * Asserts the startup screen [failure] leaves. A read failure holds startup on the retry
     * screen. A failed projection runtime completes startup as failed, with its notice, as before
     * R54-A08; only its actions are refused.
     */
    fun assertStartupScreen(failure: StartupWithoutWorkspace, state: AppState) {
        val completesStartup = failure == StartupWithoutWorkspace.ProjectionRuntime
        assertEquals("startup ready after $failure", completesStartup, state.ready)
        assertEquals("retry screen after $failure", !completesStartup, state.localStorageReadFailed)
    }

    /** The early returns after a failed startup rely on its one bounded report, and add none. */
    fun assertStartupReport(failure: StartupWithoutWorkspace) {
        assertEquals("reports after $failure", listOf(failure.reportMessage), reported.map { it.message })
    }

    fun durable(): Durable = runBlocking {
        val dao = database.timerDao()
        Durable(
            local = requireNotNull(dao.localState()),
            taskOperations = dao.pendingTaskOperations().map { it.id },
            selectedTaskOperations = dao.pendingSelectedTaskOperations().map { it.id },
            commands = dao.pendingCommands().map { it.id },
        )
    }

    private fun repository(dao: TimerDao, core: ((String, String) -> JsonElement)? = null) = TimerRepository(
        context = context,
        dao = dao,
        api = OfflineService(),
        auth = ActiveAuth(),
        json = json,
        networkAvailable = { false },
        sharedCoreDispatch = core,
    ).also(repositories::add)

    class FailedStartup(val repository: TimerRepository, private val dao: StartupReadDao) {
        /** Lets the local-state read succeed again, for a later startup retry. */
        fun recoverStorage() {
            dao.stateFailure = null
        }
    }

    data class Durable(
        val local: LocalStateEntity,
        val taskOperations: List<String>,
        val selectedTaskOperations: List<String>,
        val commands: List<String>,
    )

    class StartupReadDao(private val delegate: TimerDao) : TimerDao by delegate {
        @Volatile var stateFailure: Exception? = null

        override suspend fun localState(): LocalStateEntity? {
            stateFailure?.let { throw it }
            return delegate.localState()
        }
    }

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
        override fun clear() = error("startup must not clear credentials")
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
}
