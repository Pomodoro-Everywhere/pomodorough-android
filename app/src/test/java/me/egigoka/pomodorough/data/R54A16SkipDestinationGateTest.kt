package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A16: the idle "Skip to <phase>" control asks Shared Core for its destination only while a
 * workspace is installed and no projection failure is recorded. A startup without a workspace,
 * a startup projection failure and a failed mutation projection each hide the control without a
 * Core call. A healthy idle start still offers the Core destination, and so does a later mutation
 * whose projection succeeds after a failed one. The repository runs on real
 * Room and the real Shared Core; only projection calls past a budget fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A16SkipDestinationGateTest {
    private val reported = CopyOnWriteArrayList<Throwable>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var core: CountingCore
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        val state = LocalStateEntity(deviceId = "device-1", settingsJson = json.encodeToString(TimerSettings()))
        runBlocking { database.timerDao().insertState(state) }
        core = CountingCore(SharedCore.fromAssets(context.assets))
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
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun healthyIdleStartOffersCoreSkipDestination() = runBlocking {
        val repo = startedRepository()

        assertTrue("a healthy startup completes", repo.state.value.ready)
        assertEquals(TimerPhase.ShortBreak, repo.state.value.skipDestinationPhase)
        assertEquals("the memo keeps one Core call per key", 1, core.skipDestinationCalls())
        assertEquals(emptyList<String?>(), reported.map { it.message })
    }

    @Test
    fun startupWithoutWorkspaceAsksCoreNothing() = runBlocking {
        core.projectionsAllowed = 0
        val repo = startedRepository()

        assertTrue("a failed projection runtime completes startup", repo.state.value.ready)
        assertNull(repo.state.value.skipDestinationPhase)
        assertEquals(0, core.skipDestinationCalls())
        assertEquals(listOf("Shared Core projection failed at startup"), reported.map { it.message })
    }

    @Test
    fun startupProjectionFailureWithWorkspaceAsksCoreNothing() = runBlocking {
        core.projectionsAllowed = 1
        val repo = startedRepository()

        assertTrue("a failed startup projection completes startup", repo.state.value.ready)
        assertEquals(invalidOutputNotice(), repo.state.value.notice)
        assertNull(repo.state.value.skipDestinationPhase)
        assertEquals(0, core.skipDestinationCalls())
        assertEquals(listOf(ProjectionFailure), reported.map { it.message })
    }

    // The failed mutation leaves the selected phase, history and day unchanged, so the memo key
    // still matches. Only the gate ahead of the memo hides the phase it offered before.
    @Test
    fun failedMutationProjectionHidesOfferedSkipDestination() = runBlocking {
        val repo = startedRepository()
        assertEquals(TimerPhase.ShortBreak, repo.state.value.skipDestinationPhase)

        core.projectionsAllowed = 0
        repo.setAutoStart(!TimerSettings().autoStartBreaks)

        assertEquals(invalidOutputNotice(), repo.state.value.notice)
        assertNull(repo.state.value.skipDestinationPhase)
        assertEquals(1, core.skipDestinationCalls())
        assertEquals(listOf(ProjectionFailure), reported.map { it.message })
    }

    // Mutations do not wait on a recorded projection failure, so the same mutation can run again.
    // Its successful projection clears the failure, so the control returns; it does not stay hidden
    // until the process restarts. The memo key still matches, so no second Core call is made.
    @Test
    fun recoveredMutationProjectionOffersSkipDestinationAgain() = runBlocking {
        val repo = startedRepository()
        assertEquals(TimerPhase.ShortBreak, repo.state.value.skipDestinationPhase)
        val autoStart = !TimerSettings().autoStartBreaks

        core.projectionsAllowed = 0
        repo.setAutoStart(autoStart)
        assertNull(repo.state.value.skipDestinationPhase)

        core.projectionsAllowed = Int.MAX_VALUE
        repo.setAutoStart(autoStart)

        assertNull("the successful projection clears the failure notice", repo.state.value.notice)
        assertEquals(TimerPhase.ShortBreak, repo.state.value.skipDestinationPhase)
        assertEquals(1, core.skipDestinationCalls())
        assertEquals(listOf(ProjectionFailure), reported.map { it.message })
    }

    private suspend fun startedRepository(): TimerRepository {
        val repo = TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = UnusedService(),
            auth = SignedOutAuth(),
            json = json,
            networkAvailable = { false },
            sharedCoreDispatch = core::dispatch,
        ).also(repositories::add)
        repo.initialize()
        return repo
    }

    private fun invalidOutputNotice() = context.getString(R.string.shared_core_invalid_output)

    /** The real Shared Core. It counts skip-destination calls and fails projections past a budget. */
    private class CountingCore(private val core: SharedCore) {
        private val operations = CopyOnWriteArrayList<String>()

        @Volatile
        var projectionsAllowed = Int.MAX_VALUE

        fun dispatch(operation: String, input: String): JsonElement {
            operations.add(operation)
            if (operation == ProjectionOperation && projectionsAllowed-- <= 0) {
                throw SharedCoreException.Operation(operation, ProjectionFailureDetail)
            }
            return core.dispatch(operation, input)
        }

        fun skipDestinationCalls() = operations.count { it == SkipDestinationOperation }
    }

    private class SignedOutAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = false
        override suspend fun <T> authorized(block: suspend (String) -> T): T = error("signed out")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class UnusedService : PomodoroughService {
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun me(accessToken: String) = error("unused")
        override suspend fun bootstrap(accessToken: String) = error("unused")
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            error("unused")
        override suspend fun sync(accessToken: String, request: SyncRequest) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
    }

    private companion object {
        const val ProjectionOperation = "projection.apply.v2"
        const val SkipDestinationOperation = "timer.completionState.v1"
        const val ProjectionFailureDetail = "r54 a16 projection"
        const val ProjectionFailure = "shared-core operation $ProjectionOperation failed: $ProjectionFailureDetail"
    }
}
