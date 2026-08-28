package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceSnapshotPositiveIntegrationTest {
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun replacementCommitsEveryQueueAndBootstrapBoundaryAsOneSnapshot() = runBlocking {
        val dao = database.timerDao()
        dao.insertState(state("old", 1))
        dao.insertCommand(command("old", 1))
        dao.insertTaskOperation(task("old"))
        dao.upsertDurationOperation(duration("old", "focus"))
        dao.insertAutoStartOperation(autoStart("old"))
        dao.insertSelectedTaskOperation(selection("old"))
        dao.upsertBootstrapResolution(resolution("old"))
        val replacement = snapshot("new", 9)

        dao.replaceWorkspace(replacement)

        assertEquals(replacement, dao.localWorkspaceSnapshot())
    }

    @Test
    fun emptyReplacementClearsEveryOptionalQueueWithoutLeakingPriorRows() = runBlocking {
        val dao = database.timerDao()
        dao.replaceWorkspace(snapshot("old", 1))
        val empty = LocalWorkspaceSnapshot(
            local = state("empty", 2),
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
            bootstrapResolution = null,
        )

        dao.replaceWorkspace(empty)

        assertEquals(empty, dao.localWorkspaceSnapshot())
    }

    private fun snapshot(prefix: String, revision: Long) = LocalWorkspaceSnapshot(
        local = state(prefix, revision),
        commands = listOf(command(prefix, revision)),
        taskOperations = listOf(task(prefix)),
        durationOperations = listOf(duration(prefix, "focus")),
        autoStartOperations = listOf(autoStart(prefix)),
        selectedTaskOperations = listOf(selection(prefix)),
        bootstrapResolution = resolution(prefix),
    )

    private fun state(prefix: String, revision: Long) = LocalStateEntity(
        deviceId = "device-$prefix", revision = revision, settingsJson = "{}",
    )

    private fun command(prefix: String, sequence: Long) = PendingCommandEntity(
        id = "command-$prefix", deviceSequence = sequence, timerId = "timer-$prefix",
        type = "start", phase = "focus", plannedDurationMs = 60_000,
        occurredAt = At, hlcWallMs = sequence, hlcCounter = 0, observedElapsedMs = 0,
    )

    private fun task(prefix: String) = PendingTaskOperationEntity(
        id = "task-op-$prefix", taskId = "task-$prefix", type = "upsert",
        title = prefix, occurredAt = At, hlcWallMs = 1, hlcCounter = 0,
    )

    private fun duration(prefix: String, phase: String) = PendingDurationOperationEntity(
        phase = phase, id = "duration-$prefix", durationMs = 60_000,
        occurredAt = At, hlcWallMs = 2, hlcCounter = 0,
    )

    private fun autoStart(prefix: String) = PendingAutoStartOperationEntity(
        id = "auto-$prefix", deviceId = "device-$prefix", enabled = true,
        occurredAt = At, hlcWallMs = 3, hlcCounter = 0,
    )

    private fun selection(prefix: String) = PendingSelectedTaskOperationEntity(
        id = "selection-$prefix", taskId = "task-$prefix", occurredAt = At,
        hlcWallMs = 4, hlcCounter = 0,
    )

    private fun resolution(prefix: String) = PendingBootstrapResolutionEntity(
        requestId = "resolution-$prefix", deviceId = "device-$prefix", expectedRevision = 0,
        strategy = "merge", commandsJson = "[]", taskOperationsJson = "[]",
        durationOperationsJson = "[]", ownerUserId = "account-$prefix", userJson = "{}",
    )

    private companion object {
        const val At = "2026-01-01T00:00:00Z"
    }
}
