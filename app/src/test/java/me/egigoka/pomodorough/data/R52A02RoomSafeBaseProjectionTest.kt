package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R52-A02: room creation retains the centralized safe-base durations.
 * A room row carrying centralized 25-minute safe metadata but 35-minute
 * room settings must display and start 35 minutes on the room route.
 * The centralized route keeps safe-base authority, so returning to it
 * restores 25 minutes. Real repository plus real Room and bundled Core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R52A02RoomSafeBaseProjectionTest {
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
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
    }

    @Test
    fun irohDisplaysAndStartsRoomDurationsDespiteInheritedSafeBase() = runBlocking {
        seedRoomRow()
        val repo = repository(FakeIrohReplication(ReplicationMode.IROH))
        repo.initialize()

        assertEquals(35, repo.state.value.settings.focusMinutes)
        assertNull(repo.state.value.timer)
        repo.toggleTimer()

        assertEquals(35, repo.state.value.settings.focusMinutes)
        assertEquals(Focus35, repo.state.value.timer?.plannedDurationMs)
        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(Focus35), commands.map { it.plannedDurationMs })
    }

    @Test
    fun centralizedRouteKeepsSafeBaseAuthority() = runBlocking {
        seedRoomRow()
        val repo = repository(null)
        repo.initialize()

        assertEquals(25, repo.state.value.settings.focusMinutes)
    }

    @Test
    fun returnToCentralizedRestoresCentralizedBase() = runBlocking {
        seedRoomRow()
        val replication = FakeIrohReplication(ReplicationMode.IROH)
        val repo = repository(replication)
        repo.initialize()
        repo.toggleTimer()
        assertNotNull(repo.state.value.timer)

        replication.current = ReplicationMode.CENTRALIZED
        repo.reloadWorkspace(ReplicationMode.CENTRALIZED)

        assertEquals(25, repo.state.value.settings.focusMinutes)
    }

    private suspend fun seedRoomRow() {
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = "device-1",
                settingsJson = json.encodeToString(TimerSettings(focusMinutes = 35)),
                safeBaseDurationsJson = json.encodeToString(DurationsMs()),
                safeCanonicalHeadWallMs = HeadWallMs,
                safeCanonicalHeadCounter = 0,
            ),
        )
    }

    private fun repository(replication: IrohReplicationController?): TimerRepository = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = FakeRoomService(),
        auth = FakeRoomAuth(),
        json = json,
        networkAvailable = { false },
        replication = replication,
    ).also(repositories::add)

    private class FakeIrohReplication(initial: ReplicationMode) : IrohReplicationController {
        var current: ReplicationMode = initial
        private val flow = MutableStateFlow(IrohNetworkState(mode = initial))
        override val state: StateFlow<IrohNetworkState> = flow
        override val mode: ReplicationMode get() = current
        override suspend fun initialize() = Unit
        override suspend fun setMode(mode: ReplicationMode) {
            current = mode
        }
        override suspend fun createRoom(name: String) = Unit
        override suspend fun joinRoom(invite: String) = Unit
        override suspend fun leaveRoom() = Unit
        override suspend fun refreshInvite() = Unit
        override suspend fun syncNow() = Unit
        override suspend fun afterLocalMutation() = Unit
        override suspend fun clearAccountData() = Unit
        override fun onForeground() = Unit
    }

    private class FakeRoomAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) =
            TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")
        override fun hasTokens() = false
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FakeRoomService : PomodoroughService {
        private val empty = SyncResponse(
            acknowledgements = emptyList(),
            revision = 0,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = "2026-01-01T00:00:00Z",
            serverHlcWallMs = HeadWallMs,
            serverHlcCounter = 0,
            durationAcknowledgements = emptyList(),
            durationsMs = DurationsMs(),
            taskAcknowledgements = emptyList(),
            tasks = emptyList(),
        )
        override suspend fun me(accessToken: String) =
            MeResponse(User("account-1", "a1@example.test", "A", ""), "")
        override suspend fun bootstrap(accessToken: String) = empty
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("unused")
    }

    private companion object {
        const val Focus35 = 2_100_000L
        const val HeadWallMs = 1_767_225_600_000L
    }
}
