package me.egigoka.pomodorough.data.iroh

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CorruptStateReporter
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A07 through the production TimerRepository, Room, and orchestration. After the local
 * read succeeds, startup still reads the replication route and the startup workspace. A failed
 * read at any of those five sites must stay inside initialize(): startup shows the retry screen,
 * one bounded report names the read, mutations and network work refuse, and every persisted row
 * stays as stored. Once the read works again, the next initialize() starts normally. Before the
 * fix, each failure escaped the caller, which crashed the ViewModel's startup launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class R54A07StartupReadTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val reports = CopyOnWriteArrayList<Throwable>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var previousReporter: ((Throwable) -> Unit)? = null
    private var previousEnabled = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        previousReporter = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashReporter.delegate = reports::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        Dispatchers.setMain(main)
        runBlocking { seed(ReplicationMode.OFFLINE, RoomId) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        CrashReporter.delegate = previousReporter ?: CrashReporter.delegate
        CrashReportingRuntime.reportingEnabled = previousEnabled
        CorruptStateReporter.resetForTest()
    }

    @Test fun discardReadFailureHoldsStartup() = assertStartupHeld(Site.DISCARD, RouteReport)

    @Test fun settingsReadFailureHoldsStartup() = assertStartupHeld(Site.SETTINGS, RouteReport)

    @Test fun roomReadFailureHoldsStartup() = assertStartupHeld(Site.ROOM, RouteReport)

    @Test fun snapshotReadFailureHoldsStartup() = assertStartupHeld(Site.SNAPSHOT, RouteReport)

    @Test fun workspaceReadFailureHoldsStartup() = assertStartupHeld(Site.WORKSPACE, WorkspaceReport)

    @Test
    fun centralizedRetryAfterRouteReadFailureStarts() = runBlocking {
        seed(ReplicationMode.CENTRALIZED, activeRoomId = null)
        withSession(Site.SETTINGS) { session ->
            session.repository.initialize()
            assertRetryScreen(session)
            session.failing = false
            session.repository.initialize()
            // A centralized route has no workspace reload; startup itself replaces the retry screen.
            assertTrue("the retry must start without waiting for the account", session.ready())
            assertFalse(session.repository.state.value.localStorageReadFailed)
            assertReports(RouteReport)
        }
    }

    @Test
    fun expiredTimerAlarmFinishesWithReadableRoute() = runBlocking {
        seedExpiredTimer()
        val seeded = durable()
        withSession(site = null) { session ->
            // The control for the hold test below: with a readable route, the alarm finishes this seed.
            val finished = runCatching { session.repository.finishExpiredTimer() }
            assertEquals(null, finished.exceptionOrNull())
            assertTrue("a readable route must let the alarm finish the timer", finished.getOrThrow())
            assertEquals(TimerStatus.Completed, session.repository.state.value.timer?.status)
            assertNotEquals("the finish must reach the durable state", seeded, durable())
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(emptyList<Throwable>(), reports.toList())
        }
    }

    @Test
    fun expiredTimerAlarmHoldsOnRouteReadFailure() = runBlocking {
        seedExpiredTimer()
        val seeded = durable()
        withSession(Site.SETTINGS) { session ->
            // The alarm receiver reaches finishExpiredTimer() before any startup ran. The control
            // above finishes the same seed, so only the route-read holds keep this timer running:
            // the early return in finishExpiredTimer() and replicationStartupReadFailed in mutationsBlocked().
            val finished = runCatching { session.repository.finishExpiredTimer() }
            assertEquals(null, finished.exceptionOrNull())
            assertFalse("an unreadable route must not finish the timer", finished.getOrThrow())
            assertRetryScreen(session)
            assertEquals("the alarm must leave the durable state unchanged", seeded, durable())
            assertReports(RouteReport)
        }
    }

    @Test
    fun viewModelStartupShowsRetryScreenAndRetries() = runBlocking {
        withSession(Site.SETTINGS) { session ->
            val viewModel = PomodoroughViewModel(session.repository)
            try {
                awaitCondition("startup outcome") { reports.isNotEmpty() || uncaught.isNotEmpty() }
                awaitCondition("retry screen") { session.repository.state.value.localStorageReadFailed }
                assertEquals(emptyList<Throwable>(), uncaught.toList())
                assertRetryScreen(session)
                session.failing = false
                viewModel.retryStartup()
                awaitCondition("retried startup") { session.ready() }
                assertFalse(session.repository.state.value.localStorageReadFailed)
                assertReports(RouteReport)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun heldWorkspaceReloadStillReplacesRetryScreen() = runBlocking {
        // The retry reads the route, but a route change in flight holds the stored workspace
        // back. Startup itself must replace the retry screen. The repository sees a route state
        // the test owns, so no late route publication can replace the screen instead.
        val route = MutableStateFlow(IrohNetworkState(mode = ReplicationMode.OFFLINE, roomId = RoomId))
        val seeded = durable()
        withSession(Site.SETTINGS, route) { session ->
            session.repository.initialize()
            assertRetryScreen(session)
            route.value = route.value.copy(transitioning = true)
            awaitCondition("held route") { session.repository.state.value.network.transitioning }
            assertRetryScreen(session)
            session.failing = false
            session.repository.initialize()
            assertTrue("the retry must start", session.ready())
            assertFalse(session.repository.state.value.localStorageReadFailed)
            assertEquals(seeded, durable())
            assertReports(RouteReport)
        }
    }

    @Test
    fun cancelledWorkspaceReadPropagatesWithoutHoldingStartup() = runBlocking {
        withSession(Site.WORKSPACE) { session ->
            val cancellation = CancellationException("r54 a07 cancelled workspace read")
            session.failure = { cancellation }
            val thrown = runCatching { session.repository.initialize() }.exceptionOrNull()
            // Coroutine stack-trace recovery may rethrow a copy whose cause chain holds the original.
            assertTrue(
                "the workspace cancellation must propagate, got $thrown",
                generateSequence(thrown) { it.cause }.any { it === cancellation },
            )
            assertFalse("cancellation is not a read failure", session.repository.state.value.localStorageReadFailed)
            assertTrue("cancellation is not a storage failure", reports.isEmpty())
            session.failing = false
            session.repository.initialize()
            assertTrue("the next startup must reload the workspace", session.hits.get() >= 2)
            assertTrue(session.ready())
            assertTrue(reports.isEmpty())
        }
    }

    private fun assertStartupHeld(site: Site, report: String) = runBlocking {
        val seeded = durable()
        withSession(site) { session ->
            session.repository.initialize()
            assertRetryScreen(session)
            assertReports(report)
            assertFalse("a held startup refuses edits", session.repository.addTask("r54 a07 task"))
            session.repository.toggleTimer()
            session.repository.setReplicationMode(ReplicationMode.IROH)
            session.repository.createIrohRoom("r54 a07 room")
            session.repository.leaveIrohRoom()
            session.repository.syncIrohNow()
            assertEquals("network work must not run", emptyList<String>(), session.routeChanges.toList())
            assertRetryScreen(session)
            assertEquals(seeded, durable())
            assertReports(report)
            session.failing = false
            session.repository.initialize()
            assertTrue("the retry must start", session.ready())
            assertFalse(session.repository.state.value.localStorageReadFailed)
            assertEquals(ReplicationMode.OFFLINE, session.repository.state.value.network.mode)
            assertEquals(RoomId, session.repository.state.value.network.roomId)
            assertEquals(seeded, durable())
            assertReports(report)
        }
    }

    private fun assertRetryScreen(session: Session) {
        assertFalse("startup must stay on the retry screen", session.ready())
        assertTrue(session.repository.state.value.localStorageReadFailed)
        assertTrue("the read must have run", session.hits.get() > 0)
    }

    private fun assertReports(message: String) {
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(listOf(message), reports.map { it.message })
        val report = reports.single()
        assertNull("the report must not attach the storage cause", report.cause)
        listOf("r54 a07", RoomId, "SELECT").forEach { secret ->
            assertFalse("report leaked $secret", report.toString().contains(secret, ignoreCase = true))
        }
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(5)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private suspend fun withSession(
        site: Site?,
        route: StateFlow<IrohNetworkState>? = null,
        test: suspend (Session) -> Unit,
    ) {
        val session = Session(context, database, site, route)
        try {
            test(session)
        } finally {
            session.close()
        }
    }

    private suspend fun seed(mode: ReplicationMode, activeRoomId: String?) {
        val dao = database.timerDao()
        if (dao.localState() == null) {
            dao.insertState(LocalStateEntity(
                deviceId = "device-test0001", settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
            ))
        }
        if (dao.irohRoom(RoomId) == null) {
            dao.insertIrohRoom(IrohRoomEntity(RoomId, "Retained room", byteArrayOf(1, 2, 3), "{}", "{}", 1, true))
        }
        dao.upsertReplicationSettings(ReplicationSettingsEntity(mode = mode.name, activeRoomId = activeRoomId))
    }

    // A stored running timer whose elapsed time at its anchor already reaches its planned duration,
    // like the seed of the connected alarm tests. Startup projects at the latest command or the
    // timer's anchor, not at the current time, and Core command_eligible() allows an automatic finish
    // only for a timer projected as completed. A timer started through toggleTimer() projects as
    // running at that horizon, so Core would refuse its finish with or without the route-read hold.
    private suspend fun seedExpiredTimer() {
        val focusMs = TimerSettings().focusMinutes * 60_000L
        val timer = CanonicalTimer(
            id = "expired-timer-1", phase = TimerPhase.Focus, status = TimerStatus.Running,
            plannedDurationMs = focusMs, elapsedAtAnchorMs = focusMs,
            anchorAt = Instant.now().minusMillis(ExpiredStartAgoMs).toString(),
        )
        database.timerDao().insertState(LocalStateEntity(
            deviceId = "device-test0001", settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
            canonicalTimerJson = IrohJson.strict.encodeToString(timer), ownedTimerId = timer.id,
        ))
    }

    private fun durable(): Durable = runBlocking {
        val dao = database.timerDao()
        Durable(
            local = checkNotNull(dao.localState()),
            settings = dao.replicationSettings(),
            // Room entities compare their secret by reference, so the secret is compared apart.
            room = dao.irohRoom(RoomId)?.copy(encryptedRoomSecret = NoSecret),
            secret = dao.irohRoom(RoomId)?.encryptedRoomSecret?.toList(),
            commands = dao.pendingCommands().map { it.id },
            taskOperations = dao.pendingTaskOperations().map { it.id },
        )
    }

    private data class Durable(
        val local: LocalStateEntity,
        val settings: ReplicationSettingsEntity?,
        val room: IrohRoomEntity?,
        val secret: List<Byte>?,
        val commands: List<String>,
        val taskOperations: List<String>,
    )

    private enum class Site { DISCARD, SETTINGS, ROOM, SNAPSHOT, WORKSPACE }

    // A non-null route replaces the service state that the repository observes.
    private class Session(
        context: Context,
        database: PomodoroughDatabase,
        private val site: Site?,
        route: StateFlow<IrohNetworkState>?,
    ) {
        private val dao = database.timerDao()
        val hits = AtomicInteger()
        val routeChanges = CopyOnWriteArrayList<String>()
        @Volatile var failing = site != null
        @Volatile var failure: (Site) -> Exception = { at ->
            IOException("r54 a07 $at read of $RoomId: SELECT * FROM rooms")
        }

        private suspend fun <T> read(at: Site, block: suspend () -> T): T {
            if (at == site) {
                hits.incrementAndGet()
                if (failing) throw failure(at)
            }
            return block()
        }

        private val store = Proxy.newProxyInstance(
            IrohReplicationStore::class.java.classLoader, arrayOf(IrohReplicationStore::class.java),
        ) { _, method, _ ->
            check(method.name == "snapshot") { "Unexpected replication store call: ${method.name}" }
            IrohNetworkState(mode = ReplicationMode.OFFLINE)
        } as IrohReplicationStore
        private val service = IrohReplicationService(store, object : IrohEndpointBinding {
            override suspend fun bind(): computer.iroh.Endpoint = error("no endpoint off Iroh")
            override fun ticket(endpoint: computer.iroh.Endpoint): String = error("no endpoint off Iroh")
        }, {})
        private val orchestration = IrohRoomOrchestration(servicePort(), persistencePort())
        private val controller = object : IrohReplicationController {
            override val state = route ?: service.state
            override suspend fun initialize() = orchestration.initialize()
            override suspend fun syncNow() = orchestration.syncNow()
            override suspend fun setMode(mode: ReplicationMode) { routeChanges += "setMode $mode" }
            override suspend fun createRoom(name: String) { routeChanges += "createRoom" }
            override suspend fun joinRoom(invite: String) { routeChanges += "joinRoom" }
            override suspend fun leaveRoom() { routeChanges += "leaveRoom" }
            override suspend fun refreshInvite() { routeChanges += "refreshInvite" }
            override suspend fun afterLocalMutation() = orchestration.afterLocalMutation()
            override suspend fun clearAccountData() { routeChanges += "clearAccountData" }
            override fun onForeground() = orchestration.onForeground()
            override fun onBackground() = orchestration.onBackground()
        }

        // The local initializer reads localState() once; later reads are the workspace reloads.
        private val repositoryDao = object : TimerDao by dao {
            private val localReads = AtomicInteger()

            override suspend fun localState(): LocalStateEntity? =
                if (localReads.incrementAndGet() >= 2) read(Site.WORKSPACE) { dao.localState() } else dao.localState()
        }

        val repository = TimerRepository(
            context, repositoryDao, unusedApi(), OfflineAuth(), IrohJson.strict,
            networkAvailable = { false }, replication = controller,
        )

        fun ready() = repository.state.value.ready

        suspend fun close() {
            repository.shutdownForTest()
            orchestration.close()
        }

        private fun servicePort() = IrohRoomServicePort(
            state = service.state, publishState = service::replaceState,
            transformState = service::transformState,
            start = { _, _ -> error("no endpoint off Iroh") },
            stop = service::stop, close = service::close, stopIf = service::stopIf,
            join = { error("unexpected join") }, endpointIdForTicket = { error("unexpected ticket") },
            syncNow = { error("unexpected sync off Iroh") },
        )

        private fun persistencePort() = IrohRoomPersistencePort(
            localState = dao::localState,
            room = { roomId -> read(Site.ROOM) { dao.irohRoom(roomId) } },
            replicationSettings = { read(Site.SETTINGS) { checkNotNull(dao.replicationSettings()) } },
            discardIncompleteRooms = { read(Site.DISCARD) {} },
            setMode = { error("unexpected route change") },
            snapshot = { roomId ->
                read(Site.SNAPSHOT) { IrohNetworkState(mode = ReplicationMode.OFFLINE, roomId = roomId) }
            },
            captureLocalOperations = {
                IrohRoomProjection(LocalWorkspaceSnapshot(checkNotNull(dao.localState())), 0)
            },
            createRoom = { error("unexpected create") }, activeRoom = { dao.irohRoom(RoomId) },
            activeRoomSecret = { null }, prepareJoinedRoom = { _, _ -> error("unexpected join") },
            discardIncompleteInactiveRoom = { error("unexpected discard") },
            leaveActiveRoom = { error("unexpected leave") }, clearAccountData = { error("unexpected wipe") },
        )
    }

    private class OfflineAuth : AuthSession {
        override fun hasTokens() = false
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override suspend fun <T> authorized(block: suspend (String) -> T): T = error("unused")
        override suspend fun logout() = error("unexpected logout")
        override fun clear() = error("unexpected credential deletion")
    }

    private companion object {
        const val RoomId = "room-test0001"
        const val RouteReport = "Local storage read failed: replication route"
        const val WorkspaceReport = "Local storage read failed: startup workspace"

        // The expired-timer seed anchors this long ago, far past the default 25-minute focus.
        const val ExpiredStartAgoMs = 2 * 60 * 60 * 1000L
        val NoSecret = ByteArray(0)

        fun unusedApi() = Proxy.newProxyInstance(
            PomodoroughService::class.java.classLoader, arrayOf(PomodoroughService::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as PomodoroughService
    }
}
