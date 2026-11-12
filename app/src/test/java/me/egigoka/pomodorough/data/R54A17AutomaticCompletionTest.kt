package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.timer.TimerAlarmDeliveryResult
import me.egigoka.pomodorough.timer.TimerCompletionNotifying
import me.egigoka.pomodorough.timer.deliverTimerAlarm
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R54-A17: an automatic finish asks Core about a projection at trusted now.
 * Rebuilds project at the deterministic horizon, the newest pending command
 * time, so a timer whose start never synced looked freshly started and every
 * automatic finish was refused. Real repository, real Room and bundled Core.
 * The presentation gate reads the system clock, so each start sits two hours
 * before it. The injected clocks are the trusted clock under test and decide
 * what Core sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A17AutomaticCompletionTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()
    private val service = CountingService { physicalMs + ServerOffsetMs }
    // The view model tick reads the injected clocks on another thread.
    @Volatile private var physicalMs = 0L
    @Volatile private var elapsedMs = 0L
    @Volatile private var bootId = BootId

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
        physicalMs = System.currentTimeMillis() - SystemClockLeadMs
        elapsedMs = InitialElapsedMs
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
    fun offlineAlarmCompletesExpiredTimerInSameProcess() = runBlocking {
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        advance(started.plannedDurationMs + PastEndMs)

        val result = deliverTimerAlarm(repo, started.id, TimerCompletionNotifying { true })

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, result)
        assertCompletedOnce(repo, started)
    }

    @Test
    fun offlineAlarmCompletesExpiredTimerAfterProcessDeath() = runBlocking {
        val first = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(first)
        retire(first)
        advance(started.plannedDurationMs + PastEndMs)
        val restarted = repository(ReplicationMode.OFFLINE)

        val result = deliverTimerAlarm(restarted, started.id, TimerCompletionNotifying { true })

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, result)
        assertCompletedOnce(restarted, started)
    }

    // The real view model ticks every 500 ms on the app's Main.immediate
    // dispatcher, the Robolectric main looper. runBlocking holds the main
    // thread, so the test runs that looper itself. The finish publishes the
    // completion before it returns, which restarts the tick's collectLatest
    // block; the alert pins that the tick still alerts.
    @Test
    fun offlineForegroundTickCompletesExpiredTimer() = runBlocking {
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        val alerts = CopyOnWriteArrayList<String>()
        val viewModel = PomodoroughViewModel(repo) { timerId -> alerts.add(timerId) }
        try {
            viewModel.onForeground()
            runMainLooperFor(TickWaitMs)
            assertEquals("the tick must not finish early", TimerStatus.Running, repo.state.value.timer?.status)
            advance(started.plannedDurationMs + PastEndMs)

            runMainLooperUntil("completion") { repo.state.value.timer?.status == TimerStatus.Completed }
            runMainLooperUntil("the tick alert") { alerts.isNotEmpty() }
        } finally {
            viewModel.onBackground()
        }

        assertCompletedOnce(repo, started)
        assertEquals("the tick must alert once", listOf(started.id), alerts.toList())
    }

    @Test
    fun centralizedBackgroundAlarmCompletesTimerBetweenSyncs() = runBlocking {
        seedSignedInClockSample()
        val repo = readyRepository(ReplicationMode.CENTRALIZED)
        val started = startFocus(repo)
        val callsAtStart = service.calls
        repo.onBackground()
        advance(started.plannedDurationMs + PastEndMs)

        val result = deliverTimerAlarm(repo, started.id, TimerCompletionNotifying { true })

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, result)
        assertCompletedOnce(repo, started)
        assertEquals("no request may rescue the projection", callsAtStart, service.calls)
    }

    @Test
    fun centralizedForegroundTickCompletesAndAlertsOnImmediateMain() = runBlocking {
        seedSignedInClockSample()
        assertTickCompletes(ReplicationMode.CENTRALIZED)
    }

    private suspend fun assertTickCompletes(mode: ReplicationMode) {
        val repo = readyRepository(mode)
        val started = startFocus(repo)
        val alerts = CopyOnWriteArrayList<String>()
        val viewModel = PomodoroughViewModel(repo) { alerts.add(it) }
        try {
            viewModel.onForeground()
            runMainLooperFor(TickWaitMs)
            assertEquals(TimerStatus.Running, repo.state.value.timer?.status)
            // Restore network admission without a foreground/refresh signal: only
            // afterLocalMutation may request a sync containing the completed timer.
            val runtime = TimerRepository::class.java.getDeclaredField("centralizedSyncRuntime").let {
                it.isAccessible = true
                it.get(repo) as CentralizedSyncRuntime
            }
            runtime.updateOnlineState(true)
            advance(started.plannedDurationMs + PastEndMs)
            runMainLooperUntil("centralized tick alert; state=${repo.state.value}") { alerts.isNotEmpty() }
            runMainLooperUntil("post-completion sync") {
                service.syncRequests.any { request ->
                    request.commands.any { it.timerId == started.id && it.type == CommandType.Finish }
                }
            }
            assertCompletedOnce(repo, started)
            assertEquals(listOf(started.id), alerts.toList())
        } finally {
            viewModel.onBackground()
        }
    }

    @Test
    fun alarmAndImmediateMainTickRaceAlertsExactlyOnce() = runBlocking {
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        val alerts = CopyOnWriteArrayList<String>()
        val viewModel = PomodoroughViewModel(repo) { alerts.add(it) }
        try {
            viewModel.onForeground()
            runMainLooperFor(TickWaitMs)
            advance(started.plannedDurationMs + PastEndMs)
            val alarm = async(Dispatchers.IO) {
                deliverTimerAlarm(repo, started.id, TimerCompletionNotifying { alerts.add(started.id); true })
            }
            runMainLooperUntil("racing completion alert") { alerts.isNotEmpty() && alarm.isCompleted }
            alarm.await()
            runMainLooperFor(TickWaitMs)
            assertCompletedOnce(repo, started)
            assertEquals("only the winning completion alerts", listOf(started.id), alerts.toList())
        } finally {
            viewModel.onBackground()
        }
    }

    @Test
    fun forcedAlarmWinnerThenTickContenderAlertsOnce() = runBlocking {
        forcedCompletionWinner(alarmWins = true)
    }

    @Test
    fun forcedTickWinnerThenAlarmContenderAlertsOnce() = runBlocking {
        forcedCompletionWinner(alarmWins = false)
    }

    private suspend fun forcedCompletionWinner(alarmWins: Boolean) {
        seedSignedInClockSample()
        val repo = readyRepository(ReplicationMode.CENTRALIZED)
        val started = startFocus(repo)
        val alerts = CopyOnWriteArrayList<String>()
        val viewModel = PomodoroughViewModel(repo) { alerts.add("tick:$it") }
        try {
            viewModel.onForeground()
            runMainLooperFor(TickWaitMs)
            val runtimeField = TimerRepository::class.java.getDeclaredField("centralizedSyncRuntime").apply { isAccessible = true }
            (runtimeField.get(repo) as CentralizedSyncRuntime).updateOnlineState(true)
            advance(started.plannedDurationMs + PastEndMs)
            val notifier = TimerCompletionNotifying { alerts.add("alarm:${started.id}"); true }
            if (alarmWins) {
                assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliverTimerAlarm(repo, started.id, notifier))
                runMainLooperFor(TickWaitMs)
            } else {
                runMainLooperUntil("forced tick winner") { alerts.isNotEmpty() }
                assertEquals(TimerAlarmDeliveryResult.NotExpired, deliverTimerAlarm(repo, started.id, notifier))
            }
            runMainLooperUntil("winner's Finish sync") {
                service.syncRequests.any { it.commands.any { row -> row.type == CommandType.Finish && row.timerId == started.id } }
            }
            assertCompletedOnce(repo, started)
            assertEquals(listOf("${if (alarmWins) "alarm" else "tick"}:${started.id}"), alerts.toList())
        } finally { viewModel.onBackground() }
    }

    @Test
    fun trustedClockBeforePlannedEndKeepsTimerRunning() = runBlocking {
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        advance(started.plannedDurationMs - BeforeEndMs)
        val before = durableCommands()

        assertFalse("a running timer must not finish before its end", repo.finishExpiredTimer())
        val result = deliverTimerAlarm(repo, started.id, TimerCompletionNotifying { true })

        assertEquals(TimerAlarmDeliveryResult.NotExpired, result)
        assertEquals(TimerStatus.Running, repo.state.value.timer?.status)
        assertEquals(before, durableCommands())
        assertNull("a refused automatic finish must stay silent", repo.state.value.notice)
    }

    // After an offline reboot the trusted clock needs a fresh server sample.
    // The automatic finish waits without a mutation fence, so the account
    // restore that would bring the sample is still admitted, and the manual
    // finish still reaches the clock and reports it.
    @Test
    fun staleTrustedClockAfterRebootDefersAutomaticFinishWithoutFence() = runBlocking {
        seedSignedInClockSample()
        val first = readyRepository(ReplicationMode.CENTRALIZED)
        val started = startFocus(first)
        retire(first)
        rebootOffline(pastEndMs = started.plannedDurationMs + PastEndMs)
        val restarted = repository(ReplicationMode.CENTRALIZED).also { it.initialize() }
        val before = durableCommands()
        assertEquals(OfflineRestoreNotice, restarted.state.value.notice)

        assertFalse("a stale trusted clock must defer the automatic finish", restarted.finishExpiredTimer())

        assertEquals(TimerStatus.Running, restarted.state.value.timer?.status)
        assertEquals(OfflineRestoreNotice, restarted.state.value.notice)
        assertEquals(before, durableCommands())
        val attempts = service.attempts
        restarted.initialize()
        assertEquals("the account restore must still be admitted", attempts + 1, service.attempts)
        restarted.finishTimer()
        assertEquals(TimerStatus.Running, restarted.state.value.timer?.status)
        assertEquals(StaleClockNotice, restarted.state.value.notice)
        assertEquals(before, durableCommands())
    }

    @Test
    fun manualFinishBeforePlannedEndStillFinishes() = runBlocking {
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        advance(BeforeEndMs)

        repo.finishTimer()

        assertCompletedOnce(repo, started)
    }

    @Test
    fun failedCompletionAlertMarkNeverCallsNotifier() = runBlocking {
        context = failingAlertPreferencesContext(context)
        val repo = readyRepository(ReplicationMode.OFFLINE)
        val started = startFocus(repo)
        repo.finishTimer()
        var notifications = 0

        val shown = repo.showCompletionAlert(started.id) { notifications++; true }

        assertFalse("a notification needs a durable stop-sound marker", shown)
        assertEquals(0, notifications)
        assertNull(repo.state.value.completionAlertTimerId)
    }

    private fun failingAlertPreferencesContext(base: Context): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = base.getSharedPreferences(name, mode)
            if (name != "completion-alert") return preferences
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") method.invoke(preferences, *(args ?: emptyArray()))
                else failingEditor(preferences.edit())
            } as SharedPreferences
        }
    }

    private fun failingEditor(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            if (method.name == "commit") false
            else method.invoke(editor, *(args ?: emptyArray())).let {
                if (it is SharedPreferences.Editor) proxy else it
            }
        } as SharedPreferences.Editor

    private suspend fun readyRepository(mode: ReplicationMode): TimerRepository {
        val repo = repository(mode)
        repo.initialize()
        if (mode == ReplicationMode.CENTRALIZED) {
            awaitState(repo, "sign-in") { it.authStatus == AuthStatus.SignedIn }
        }
        return repo
    }

    private fun repository(mode: ReplicationMode): TimerRepository = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = service,
        auth = FakeAuth(signedIn = mode == ReplicationMode.CENTRALIZED),
        json = json,
        networkAvailable = { false },
        currentTimeMillis = { physicalMs },
        elapsedRealtimeMillis = { elapsedMs },
        bootId = { bootId },
        replication = FixedModeReplication(mode),
    ).also(repositories::add)

    private suspend fun retire(repo: TimerRepository) {
        repo.shutdownForTest()
        repositories.remove(repo)
    }

    private fun rebootOffline(pastEndMs: Long) {
        bootId = RebootedBootId
        elapsedMs = RebootedElapsedMs
        physicalMs += pastEndMs
        service.reachable = false
    }

    private fun advance(durationMs: Long) {
        physicalMs += durationMs
        elapsedMs += durationMs
    }

    private suspend fun startFocus(repo: TimerRepository): CanonicalTimer {
        repo.toggleTimer()
        val started = checkNotNull(repo.state.value.timer) { "toggle did not start a timer" }
        assertEquals(TimerStatus.Running, started.status)
        assertEquals(TimerPhase.Focus, started.phase)
        return started
    }

    private suspend fun assertCompletedOnce(repo: TimerRepository, started: CanonicalTimer) {
        val timer = repo.state.value.timer
        assertEquals(started.id, timer?.id)
        assertEquals(TimerStatus.Completed, timer?.status)
        val finishes = database.timerDao().pendingCommands()
            .filter { it.timerId == started.id && it.type == CommandType.Finish }
        assertEquals("exactly one durable Finish command", 1, finishes.size)
    }

    private suspend fun durableCommands(): List<String> =
        database.timerDao().pendingCommands().map { "${it.type}:${it.timerId}:${it.id}" }

    private suspend fun seedSignedInClockSample() {
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = DeviceId,
                settingsJson = json.encodeToString(TimerSettings()),
                userJson = json.encodeToString(Account),
                ownerUserId = Account.id,
                serverClockOffsetMs = ServerOffsetMs,
                serverClockUncertaintyMs = 0L,
                serverClockSamplePhysicalMs = physicalMs,
                serverClockSampleElapsedRealtimeMs = elapsedMs,
                serverClockBootId = BootId,
            ),
        )
    }

    private suspend fun awaitState(repo: TimerRepository, label: String, reached: (AppState) -> Boolean) {
        val done = withTimeoutOrNull(5_000) {
            while (!reached(repo.state.value)) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", done == true)
    }

    // Each pass moves the main looper clock 50 ms, which drives the 500 ms
    // tick, then lets Room's threads post their results back to the looper.
    private fun runMainLooperUntil(label: String, reached: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofMillis(LooperWaitMs).toNanos()
        while (!reached()) {
            assertTrue("timed out waiting for $label", System.nanoTime() < deadline)
            runMainLooperStep()
        }
    }

    private fun runMainLooperFor(realMs: Long) {
        val end = System.nanoTime() + Duration.ofMillis(realMs).toNanos()
        while (System.nanoTime() < end) runMainLooperStep()
    }

    private fun runMainLooperStep() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LooperStepMs))
        Thread.sleep(LooperPauseMs)
    }

    private class FakeAuth(private val signedIn: Boolean) : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) =
            error("unused")
        override fun hasTokens() = signedIn
        override fun credentialState() =
            if (signedIn) AuthCredentialState.Active else AuthCredentialState.Empty
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FixedModeReplication(override val mode: ReplicationMode) : IrohReplicationController {
        override val state: StateFlow<IrohNetworkState> = MutableStateFlow(IrohNetworkState(mode = mode))
        override suspend fun initialize() = Unit
        override suspend fun setMode(mode: ReplicationMode) = error("unexpected mode change")
        override suspend fun createRoom(name: String) = Unit
        override suspend fun joinRoom(invite: String) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun refreshInvite() = Unit
        override suspend fun syncNow() = Unit
        override suspend fun afterLocalMutation() = Unit
        override suspend fun clearAccountData() = Unit
        override fun onForeground() = Unit
    }

    // The connectivity callback reports no network, but sign-in restore still
    // calls the service. Its clock matches the seeded offset. A sync would
    // project at trusted now and hide the defect, so each call is counted.
    // Unreachable, every call fails as offline. Attempts count failed calls too.
    private class CountingService(private val serverNowMs: () -> Long) : PomodoroughService {
        val syncRequests = CopyOnWriteArrayList<SyncRequest>()
        @Volatile var calls = 0
        @Volatile var attempts = 0
        @Volatile var reachable = true

        private fun reach() {
            attempts += 1
            if (!reachable) throw IOException(OfflineNotice)
            calls += 1
        }

        private fun empty(): SyncResponse {
            reach()
            val serverMs = serverNowMs()
            return SyncResponse(
                acknowledgements = emptyList(),
                revision = 0,
                canonicalTimer = null,
                history = emptyList(),
                serverTime = Instant.ofEpochMilli(serverMs).toString(),
                serverHlcWallMs = serverMs,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
        }

        override suspend fun me(accessToken: String): MeResponse {
            reach()
            return MeResponse(Account, "")
        }
        override suspend fun bootstrap(accessToken: String) = empty()
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty()
        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
            syncRequests.add(request)
            throw IOException(OfflineNotice)
        }
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("unused")
    }

    private companion object {
        const val DeviceId = "device-1"
        const val BootId = "boot-1"
        const val RebootedBootId = "boot-2"
        const val InitialElapsedMs = 10_000_000L
        const val RebootedElapsedMs = 5_000L
        const val SystemClockLeadMs = 2 * 60 * 60 * 1_000L
        const val PastEndMs = 60_000L
        const val BeforeEndMs = 60_000L
        const val ServerOffsetMs = 90_000L
        const val StaleClockNotice =
            "The app must check the time with the server before it can change the timer. " +
                "Connect to the internet, then try again."
        const val OfflineNotice = "r54 a17 offline"

        // The restore notice is the localized sentence, never the exception text above.
        const val OfflineRestoreNotice =
            "Could not reach the server to confirm your account. Check your connection, then try again."
        const val TickWaitMs = 1_200L
        const val LooperStepMs = 50L
        const val LooperPauseMs = 5L
        const val LooperWaitMs = 5_000L
        val Account = User("account-1", "a1@example.test", "A", "")
    }
}
