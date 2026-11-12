package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A02: an ordinary storage-read failure during local initialization must
 * stay inside the repository. Startup stays incomplete and retryable, every
 * persisted row stays as stored, cancellation propagates, and each read stage
 * reports one bounded category without persisted content. Real Room, bundled
 * Core and the production repository; only the read failure, server and token
 * vault are faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class R54A02LocalStorageStartupTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
    private val reported = CopyOnWriteArrayList<Throwable>()
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

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
    fun firstStateReadFailureStaysRetryableAndPreservesWorkspace() = runBlocking {
        val seeded = seedWorkspace()
        val dao = StartupReadDao(database.timerDao()).apply { stateFailure = IOException("r54 a02 disk read") }
        val repo = repository(dao)

        repo.initialize()

        assertFalse("startup must stay open after a read failure", repo.state.value.ready)
        assertEquals(seeded, durable())
        assertReports("Local storage read failed: workspace state")
        dao.stateFailure = null
        repo.initialize()
        assertStartedWith(repo, seeded)
        assertEquals(seeded, durable())
        assertReports("Local storage read failed: workspace state")
    }

    @Test
    fun pendingQueueReadFailureStaysRetryable() = runBlocking {
        val seeded = seedWorkspace()
        val dao = StartupReadDao(database.timerDao()).apply { queueFailure = IOException("r54 a02 queue read") }
        val repo = repository(dao)

        repo.initialize()

        assertFalse(repo.state.value.ready)
        assertEquals(seeded, durable())
        assertReports("Local storage read failed: pending queues")
        dao.queueFailure = null
        repo.initialize()
        assertStartedWith(repo, seeded)
    }

    @Test
    fun bootstrapResolutionReadFailureStaysRetryable() = runBlocking {
        val seeded = seedWorkspace()
        val dao = StartupReadDao(database.timerDao()).apply {
            resolutionFailure = IllegalStateException("r54 a02 cursor window")
        }
        val repo = repository(dao)

        repo.initialize()

        assertFalse(repo.state.value.ready)
        assertReports("Local storage read failed: pending bootstrap resolution")
        dao.resolutionFailure = null
        repo.initialize()
        assertStartedWith(repo, seeded)
    }

    @Test
    fun repeatedFailuresReportOnceAndEachRetryReadsAgain() = runBlocking {
        val seeded = seedWorkspace()
        val dao = StartupReadDao(database.timerDao()).apply { stateFailure = IOException("r54 a02 disk read") }
        val repo = repository(dao)

        repeat(3) { repo.initialize() }

        assertEquals("each initialize() reads storage again", 3, dao.stateReads.get())
        assertFalse(repo.state.value.ready)
        assertReports("Local storage read failed: workspace state")
        dao.stateFailure = null
        repo.initialize()
        assertStartedWith(repo, seeded)
        assertTrue("a recovered workspace accepts edits", repo.addTask("r54 a02 after retry"))
    }

    @Test
    fun failedStartupReopensWithFreshRepository() = runBlocking {
        val seeded = seedWorkspace()
        val failing = repository(StartupReadDao(database.timerDao()).apply { stateFailure = IOException("r54 a02 disk read") })
        failing.initialize()
        assertFalse(failing.state.value.ready)
        failing.shutdownForTest()
        repositories.remove(failing)

        val reopened = repository(database.timerDao())
        reopened.initialize()

        assertStartedWith(reopened, seeded)
        assertEquals(seeded, durable())
    }

    @Test
    fun cancelledStartupReadPropagatesAndStaysRetryable() = runBlocking {
        val seeded = seedWorkspace()
        val dao = StartupReadDao(database.timerDao()).apply {
            stateFailure = CancellationException("r54 a02 startup cancelled")
        }
        val repo = repository(dao)

        try {
            repo.initialize()
            fail("cancellation must propagate out of initialize()")
        } catch (error: CancellationException) {
            assertEquals("r54 a02 startup cancelled", error.message)
        }

        assertFalse(repo.state.value.ready)
        assertTrue("cancellation is not a storage failure", reported.isEmpty())
        dao.stateFailure = null
        repo.initialize()
        assertStartedWith(repo, seeded)
    }

    @Test
    fun viewModelStartupContainsStorageReadFailure() = runBlocking {
        seedWorkspace()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        Dispatchers.setMain(main)
        try {
            val dao = StartupReadDao(database.timerDao()).apply { stateFailure = IOException("r54 a02 disk read") }
            val repo = repository(dao)
            PomodoroughViewModel(repo)
            awaitCondition("startup failure settled") { reported.isNotEmpty() || uncaught.isNotEmpty() }
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertFalse(repo.state.value.ready)
            assertReports("Local storage read failed: workspace state")
        } finally {
            Dispatchers.resetMain()
            main.close()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    // Seeds through production: a failed offline restore keeps the owner, and the added
    // task leaves pending operations in Room for the startup read to load.
    private suspend fun seedWorkspace(): Durable {
        val seed = repository(database.timerDao())
        seed.initialize()
        assertTrue(seed.addTask("r54 a02 task"))
        seed.shutdownForTest()
        repositories.remove(seed)
        return durable().also { assertTrue("seed must persist pending work", it.taskOperations.isNotEmpty()) }
    }

    private suspend fun assertStartedWith(repo: TimerRepository, seeded: Durable) {
        assertTrue("retry must complete startup", repo.state.value.ready)
        assertEquals(listOf("r54 a02 task"), repo.state.value.tasks.map { it.title })
        assertEquals(
            seeded.commands.size + seeded.taskOperations.size + seeded.selectedTaskOperations.size,
            repo.state.value.pendingCount,
        )
        assertNull("a read failure must not mark corruption", repo.state.value.conflict)
        assertFalse(repo.state.value.localAccountResetRequired)
    }

    private fun assertReports(vararg messages: String) {
        assertEquals(messages.toList(), reported.map { it.message })
        reported.forEach { report ->
            assertNull("report must not attach the storage cause", report.cause)
            val text = report.toString()
            listOf(account.email, account.id, "r54 a02", "SELECT", "task").forEach { secret ->
                assertFalse("report leaked $secret", text.contains(secret, ignoreCase = true))
            }
        }
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private fun repository(dao: TimerDao) = TimerRepository(
        context = context,
        dao = dao,
        api = OfflineService(),
        auth = ActiveAuth(),
        json = json,
        networkAvailable = { false },
    ).also(repositories::add)

    private fun durable(): Durable = runBlocking {
        val dao = database.timerDao()
        Durable(
            local = requireNotNull(dao.localState()),
            taskOperations = dao.pendingTaskOperations().map { it.id },
            selectedTaskOperations = dao.pendingSelectedTaskOperations().map { it.id },
            commands = dao.pendingCommands().map { it.id },
        )
    }

    private data class Durable(
        val local: LocalStateEntity,
        val taskOperations: List<String>,
        val selectedTaskOperations: List<String>,
        val commands: List<String>,
    )

    private class StartupReadDao(private val delegate: TimerDao) : TimerDao by delegate {
        @Volatile var stateFailure: Exception? = null
        @Volatile var queueFailure: Exception? = null
        @Volatile var resolutionFailure: Exception? = null
        val stateReads = AtomicInteger()

        override suspend fun localState(): LocalStateEntity? {
            stateReads.incrementAndGet()
            stateFailure?.let { throw it }
            return delegate.localState()
        }

        override suspend fun pendingCommandsCapped(limit: Int): List<PendingCommandEntity> {
            queueFailure?.let { throw it }
            return delegate.pendingCommandsCapped(limit)
        }

        override suspend fun pendingBootstrapResolution(): PendingBootstrapResolutionEntity? {
            resolutionFailure?.let { throw it }
            return delegate.pendingBootstrapResolution()
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
        override suspend fun me(accessToken: String): MeResponse = throw IOException("r54 a02 offline")
        override suspend fun bootstrap(accessToken: String): SyncResponse = throw IOException("r54 a02 offline")
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            throw IOException("r54 a02 offline")
        override suspend fun sync(accessToken: String, request: SyncRequest) = throw IOException("r54 a02 offline")
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("r54 a02 revision stream offline")
    }
}
