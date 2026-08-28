package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.storage.TimerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerStoreRestartPositiveIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val strictJson = Json(from = json) { ignoreUnknownKeys = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DatabaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun allQueueTypesDependenciesAndAccountSurviveRestartInProtocolOrder() = runBlocking {
        val fixtures = Fixtures()
        val state = fixtures.state(json)
        val store = store()
        database.timerDao().insertState(state)

        store.saveTimerCommands(state, fixtures.commands.reversed(), fixtures.dependencies)
        store.saveTaskOperation(state, fixtures.task, fixtures.selection)
        store.saveDurationOperation(state, fixtures.duration)
        store.saveAutoStartOperation(state, fixtures.autoStart)
        database.close()
        database = openDatabase()

        val initialized = store().initialize()
        val workspace = store().loadWorkspace()

        assertEquals(fixtures.commands, initialized.commands)
        assertEquals(fixtures.dependencies, initialized.commandDependencies)
        assertEquals(fixtures.commands, workspace.pending.commands)
        assertEquals(listOf(fixtures.task), workspace.pending.taskOperations)
        assertEquals(listOf(fixtures.duration), workspace.pending.durationOperations)
        assertEquals(listOf(fixtures.autoStart), workspace.pending.autoStartOperations)
        assertEquals(listOf(fixtures.selection), workspace.pending.selectedTaskOperations)
        assertEquals(fixtures.dependencies, workspace.commandDependencies)
        assertEquals(fixtures.user, workspace.user)
        assertEquals(fixtures.tasks.associateBy(FocusTask::id), workspace.knownTasks)
    }

    private fun store() = TimerStore(database.timerDao(), json, strictJson) { user ->
        require(user.id == "user-1")
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        PomodoroughDatabase::class.java,
        DatabaseName,
    ).build()

    private class Fixtures {
        val commands = listOf(command("command-1", 1L), command("command-2", 2L))
        val dependencies = mapOf("command-2" to "command-1")
        val task = TaskOperation(
            "task-operation", "task-2", TaskOperationType.Upsert, "Second",
            At, 103L, 0L,
        )
        val selection = SelectedTaskOperation("selection", "task-2", At, 104L, 0L)
        val duration = DurationOperation("duration", TimerPhase.Focus, 1_800_000L, At, 105L, 0L)
        val autoStart = AutoStartOperation("auto-start", "device", true, At, 106L, 0L)
        val user = User("user-1", "u@example.com", "User", "")
        val tasks = listOf(FocusTask("task-1", "First"), FocusTask("task-2", "Second"))

        fun state(json: Json) = LocalStateEntity(
            deviceId = "device",
            deviceSequence = 2L,
            hlcWallMs = 106L,
            settingsJson = json.encodeToString(TimerSettings()),
            userJson = json.encodeToString(user),
            ownerUserId = user.id,
            tasksJson = json.encodeToString(tasks.take(1)),
            knownTasksJson = json.encodeToString(tasks.drop(1)),
            selectedTaskId = "task-2",
        )
    }

    private companion object {
        const val DatabaseName = "timer-store-positive-matrix.db"
        const val At = "1970-01-01T00:00:00.100Z"

        fun command(id: String, sequence: Long) = TimerCommand(
            id, sequence, "timer", CommandType.Start, TimerPhase.Focus,
            1_500_000L, At, 100L + sequence, 0L, 0L,
            physicalOccurredAt = At,
        )
    }
}
