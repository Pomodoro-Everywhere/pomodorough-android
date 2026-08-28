package me.egigoka.pomodorough.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CentralizedSyncTransactionBoundaryTest {
    private lateinit var database: PomodoroughDatabase
    private lateinit var dao: TimerDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.timerDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskAndSelectionPersistenceRollsBackWhenSelectionConflicts() = runBlocking {
        val initial = state()
        val existingSelection = selection("selection-existing", "task-existing")
        dao.insertState(initial)
        dao.insertSelectedTaskOperation(existingSelection)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.persistTaskOperation(
                    task("task-new", "New task"),
                    initial.copy(revision = 1L),
                    existingSelection.copy(taskId = "task-changed"),
                )
            }
        }

        assertEquals(initial, dao.localState())
        assertEquals(emptyList<PendingTaskOperationEntity>(), dao.pendingTaskOperations())
        assertEquals(listOf(existingSelection), dao.pendingSelectedTaskOperations())
    }

    @Test
    fun bootstrapResolutionRollsBackAllQueueDeletesWhenRetainedCommandsConflict() = runBlocking {
        val initial = state()
        val originalCommand = command("command-original", 1L)
        val originalTask = task("task-original", "Original")
        val originalDuration = duration("duration-original")
        val originalAutoStart = autoStart("auto-original")
        val originalSelection = selection("selection-original", "task-original")
        val originalResolution = resolution()
        seedWorkspace(
            initial,
            originalCommand,
            originalTask,
            originalDuration,
            originalAutoStart,
            originalSelection,
            originalResolution,
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.applyBootstrapResolution(
                    state = initial.copy(revision = 2L),
                    retainedCommands = listOf(
                        command("retained-one", 2L),
                        command("retained-two", 2L),
                    ),
                )
            }
        }

        assertEquals(initial, dao.localState())
        assertEquals(listOf(originalCommand), dao.pendingCommands())
        assertEquals(listOf(originalTask), dao.pendingTaskOperations())
        assertEquals(listOf(originalDuration), dao.pendingDurationOperations())
        assertEquals(listOf(originalAutoStart), dao.pendingAutoStartOperations())
        assertEquals(listOf(originalSelection), dao.pendingSelectedTaskOperations())
        assertEquals(originalResolution, dao.pendingBootstrapResolution())
    }

    @Test
    fun clearAccountAtomicallyRemovesEveryPendingDomainAndPublishesReplacementState() = runBlocking {
        val initial = state()
        seedWorkspace(
            initial,
            command("command", 1L),
            task("task", "Task"),
            duration("duration"),
            autoStart("auto"),
            selection("selection", "task"),
            resolution(),
        )
        val signedOut = initial.copy(revision = 8L, ownerUserId = null, userJson = null)

        dao.clearAccount(signedOut)

        assertEquals(signedOut, dao.localState())
        assertEquals(emptyList<PendingCommandEntity>(), dao.pendingCommands())
        assertEquals(emptyList<PendingTaskOperationEntity>(), dao.pendingTaskOperations())
        assertEquals(emptyList<PendingDurationOperationEntity>(), dao.pendingDurationOperations())
        assertEquals(emptyList<PendingAutoStartOperationEntity>(), dao.pendingAutoStartOperations())
        assertEquals(emptyList<PendingSelectedTaskOperationEntity>(), dao.pendingSelectedTaskOperations())
        assertNull(dao.pendingBootstrapResolution())
    }

    @Test
    fun fullSyncAcknowledgesAndRewritesEveryPendingDomainInOneCommit() = runBlocking {
        val initial = state()
        val acknowledgedCommand = command("command-ack", 1L)
        val retainedCommand = command("command-retained", 2L)
        val acknowledgedTask = task("task-ack", "Ack")
        val retainedTask = task("task-retained", "Before")
        val acknowledgedDuration = duration("duration-ack", TimerPhase.Focus)
        val retainedDuration = duration("duration-retained", TimerPhase.ShortBreak)
        val acknowledgedAuto = autoStart("auto-ack")
        val retainedAuto = autoStart("auto-retained")
        val acknowledgedSelection = selection("selection-ack", "task-ack")
        val retainedSelection = selection("selection-retained", "task-retained")
        dao.insertState(initial)
        dao.insertCommands(listOf(acknowledgedCommand, retainedCommand))
        dao.insertTaskOperations(listOf(acknowledgedTask, retainedTask))
        dao.upsertDurationOperations(listOf(acknowledgedDuration, retainedDuration))
        dao.insertAutoStartOperations(listOf(acknowledgedAuto, retainedAuto))
        dao.insertSelectedTaskOperation(acknowledgedSelection)
        dao.insertSelectedTaskOperation(retainedSelection)
        val next = initial.copy(revision = 9L)
        val updatedCommand = retainedCommand.copy(observedElapsedMs = 10L)
        val updatedTask = retainedTask.copy(title = "After")
        val updatedDuration = retainedDuration.copy(durationMs = 600_000L)
        val updatedAuto = retainedAuto.copy(enabled = false)
        val updatedSelection = retainedSelection.copy(taskId = null)

        dao.applyFullSync(
            acknowledgedCommands = listOf(acknowledgedCommand),
            acknowledgedTaskOperations = listOf(acknowledgedTask),
            acknowledgedDurationOperationIds = listOf(acknowledgedDuration.id),
            state = next,
            acknowledgedAutoStartOperations = listOf(acknowledgedAuto),
            updatedCommands = listOf(updatedCommand),
            updatedTaskOperations = listOf(updatedTask),
            updatedDurationOperations = listOf(updatedDuration),
            updatedAutoStartOperations = listOf(updatedAuto),
            acknowledgedSelectedTaskOperations = listOf(acknowledgedSelection),
            updatedSelectedTaskOperations = listOf(updatedSelection),
        )

        assertEquals(next, dao.localState())
        assertEquals(listOf(updatedCommand), dao.pendingCommands())
        assertEquals(listOf(updatedTask), dao.pendingTaskOperations())
        assertEquals(listOf(updatedDuration), dao.pendingDurationOperations())
        assertEquals(listOf(updatedAuto), dao.pendingAutoStartOperations())
        assertEquals(listOf(updatedSelection), dao.pendingSelectedTaskOperations())
    }

    private suspend fun seedWorkspace(
        local: LocalStateEntity,
        command: PendingCommandEntity,
        task: PendingTaskOperationEntity,
        duration: PendingDurationOperationEntity,
        autoStart: PendingAutoStartOperationEntity,
        selection: PendingSelectedTaskOperationEntity,
        resolution: PendingBootstrapResolutionEntity,
    ) {
        dao.insertState(local)
        dao.insertCommand(command)
        dao.insertTaskOperation(task)
        dao.upsertDurationOperation(duration)
        dao.insertAutoStartOperation(autoStart)
        dao.insertSelectedTaskOperation(selection)
        dao.upsertBootstrapResolution(resolution)
    }

    private fun state() = LocalStateEntity(
        deviceId = "device-test",
        settingsJson = "{}",
        ownerUserId = "account-test",
        userJson = "{}",
    )

    private fun command(id: String, sequence: Long) = PendingCommandEntity(
        id = id,
        deviceSequence = sequence,
        timerId = "timer-$id",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = At,
        hlcWallMs = WallMs + sequence,
        hlcCounter = 0L,
        observedElapsedMs = 0L,
    )

    private fun task(id: String, title: String) = PendingTaskOperationEntity(
        id = id,
        taskId = "focus-$id",
        type = TaskOperationType.Upsert,
        title = title,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 1L,
    )

    private fun duration(
        id: String,
        phase: String = TimerPhase.Focus,
    ) = PendingDurationOperationEntity(
        phase = phase,
        id = id,
        durationMs = 300_000L,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 2L,
    )

    private fun autoStart(id: String) = PendingAutoStartOperationEntity(
        id = id,
        deviceId = "device-test",
        enabled = true,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 3L,
    )

    private fun selection(id: String, taskId: String?) = PendingSelectedTaskOperationEntity(
        id = id,
        taskId = taskId,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 4L,
    )

    private fun resolution() = PendingBootstrapResolutionEntity(
        requestId = "resolution-test",
        deviceId = "device-test",
        expectedRevision = 0L,
        strategy = "merge",
        commandsJson = "[]",
        taskOperationsJson = "[]",
        durationOperationsJson = "[]",
        ownerUserId = "account-test",
        userJson = "{}",
        autoStartOperationsJson = "[]",
        selectedTaskOperationsJson = "[]",
    )

    private companion object {
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
    }
}
