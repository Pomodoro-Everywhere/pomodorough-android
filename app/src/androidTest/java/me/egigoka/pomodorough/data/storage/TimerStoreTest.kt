package me.egigoka.pomodorough.data.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.PendingSyncQueues
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerStoreTest {
    private lateinit var database: PomodoroughDatabase
    private lateinit var dao: TimerDao
    private lateinit var store: TimerStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.timerDao()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        store = TimerStore(dao, json, Json(from = json) { ignoreUnknownKeys = false }) {}
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun timerCommandBatchAndLocalStateRollbackTogether() = runBlocking {
        val initial = state()
        dao.insertState(initial)
        val next = initial.copy(deviceSequence = 2L)

        assertConstraintFailure {
            store.saveTimerCommands(
                next,
                listOf(command("one", 1L), command("two", 1L)),
                emptyMap(),
            )
        }

        assertEquals(initial, dao.localState())
        assertEquals(emptyList<PendingCommandEntity>(), dao.pendingCommands())
    }

    @Test
    fun fullSyncQueueUpdatesAndLocalStateRollbackTogether() = runBlocking {
        val initial = state()
        val first = command("one", 1L)
        val second = command("two", 2L)
        dao.insertState(initial)
        dao.insertCommands(listOf(first, second).map(PendingCommandEntity::from))
        val retained = queues(listOf(first.copy(deviceSequence = 3L), second.copy(deviceSequence = 3L)))

        assertConstraintFailure {
            store.applyFullSync(
                FullSyncStorageUpdate(
                    local = initial.copy(revision = 1L),
                    acknowledged = request(),
                    acknowledgedDurationOperationIds = emptyList(),
                    retained = retained,
                    retainedCommandDependencies = emptyMap(),
                    discardedCommands = emptyList(),
                    discardedCommandDependencies = emptyMap(),
                ),
            )
        }

        assertEquals(initial, dao.localState())
        assertEquals(listOf(first, second), dao.pendingCommands().map(PendingCommandEntity::toModel))
    }

    private fun state() = LocalStateEntity(deviceId = "device", settingsJson = "{}")

    private suspend fun assertConstraintFailure(block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: Exception) {
            error
        }
        assertTrue(error is SQLiteConstraintException)
    }

    private fun queues(commands: List<TimerCommand>) = PendingSyncQueues(
        commands,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    private fun request() = SyncRequest(
        deviceId = "device",
        lastRevision = 0L,
        commands = emptyList(),
        durationOperations = emptyList(),
    )

    private fun command(id: String, sequence: Long) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = "timer",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 100L + sequence,
        hlcCounter = 0L,
        observedElapsedMs = 0L,
    )
}
