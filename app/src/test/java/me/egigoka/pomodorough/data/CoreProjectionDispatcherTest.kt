package me.egigoka.pomodorough.data

import java.time.Instant
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.domain.SettingsReducer
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.LegacyTimerReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreProjectionDispatcherTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatcher by lazy {
        CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }
    }

    @Test
    fun projectionMatchesLegacyReducersAcrossSynchronizedDomains() {
        val deviceId = "device-1"
        val task = requireNotNull(TaskReducer.taskFromTitle("Café"))
        val command = TimerCommand(
            id = "command-1",
            deviceSequence = 1,
            timerId = "timer-1",
            type = CommandType.Start,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_800_000,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
            observedElapsedMs = 0,
            taskId = task.id,
            physicalOccurredAt = "2026-01-01T00:00:00Z",
        )
        val taskOperation = TaskOperation(
            id = "task-operation-1",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_002,
            hlcCounter = 0,
        )
        val durationOperation = DurationOperation(
            id = "duration-operation-1",
            phase = TimerPhase.Focus,
            durationMs = 1_800_000,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_003,
            hlcCounter = 0,
        )
        val autoStartOperation = AutoStartOperation(
            id = "auto-start-operation-1",
            deviceId = deviceId,
            enabled = true,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_004,
            hlcCounter = 0,
        )
        val selectedTaskOperation = SelectedTaskOperation(
            id = "selected-task-operation-1",
            taskId = task.id,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_005,
            hlcCounter = 0,
        )

        val actual = dispatcher.apply(
            base = CoreProjectionBase(),
            pending = CoreProjectionPending(
                commands = listOf(DeviceOperation(deviceId, command)),
                taskOperations = listOf(DeviceOperation(deviceId, taskOperation)),
                durationOperations = listOf(DeviceOperation(deviceId, durationOperation)),
                autoStartOperations = listOf(DeviceOperation(deviceId, autoStartOperation)),
                selectedTaskOperations = listOf(DeviceOperation(deviceId, selectedTaskOperation)),
            ),
            now = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val nativeTimer = requireNotNull(LegacyTimerReducer.replay(null, emptyList(), listOf(command)).timer)
        assertEquals(
            nativeTimer,
            actual.canonicalTimer?.copy(
                startedByDeviceId = null,
                lastIntent = actual.canonicalTimer.lastIntent?.copy(deviceId = null),
            ),
        )
        assertEquals(TaskReducer.replay(emptyList(), listOf(taskOperation)), actual.tasks)
        assertEquals(
            SettingsReducer.replayDurations(TimerSettings(), listOf(durationOperation))
                .effectiveDurationsMs(),
            actual.durationsMs,
        )
        assertEquals(SettingsReducer.replayAutoStart(false, listOf(autoStartOperation)), actual.autoStartBreaks)
        assertEquals(task.id, actual.selectedTaskId)
        assertEquals("applied", actual.timerOutcomes[command.id]?.outcome)
        assertEquals(taskOperation.id, actual.winningOperationIds.tasks[task.id])
        assertEquals(durationOperation.id, actual.winningOperationIds.durations[TimerPhase.Focus])
        assertEquals(autoStartOperation.id, actual.winningOperationIds.autoStart)
        assertEquals(selectedTaskOperation.id, actual.winningOperationIds.selectedTask)
        assertNotNull(actual.canonicalTimer?.startedByDeviceId)
    }

    @Test
    fun terminalTimerDuplicatedInHistoryIsMappedToHistoryOnlyForCore() {
        val completed = CanonicalTimer(
            id = "timer-1",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 1_500_000,
            elapsedAtAnchorMs = 1_500_000,
            anchorAt = "2026-01-01T00:25:00Z",
        )
        val history = listOf(
            HistoryItem(
                id = "history-1",
                timerId = completed.id,
                phase = completed.phase,
                status = completed.status,
                plannedDurationMs = completed.plannedDurationMs,
                completedAt = completed.anchorAt,
                endedAt = completed.anchorAt,
            ),
        )

        val actual = dispatcher.apply(
            base = CoreProjectionBase(canonicalTimer = completed, history = history),
            pending = CoreProjectionPending(),
            now = Instant.parse("2026-01-01T00:25:00Z"),
        )

        assertEquals(null, actual.canonicalTimer)
        assertEquals(history, actual.history)
    }

    @Test
    fun sharedCoreIsAuthoritativeForTaskIdentityAtReplicationBoundary() {
        assertThrows(SharedCoreException.Operation::class.java) {
            dispatcher.apply(
                base = CoreProjectionBase(
                    tasks = listOf(FocusTask("00000000-0000-0000-0000-000000000000", "Café")),
                ),
                pending = CoreProjectionPending(),
                now = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
    }
}
