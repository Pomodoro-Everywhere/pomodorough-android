package me.egigoka.pomodorough.data.iroh

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import computer.iroh.Endpoint
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A03 through the production ViewModel, TimerRepository, Room, and endpoint lifecycle.
 * A retained room reopens after an earlier session left a pending queue. The first
 * initialization that starts its endpoint in the foreground hits a native bind fault. The
 * fault stays inside the retryable unavailable state, no coroutine fails uncaught, and every
 * persisted row and the room secret survive. Before the fix, the IOException escaped either
 * the ViewModel's startup launch or the orchestration's foreground launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class R54A03RepositoryFirstForegroundTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val reports = CopyOnWriteArrayList<Throwable>()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var previousReporter: ((Throwable) -> Unit)? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        previousReporter = CrashReporter.delegate
        CrashReporter.delegate = reports::add
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        CrashReporter.delegate = previousReporter ?: CrashReporter.delegate
    }

    @Test
    fun firstForegroundAfterDeferredInitializationBindsOnceAndRetrySucceeds() = runBlocking {
        val bindFailure = IOException("r54 a03 synthetic secret invite and user text")
        withReopenedRoom { session ->
            session.captureFailures.set(1)
            session.bindFailure = bindFailure
            val viewModel = session.viewModel()
            awaitCondition("deferred initialization") {
                session.captures.get() == 1 && session.ready()
            }
            reports.clear()
            viewModel.onForeground()
            awaitCondition("first foreground outcome") {
                session.network().message == RecoveryMessage || uncaught.isNotEmpty()
            }
            session.orchestration.initialize()
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals("the foreground action must not bind again", 1, session.binds.get())
            assertEquals("only the endpoint lifecycle reports", listOf<Throwable>(bindFailure), reports)
            assertRecoverable(session)

            session.bindFailure = null
            viewModel.syncIrohNow()
            awaitCondition("retry succeeded") {
                val network = session.network()
                network.status == IrohConnectionStatus.LISTENING && network.message == null
            }
            assertEquals(1, session.binds.get())
            assertTrue(session.syncs.get() >= 1)
            assertEquals(listOf<Throwable>(bindFailure), reports.toList())
        }
    }

    @Test
    fun foregroundDuringStartupInitializationContainsBindFailure() = runBlocking {
        val bindFailure = IOException("r54 a03 synthetic secret invite and user text")
        withReopenedRoom { session ->
            session.bindFailure = bindFailure
            val gate = session.holdFirstInitialization()
            val viewModel = session.viewModel()
            awaitCondition("startup reached replication initialization") { gate.reached.isCompleted }
            viewModel.onForeground()
            gate.release.complete(Unit)
            // Startup binds once and the queued foreground action binds once. Startup's own
            // foreground replay keeps the generation and binds nothing (R54-A05).
            awaitCondition("startup outcome") { session.binds.get() >= 2 || uncaught.isNotEmpty() }
            awaitCondition("startup ready") { session.ready() || uncaught.isNotEmpty() }
            delay(SettleMillis)
            session.orchestration.initialize()
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(2, session.binds.get())
            assertTrue(reports.isNotEmpty())
            assertTrue("reports carry only the injected bind fault", reports.all { it === bindFailure })
            assertEquals(IrohConnectionStatus.UNAVAILABLE, session.network().status)
            assertNull(session.network().identityRecovery)
            assertFalse(session.network().transitioning)
        }
    }

    private suspend fun withReopenedRoom(test: suspend (Session) -> Unit) {
        seedRetainedRoomWithPendingQueue()
        val dao = database.timerDao()
        val room = checkNotNull(dao.irohRoom(RoomId))
        val settings = dao.replicationSettings()
        val queue = dao.pendingCommands()
        assertTrue("exercise a nonempty real persisted queue", queue.isNotEmpty())
        val session = Session(context, database)
        try {
            test(session)
            val retained = checkNotNull(dao.irohRoom(RoomId))
            assertEquals(room.copy(encryptedRoomSecret = retained.encryptedRoomSecret), retained)
            assertArrayEquals(room.encryptedRoomSecret, retained.encryptedRoomSecret)
            assertEquals(settings, dao.replicationSettings())
            assertEquals(queue, dao.pendingCommands())
            assertEquals(ReplicationMode.IROH.name, dao.replicationSettings()?.mode)
            assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, session.secret)
        } finally {
            session.close()
        }
    }

    // An earlier session queued a local timer command for the retained room in the background.
    private suspend fun seedRetainedRoomWithPendingQueue() {
        val dao = database.timerDao()
        dao.insertState(LocalStateEntity(
            deviceId = "device-test0001", settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
        ))
        dao.insertIrohRoom(IrohRoomEntity(
            RoomId, "Retained room", byteArrayOf(1, 2, 3), "return", "room", 1, true,
        ))
        dao.upsertReplicationSettings(ReplicationSettingsEntity(
            mode = ReplicationMode.IROH.name, activeRoomId = RoomId,
        ))
        val earlier = Session(context, database)
        try {
            earlier.repository.initialize()
            earlier.repository.toggleTimer()
        } finally {
            earlier.close()
        }
        reports.clear()
    }

    private fun assertRecoverable(session: Session) {
        val network = session.network()
        assertEquals(IrohConnectionStatus.UNAVAILABLE, network.status)
        assertEquals(RecoveryMessage, network.message)
        assertFalse(network.transitioning)
        assertNull(network.identityRecovery)
        assertEquals(RoomId, network.roomId)
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(5)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private class InitializationGate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class Session(private val context: Context, database: PomodoroughDatabase) {
        private val dao = database.timerDao()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val binds = AtomicInteger()
        val syncs = AtomicInteger()
        val captures = AtomicInteger()
        val captureFailures = AtomicInteger()
        @Volatile var bindFailure: Exception? = null
        @Volatile private var gate: InitializationGate? = null
        @Volatile private var viewModel: PomodoroughViewModel? = null
        private val store = Proxy.newProxyInstance(
            IrohReplicationStore::class.java.classLoader, arrayOf(IrohReplicationStore::class.java),
        ) { _, method, _ ->
            check(method.name == "snapshot") { "Unexpected replication store call: ${method.name}" }
            IrohNetworkState(mode = ReplicationMode.IROH, roomId = RoomId)
        } as IrohReplicationStore
        private val service = IrohReplicationService(store, object : IrohEndpointBinding {
            override suspend fun bind(): Endpoint {
                binds.incrementAndGet()
                throw checkNotNull(bindFailure)
            }
            override fun ticket(endpoint: Endpoint): String = error("bind must fail")
        }, {})
        val orchestration = IrohRoomOrchestration(servicePort(), persistencePort())
        private val controller = object : IrohReplicationController {
            override val state = service.state
            override suspend fun initialize() = orchestration.initialize()
            override suspend fun syncNow() = orchestration.syncNow()
            override suspend fun setMode(mode: ReplicationMode) = error("unexpected mode change")
            override suspend fun createRoom(name: String) = error("unexpected room creation")
            override suspend fun joinRoom(invite: String) = error("unexpected join")
            override suspend fun leaveRoom() = error("unexpected leave")
            override suspend fun refreshInvite() = error("unexpected refresh")
            override suspend fun afterLocalMutation() = orchestration.afterLocalMutation()
            override suspend fun clearAccountData() = error("unexpected wipe")
            override fun onForeground() = orchestration.onForeground()
            override fun onBackground() = orchestration.onBackground()
        }
        val repository = TimerRepository(
            context, dao, unusedApi(), OfflineAuth(), IrohJson.strict,
            networkAvailable = { false }, replication = controller,
        )

        fun viewModel() = PomodoroughViewModel(repository).also { viewModel = it }

        fun network() = repository.state.value.network

        fun ready() = repository.state.value.ready

        fun holdFirstInitialization() = InitializationGate().also { gate = it }

        suspend fun close() {
            viewModel?.viewModelScope?.cancel()
            repository.shutdownForTest()
            orchestration.close()
        }

        // A fault-free start stands in for a native endpoint, which JVM tests cannot bind.
        private fun servicePort() = IrohRoomServicePort(
            state = service.state, publishState = service::replaceState,
            transformState = service::transformState,
            start = { context, periodic ->
                if (bindFailure != null) service.start(context, periodic) else {
                    service.replaceState(service.state.value.copy(status = IrohConnectionStatus.LISTENING))
                    "fault-free-ticket"
                }
            },
            stop = service::stop, close = service::close, stopIf = service::stopIf,
            join = { error("unexpected join") }, endpointIdForTicket = { error("unexpected ticket decode") },
            syncNow = {
                syncs.incrementAndGet()
                service.replaceState(
                    service.state.value.copy(status = IrohConnectionStatus.LISTENING, message = null),
                )
            },
        )

        private fun persistencePort() = IrohRoomPersistencePort(
            localState = dao::localState, room = dao::irohRoom,
            replicationSettings = { checkNotNull(dao.replicationSettings()) },
            discardIncompleteRooms = { awaitGate() }, setMode = { error("unexpected route change") },
            snapshot = { IrohNetworkState(mode = ReplicationMode.IROH, roomId = it) },
            captureLocalOperations = ::captureLocalOperations,
            createRoom = { error("unexpected create") }, activeRoom = { dao.irohRoom(RoomId) },
            activeRoomSecret = { secret.copyOf() }, prepareJoinedRoom = { _, _ -> error("unexpected join") },
            discardIncompleteInactiveRoom = { error("unexpected discard") },
            leaveActiveRoom = { error("unexpected leave") }, clearAccountData = { error("unexpected wipe") },
        )

        private suspend fun awaitGate() {
            val held = gate ?: return
            gate = null
            held.reached.complete(Unit)
            held.release.await()
        }

        private suspend fun captureLocalOperations(): IrohRoomProjection {
            captures.incrementAndGet()
            if (captureFailures.getAndUpdate { maxOf(it - 1, 0) } > 0) {
                throw IOException("r54 a03 transient capture failure")
            }
            return IrohRoomProjection(LocalWorkspaceSnapshot(checkNotNull(dao.localState())), 0)
        }
    }

    private class OfflineAuth : AuthSession {
        override fun hasTokens() = false
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override suspend fun <T> authorized(block: suspend (String) -> T): T = error("unused")
        override suspend fun logout() = error("unexpected logout")
        override fun clear() = error("unexpected credential deletion")
    }

    companion object {
        private val RoomId = IrohProtocolV1.roomId(ByteArray(32) { (it + 1).toByte() })
        private const val RecoveryMessage = "Iroh sync could not be completed. Try again."
        private const val SettleMillis = 300L

        private fun unusedApi() = Proxy.newProxyInstance(
            PomodoroughService::class.java.classLoader, arrayOf(PomodoroughService::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as PomodoroughService
    }
}
