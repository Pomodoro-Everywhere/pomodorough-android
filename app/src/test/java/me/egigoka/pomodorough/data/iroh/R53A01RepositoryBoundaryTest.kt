package me.egigoka.pomodorough.data.iroh

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import computer.iroh.Endpoint
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R53A01RepositoryBoundaryTest {
    @Test
    fun realRepositoryContainsNativeBindFailureAndPublishesOneDiagnosticPerAttempt() = runBlocking {
        withBoundary { fixture ->
            val failure = IOException("synthetic secret invite and user text")
            fixture.failure = failure
            repeat(2) { attempt ->
                fixture.repository.syncIrohNow()
                fixture.awaitNetwork { it.message == RecoveryMessage }
                assertEquals(attempt + 1, fixture.binds)
                assertEquals(List(attempt + 1) { failure }, fixture.reports)
                assertEquals(IrohConnectionStatus.UNAVAILABLE, fixture.repository.state.value.network.status)
                assertFalse(fixture.service.state.value.transitioning)
                assertNull(fixture.service.state.value.identityRecovery)
            }
        }
    }

    @Test
    fun realRepositoryPropagatesNativeCancellationWithoutCrashOrRecoveryMessage() = runBlocking {
        withBoundary { fixture ->
            val cancellation = CancellationException("cancel native bind")
            fixture.failure = cancellation
            assertSame(cancellation, runCatching { fixture.repository.syncIrohNow() }.exceptionOrNull())
            assertEquals(1, fixture.binds)
            assertTrue(fixture.reports.isEmpty())
            assertNull(fixture.service.state.value.identityRecovery)
            assertNotEquals(RecoveryMessage, fixture.service.state.value.message)
        }
    }

    @Test
    fun realRepositoryQuarantinesNativeVaultFailureWithoutDeletingPersistedWork() = runBlocking {
        withBoundary { fixture ->
            fixture.failure = IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
            fixture.repository.syncIrohNow()
            fixture.awaitNetwork { it.identityRecovery == IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED }
            assertEquals(1, fixture.binds)
            assertTrue(fixture.reports.isEmpty())
            assertNull(fixture.service.state.value.invite)
            assertNull(fixture.service.state.value.message)
            assertEquals(IrohConnectionStatus.UNAVAILABLE, fixture.service.state.value.status)
            fixture.repository.syncIrohNow()
            assertEquals("quarantine must reject a second bind", 1, fixture.binds)
        }
    }

    private suspend fun withBoundary(test: suspend (Boundary) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        val previousReporter = CrashReporter.delegate
        val fixture = Boundary(context, database)
        CrashReporter.delegate = fixture.reports::add
        try {
            fixture.initialize()
            val room = database.timerDao().irohRoom(RoomId)
            val settings = database.timerDao().replicationSettings()
            val local = database.timerDao().localState()
            val queue = database.timerDao().pendingCommands()
            val secret = fixture.secret.copyOf()
            assertTrue("exercise a nonempty real persisted queue", queue.isNotEmpty())
            test(fixture)
            val retainedRoom = checkNotNull(database.timerDao().irohRoom(RoomId))
            assertEquals(room?.copy(encryptedRoomSecret = retainedRoom.encryptedRoomSecret), retainedRoom)
            assertArrayEquals(room?.encryptedRoomSecret, retainedRoom.encryptedRoomSecret)
            assertEquals(settings, database.timerDao().replicationSettings())
            assertEquals(local, database.timerDao().localState())
            assertEquals(queue, database.timerDao().pendingCommands())
            assertArrayEquals(secret, fixture.secret)
        } finally {
            fixture.repository.shutdownForTest()
            fixture.orchestration.close()
            database.close()
            CrashReporter.delegate = previousReporter
        }
    }

    private class Boundary(context: Context, database: PomodoroughDatabase) {
        private val dao = database.timerDao()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val reports = mutableListOf<Throwable>()
        var failure: Exception? = null
        var binds = 0
        lateinit var retainedInvite: String
        private var armed = false
        private val store = Proxy.newProxyInstance(
            IrohReplicationStore::class.java.classLoader, arrayOf(IrohReplicationStore::class.java),
        ) { _, method, _ ->
            check(method.name == "snapshot") { "Unexpected replication store call: ${method.name}" }
            IrohNetworkState(mode = ReplicationMode.IROH, roomId = RoomId)
        } as IrohReplicationStore
        val service = IrohReplicationService(store, object : IrohEndpointBinding {
            override suspend fun bind(): Endpoint {
                binds += 1
                throw checkNotNull(failure)
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
            override suspend fun afterLocalMutation() = Unit
            override suspend fun clearAccountData() = error("unexpected wipe")
            override fun onForeground() = Unit
        }
        val repository = TimerRepository(
            context, dao, unusedApi(), OfflineAuth(), IrohJson.strict,
            networkAvailable = { false }, replication = controller,
        )

        suspend fun initialize() {
            dao.insertState(LocalStateEntity(
                deviceId = "device-test0001", settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
            ))
            dao.insertIrohRoom(IrohRoomEntity(
                RoomId, "Retained room", byteArrayOf(1, 2, 3), "return", "room", 1, true,
            ))
            dao.upsertReplicationSettings(ReplicationSettingsEntity(
                mode = ReplicationMode.IROH.name, activeRoomId = RoomId,
            ))
            repository.initialize()
            repository.toggleTimer()
            // Admit foreground before arming the native fault; setup must not consume the failure.
            orchestration.onForeground()
            awaitNetwork { it.status == IrohConnectionStatus.LISTENING }
            orchestration.initialize()
            orchestration.refreshInvite()
            retainedInvite = checkNotNull(service.state.value.invite)
            armed = true
        }

        suspend fun awaitNetwork(predicate: (IrohNetworkState) -> Boolean) = withTimeout(5_000) {
            while (!predicate(repository.state.value.network)) delay(1)
        }

        private fun servicePort() = IrohRoomServicePort(
            state = service.state, publishState = service::replaceState,
            start = { context, periodic ->
                if (armed) service.start(context, periodic) else {
                    service.replaceState(service.state.value.copy(status = IrohConnectionStatus.LISTENING))
                    "setup-ticket"
                }
            },
            stop = service::stop, close = service::close, stopIf = service::stopIf,
            join = { error("unexpected join") }, endpointIdForTicket = { error("unexpected ticket decode") },
            syncNow = service::syncNow,
        )

        private fun persistencePort() = IrohRoomPersistencePort(
            localState = dao::localState, room = dao::irohRoom,
            replicationSettings = { checkNotNull(dao.replicationSettings()) },
            discardIncompleteRooms = {}, setMode = { error("unexpected route change") },
            snapshot = { IrohNetworkState(mode = ReplicationMode.IROH, roomId = it) },
            captureLocalOperations = {
                IrohRoomProjection(LocalWorkspaceSnapshot(checkNotNull(dao.localState())), 0)
            },
            createRoom = { error("unexpected create") }, activeRoom = { dao.irohRoom(RoomId) },
            activeRoomSecret = { secret.copyOf() }, prepareJoinedRoom = { _, _ -> error("unexpected join") },
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

    companion object {
        private val RoomId = IrohProtocolV1.roomId(ByteArray(32) { (it + 1).toByte() })
        private const val RecoveryMessage = "Iroh sync could not be completed. Try again."

        private fun unusedApi() = Proxy.newProxyInstance(
            PomodoroughService::class.java.classLoader, arrayOf(PomodoroughService::class.java),
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as PomodoroughService
    }
}
