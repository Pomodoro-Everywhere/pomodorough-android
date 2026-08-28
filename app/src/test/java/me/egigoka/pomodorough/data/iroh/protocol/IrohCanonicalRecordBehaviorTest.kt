package me.egigoka.pomodorough.data.iroh.protocol

import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohCanonicalRecordBehaviorTest {
    @Test
    fun everyOperationDomainRoundTripsThroughStrictWireValidation() {
        operationRecords().forEach { record ->
            record.validate()
            assertEquals(record, IrohOperationRecord.fromJson(record.toJson()))
            assertEquals(32, Base64Url.decode(record.digest()).size)
            assertTrue(record.operationByteCount() > 0)
        }
    }

    @Test
    fun genesisRoundTripStripsLocalOnlyIntentDeviceAndPendingHistory() {
        val task = focusTask()
        val genesis = IrohGenesis(
            canonicalTimer = canonicalTimer(task.id),
            history = listOf(completedHistory(task.id), cancelledHistory()),
            tasks = listOf(task),
            durationsMs = DurationsMs(),
            autoStartBreaks = true,
            selectedTaskId = task.id,
            hlcWallMs = WallMs,
            hlcCounter = 5L,
        )

        val record = IrohOperationRecord.genesis(DeviceId, genesis)
        val decoded = IrohOperationRecord.fromJson(record.toJson())
        val decodedGenesis = decoded.decodeOperation<IrohGenesis>()

        assertEquals(null, decodedGenesis.canonicalTimer?.lastIntent?.deviceId)
        assertFalse(decoded.operation.toString().contains("pending"))
        assertEquals(genesis.history, decodedGenesis.history)
        assertEquals(genesis.tasks, decodedGenesis.tasks)
    }

    @Test
    fun timerOperationsRejectIdentitySequenceStateDurationClockAndTaskViolations() {
        val valid = timerCommand()
        val invalid = listOf(
            valid.copy(id = "bad"),
            valid.copy(timerId = "bad"),
            valid.copy(deviceSequence = 0L),
            valid.copy(type = "unknown"),
            valid.copy(phase = "unknown"),
            valid.copy(plannedDurationMs = 1L),
            valid.copy(observedElapsedMs = Long.MAX_VALUE),
            valid.copy(observedElapsedMs = 1L),
            valid.copy(type = CommandType.Pause, observedElapsedMs = -1L),
            valid.copy(
                type = CommandType.Pause,
                observedElapsedMs = valid.plannedDurationMs + 1L,
            ),
            valid.copy(taskId = "bad"),
            valid.copy(type = CommandType.Pause, taskId = focusTask().id),
            valid.copy(occurredAt = "invalid"),
        )

        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                IrohOperationRecord.timer(DeviceId, value).validate()
            }
        }
    }

    @Test
    fun taskDurationAutoStartAndSelectionRejectMalformedDomainSemantics() {
        val duration = durationOperation()
        val autoStart = autoStartOperation()
        val selection = selectedTaskOperation()
        val invalid = listOf(
            IrohOperationRecord.task(DeviceId, taskOperation().copy(type = "unknown")),
            IrohOperationRecord.duration(DeviceId, duration.copy(durationMs = 60_001L)),
            IrohOperationRecord.duration(DeviceId, duration.copy(phase = "unknown")),
            IrohOperationRecord.autoStart(DeviceId, autoStart.copy(id = "bad")),
            IrohOperationRecord.selectedTask(DeviceId, selection.copy(id = "bad")),
            IrohOperationRecord.selectedTask(DeviceId, selection.copy(taskId = "bad")),
        )

        invalid.forEach { record ->
            assertThrows(IllegalArgumentException::class.java, record::validate)
        }
    }

    @Test
    fun genesisRejectsInvalidDurationsClocksTasksSelectionTimerAndHistory() {
        val task = focusTask()
        val valid = IrohGenesis(
            canonicalTimer = canonicalTimer(task.id),
            history = listOf(completedHistory(task.id)),
            tasks = listOf(task),
            durationsMs = DurationsMs(),
            autoStartBreaks = false,
            selectedTaskId = task.id,
            hlcWallMs = WallMs,
            hlcCounter = 0L,
        )
        val invalid = listOf(
            valid.copy(durationsMs = valid.durationsMs.copy(focus = 60_001L)),
            valid.copy(hlcWallMs = -1L),
            valid.copy(tasks = listOf(task, task)),
            valid.copy(selectedTaskId = "bad"),
            valid.copy(canonicalTimer = valid.canonicalTimer?.copy(elapsedAtAnchorMs = Long.MAX_VALUE)),
            valid.copy(history = listOf(completedHistory(task.id), completedHistory(task.id))),
            valid.copy(history = listOf(completedHistory(task.id).copy(completedAt = null))),
            valid.copy(history = listOf(cancelledHistory().copy(endedAt = null))),
        )

        invalid.forEach { genesis ->
            assertThrows(IllegalArgumentException::class.java) {
                IrohOperationRecord.genesis(DeviceId, genesis).validate()
            }
        }
    }

    @Test
    fun nativeCodecDefersTaskIdentityDerivationToSharedCore() {
        val task = focusTask()
        IrohOperationRecord.task(
            DeviceId,
            taskOperation().copy(title = "different"),
        ).validate()
        IrohOperationRecord.genesis(
            DeviceId,
            IrohGenesis(
                canonicalTimer = null,
                history = emptyList(),
                tasks = listOf(task.copy(title = "different")),
                durationsMs = DurationsMs(),
                autoStartBreaks = false,
                selectedTaskId = null,
                hlcWallMs = WallMs,
                hlcCounter = 0L,
            ),
        ).validate()
    }

    @Test
    fun canonicalComparatorUsesClockDeviceAndIdentityAsStableTieBreakers() {
        val first = IrohOperationRecord.timer(DeviceId, timerCommand())
        val laterWall = IrohOperationRecord.timer(DeviceId, timerCommand().copy(id = "command-0002", hlcWallMs = WallMs + 1L))
        val laterCounter = IrohOperationRecord.timer(DeviceId, timerCommand().copy(id = "command-0002", hlcCounter = 1L))
        val laterDevice = IrohOperationRecord.timer("device-0002", timerCommand().copy(id = "command-0002"))
        val laterId = IrohOperationRecord.timer(DeviceId, timerCommand().copy(id = "command-0002"))

        listOf(laterWall, laterCounter, laterDevice, laterId).forEach { record ->
            assertTrue(IrohOperationRecord.canonicalComparator.compare(first, record) < 0)
        }
    }

    private fun operationRecords() = listOf(
        IrohOperationRecord.timer(DeviceId, timerCommand()),
        IrohOperationRecord.task(DeviceId, taskOperation()),
        IrohOperationRecord.duration(DeviceId, durationOperation()),
        IrohOperationRecord.autoStart(DeviceId, autoStartOperation()),
        IrohOperationRecord.selectedTask(DeviceId, selectedTaskOperation()),
    )

    private fun timerCommand() = TimerCommand(
        id = "command-0001",
        deviceSequence = 1L,
        timerId = "timer-0001",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 0L,
        observedElapsedMs = 0L,
        taskId = focusTask().id,
    )

    private fun taskOperation(): TaskOperation {
        val task = focusTask()
        return TaskOperation("task-op-0001", task.id, TaskOperationType.Upsert, task.title, At, WallMs, 1L)
    }

    private fun durationOperation() = DurationOperation(
        "duration-op-0001", TimerPhase.Focus, 1_500_000L, At, WallMs, 2L,
    )

    private fun autoStartOperation() = AutoStartOperation(
        "auto-op-0001", DeviceId, true, At, WallMs, 3L,
    )

    private fun selectedTaskOperation() = SelectedTaskOperation(
        "selected-op-0001", focusTask().id, At, WallMs, 4L,
    )

    private fun canonicalTimer(taskId: String) = CanonicalTimer(
        id = "timer-0001",
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000L,
        elapsedAtAnchorMs = 10L,
        anchorAt = At,
        taskId = taskId,
        startedByDeviceId = DeviceId,
        lastIntent = TimerIntent(CommandType.Start, "command-0001", At, DeviceId),
    )

    private fun completedHistory(taskId: String) = HistoryItem(
        id = "history-0001",
        timerId = "timer-history-0001",
        commandId = "finish-0001",
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000L,
        completedAt = At,
        taskId = taskId,
    )

    private fun cancelledHistory() = HistoryItem(
        id = "history-0002",
        timerId = "timer-history-0002",
        commandId = "cancel-0002",
        phase = TimerPhase.ShortBreak,
        status = TimerStatus.Cancelled,
        plannedDurationMs = 300_000L,
        endedAt = At,
    )

    private fun focusTask() = requireNotNull(TaskReducer.taskFromTitle("Write coverage"))

    private companion object {
        const val DeviceId = "device-0001"
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
    }
}
