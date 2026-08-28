package me.egigoka.pomodorough.integration.negative

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.storage.TimerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerStoreRollbackNegativeIntegrationTest {
    private lateinit var database: PomodoroughDatabase
    private lateinit var store: TimerStore
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        store = TimerStore(
            database.timerDao(),
            json,
            Json(from = json) { ignoreUnknownKeys = false },
        ) {}
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateSelectionRollsBackTaskQueueAndClockStateAtomically() = runBlocking {
        val dao = database.timerDao()
        val initial = LocalStateEntity(
            deviceId = "device",
            hlcWallMs = 100L,
            settingsJson = json.encodeToString(TimerSettings()),
        )
        val existingSelection = selection("selection", "task-old", 100L)
        dao.insertState(initial)
        dao.insertSelectedTaskOperation(PendingSelectedTaskOperationEntity.from(existingSelection))
        val task = TaskOperation(
            "task-operation", "task-new", TaskOperationType.Upsert, "New task",
            At, 101L, 0L,
        )
        val conflictingSelection = selection(existingSelection.id, "task-new", 101L)
        val changed = initial.copy(hlcWallMs = 101L, selectedTaskId = "task-new")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { store.saveTaskOperation(changed, task, conflictingSelection) }
        }

        assertEquals(initial, dao.localState())
        assertEquals(emptyList<Any>(), dao.pendingTaskOperations())
        assertEquals(
            listOf(existingSelection),
            dao.pendingSelectedTaskOperations().map(PendingSelectedTaskOperationEntity::toModel),
        )
    }

    private fun selection(id: String, taskId: String, wallMs: Long) =
        SelectedTaskOperation(id, taskId, At, wallMs, 0L)

    private companion object {
        const val At = "1970-01-01T00:00:00.100Z"
    }
}
