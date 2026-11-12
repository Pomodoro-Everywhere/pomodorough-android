package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneOffset
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Core 0.56.0 stores a rename as a `rename` operation on the old identity that
 * names the new title. The bundled Core moves finished history to the renamed
 * task, a row that starts after the rename stays on a recreated title, and the
 * saved queue accepts the operation only with its title.
 *
 * The type is written as its wire string so the test also runs against a tree
 * that predates the constant.
 */
class TaskRenameOperationTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatcher by lazy { CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) } }

    @Test
    fun renameCarriesFinishedFocusHistoryToTheRenamedTask() {
        val result = dispatcher.apply(
            base = CoreProjectionBase(),
            pending = CoreProjectionPending(
                commands = focusSession(StartedAt),
                taskOperations = listOf(
                    upsert("op-1", alpha, StartedAt.minusSeconds(60), 0),
                    upsert("op-2", beta, RenamedAt, 10),
                    rename("op-3", alpha, beta.title, RenamedAt, 11),
                ).map { DeviceOperation(DeviceId, it) },
            ),
            now = RenamedAt,
        )

        assertEquals(listOf(beta), result.tasks)
        assertEquals(listOf(beta.id), result.history.map(HistoryItem::taskId))
        val summary = TaskReducer.summariesToday(result.tasks, result.history, RenamedAt, ZoneOffset.UTC).single()
        assertEquals(beta, summary.task)
        assertEquals(1, summary.finishedPomodoros)
    }

    @Test
    fun aRowThatStartsAfterTheRenameStaysOnTheRecreatedTitle() {
        val renamedBeforeStart = StartedAt.minusSeconds(600)
        val result = dispatcher.apply(
            base = CoreProjectionBase(),
            pending = CoreProjectionPending(
                commands = focusSession(StartedAt),
                taskOperations = listOf(
                    upsert("op-1", alpha, renamedBeforeStart.minusSeconds(60), 0),
                    upsert("op-2", beta, renamedBeforeStart, 1),
                    rename("op-3", alpha, beta.title, renamedBeforeStart, 2),
                    upsert("op-4", alpha, renamedBeforeStart.plusSeconds(60), 3),
                ).map { DeviceOperation(DeviceId, it) },
            ),
            now = RenamedAt,
        )

        assertEquals(setOf(alpha, beta), result.tasks.toSet())
        assertEquals(listOf(alpha.id), result.history.map(HistoryItem::taskId))
    }

    @Test
    fun savedQueueAcceptsARenameWithItsTitleAndRejectsOneWithout() {
        val valid = rename("op-1", alpha, beta.title, RenamedAt, 0)

        TimerSyncValidation.validatePendingQueues(queues(valid), DeviceId)
        val error = assertThrows(SyncProtocolException::class.java) {
            TimerSyncValidation.validatePendingQueues(queues(valid.copy(title = null)), DeviceId)
        }
        assertEquals("Queued task operation is invalid", error.message)
    }

    @Test
    fun clientReducerRemovesTheRenamedIdentityLikeADelete() {
        val operations = listOf(
            upsert("op-1", alpha, StartedAt, 0),
            upsert("op-2", beta, RenamedAt, 1),
            rename("op-3", alpha, beta.title, RenamedAt, 2),
        )

        assertEquals(listOf(beta), TaskReducer.replay(emptyList(), operations))
    }

    private fun focusSession(startedAt: Instant): List<DeviceOperation<TimerCommand>> {
        val finishedAt = startedAt.plusMillis(PlanMs)
        return listOf(
            command("command-1", 1, CommandType.Start, startedAt, 0),
            command("command-2", 2, CommandType.Finish, finishedAt, PlanMs),
        ).map { DeviceOperation(DeviceId, it) }
    }

    private fun command(id: String, sequence: Long, type: String, at: Instant, observed: Long) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = "timer-1",
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = PlanMs,
        occurredAt = at.toString(),
        hlcWallMs = at.toEpochMilli(),
        hlcCounter = 0,
        observedElapsedMs = observed,
        taskId = alpha.id,
        physicalOccurredAt = at.toString(),
    )

    private fun upsert(id: String, task: FocusTask, at: Instant, counter: Long) =
        TaskOperation(id, task.id, TaskOperationType.Upsert, task.title, at.toString(), at.toEpochMilli(), counter)

    private fun rename(id: String, task: FocusTask, title: String, at: Instant, counter: Long) =
        TaskOperation(id, task.id, RenameType, title, at.toString(), at.toEpochMilli(), counter)

    private fun queues(operation: TaskOperation) = PendingSyncQueues(
        commands = emptyList(),
        taskOperations = listOf(operation),
        durationOperations = emptyList(),
        autoStartOperations = emptyList(),
        selectedTaskOperations = emptyList(),
    )

    private companion object {
        const val RenameType = "rename"
        const val DeviceId = "device-1"
        const val PlanMs = 1_500_000L
        val StartedAt: Instant = Instant.parse("2026-01-01T09:00:00Z")
        val RenamedAt: Instant = Instant.parse("2026-01-01T09:30:00Z")
        val alpha = requireNotNull(TaskReducer.taskFromTitle("Alpha"))
        val beta = requireNotNull(TaskReducer.taskFromTitle("Beta"))
    }
}
