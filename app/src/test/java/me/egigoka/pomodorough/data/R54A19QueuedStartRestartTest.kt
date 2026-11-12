package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A19: the process dies after the local Start is sent and before its
 * acknowledgement. On restart the delivery-safe projection omits that Start
 * (it has no never-sent proof), so no active timer matches the owned id.
 * Local initialization must keep the ownership while the own Start stays
 * queued, or the later manual finish loses its automatic break.
 * Real repository plus real Room and bundled Core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A19QueuedStartRestartTest {
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
    fun restartKeepsOwnershipWhileSentStartAwaitsAcknowledgement() = runBlocking {
        seedSentStart(ownedTimerId = StartTimerId)

        val restarted = repository().also { it.initialize() }

        assertNull(restarted.state.value.timer)
        assertEquals(StartTimerId, database.timerDao().localState()?.ownedTimerId)
    }

    @Test
    fun restartClearsOwnershipWhenOnlyAnotherTimerStartIsQueued() = runBlocking {
        seedSentStart(ownedTimerId = RetiredTimerId)

        repository().initialize()

        assertNull(database.timerDao().localState()?.ownedTimerId)
    }

    // The Start is stamped above the canonical head but was already sent
    // (no never-sent proof), so the safe projection drops the command domain.
    private suspend fun seedSentStart(ownedTimerId: String) {
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = DeviceId,
                deviceSequence = 1,
                hlcWallMs = HeadWallMs + 2_000,
                hlcCounter = 0,
                revision = 3,
                settingsJson = json.encodeToString(TimerSettings()),
                safeBaseDurationsJson = json.encodeToString(DurationsMs()),
                safeCanonicalHeadWallMs = HeadWallMs,
                safeCanonicalHeadCounter = 0,
                ownedTimerId = ownedTimerId,
            ),
        )
        database.timerDao().insertCommand(
            PendingCommandEntity(
                id = StartId,
                deviceSequence = 1,
                timerId = StartTimerId,
                type = CommandType.Start,
                phase = TimerPhase.Focus,
                plannedDurationMs = DurationsMs().focus,
                occurredAt = StartAt,
                hlcWallMs = HeadWallMs + 1_000,
                hlcCounter = 0,
                observedElapsedMs = 0,
                physicalOccurredAt = StartAt,
                neverSent = false,
            ),
        )
    }

    private fun repository(): TimerRepository = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = OfflineService(),
        auth = SignedOutAuth(),
        json = json,
        networkAvailable = { false },
        replication = null,
    ).also(repositories::add)

    private class SignedOutAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) =
            error("unused")
        override fun hasTokens() = false
        override suspend fun <T> authorized(block: suspend (String) -> T) = error("unused")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class OfflineService : PomodoroughService {
        override suspend fun me(accessToken: String) = error("unused")
        override suspend fun bootstrap(accessToken: String) = error("unused")
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) =
            error("unused")
        override suspend fun sync(accessToken: String, request: SyncRequest) = error("unused")
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("unused")
    }

    private companion object {
        const val DeviceId = "device-a"
        const val StartId = "start-sent"
        const val StartTimerId = "focus-sent"
        const val RetiredTimerId = "focus-retired"
        const val StartAt = "2026-01-01T00:00:01Z"
        const val HeadWallMs = 1_767_225_600_000L
    }
}
