package me.egigoka.pomodorough.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings

internal data class DaoCall(val name: String, val arguments: List<Any?> = emptyList())

internal class RecordingCentralizedSyncDao : CentralizedSyncDao {
    val calls = mutableListOf<DaoCall>()
    var failOn: String? = null

    private fun record(name: String, vararg arguments: Any?) {
        calls += DaoCall(name, arguments.toList())
        if (failOn == name) throw IllegalStateException("$name failed")
    }

    override suspend fun localState(): LocalStateEntity? = null
    override suspend fun pendingCommands(): List<PendingCommandEntity> = emptyList()
    override suspend fun pendingTaskOperations(): List<PendingTaskOperationEntity> = emptyList()
    override suspend fun pendingDurationOperations(): List<PendingDurationOperationEntity> = emptyList()
    override suspend fun pendingAutoStartOperations(): List<PendingAutoStartOperationEntity> = emptyList()
    override suspend fun pendingSelectedTaskOperations(): List<PendingSelectedTaskOperationEntity> = emptyList()
    override suspend fun pendingBootstrapResolution(): PendingBootstrapResolutionEntity? = null

    override suspend fun insertState(state: LocalStateEntity) = record("insertState", state)
    override suspend fun updateState(state: LocalStateEntity) = record("updateState", state)
    override suspend fun insertCommand(command: PendingCommandEntity) = record("insertCommand", command)
    override suspend fun insertCommands(commands: List<PendingCommandEntity>) =
        record("insertCommands", commands)
    override suspend fun insertTaskOperations(operations: List<PendingTaskOperationEntity>) =
        record("insertTaskOperations", operations)
    override suspend fun upsertDurationOperations(operations: List<PendingDurationOperationEntity>) =
        record("upsertDurationOperations", operations)
    override suspend fun insertAutoStartOperations(operations: List<PendingAutoStartOperationEntity>) =
        record("insertAutoStartOperations", operations)
    override suspend fun updateCommands(commands: List<PendingCommandEntity>) =
        record("updateCommands", commands)
    override suspend fun updateTaskOperations(operations: List<PendingTaskOperationEntity>) =
        record("updateTaskOperations", operations)
    override suspend fun updateDurationOperations(operations: List<PendingDurationOperationEntity>) =
        record("updateDurationOperations", operations)
    override suspend fun updateAutoStartOperations(operations: List<PendingAutoStartOperationEntity>) =
        record("updateAutoStartOperations", operations)
    override suspend fun updateSelectedTaskOperations(
        operations: List<PendingSelectedTaskOperationEntity>,
    ) = record("updateSelectedTaskOperations", operations)
    override suspend fun insertTaskOperation(operation: PendingTaskOperationEntity) =
        record("insertTaskOperation", operation)
    override suspend fun upsertDurationOperation(operation: PendingDurationOperationEntity) =
        record("upsertDurationOperation", operation)
    override suspend fun insertAutoStartOperation(operation: PendingAutoStartOperationEntity) =
        record("insertAutoStartOperation", operation)
    override suspend fun insertSelectedTaskOperation(operation: PendingSelectedTaskOperationEntity) =
        record("insertSelectedTaskOperation", operation)
    override suspend fun deleteCommands(commands: List<PendingCommandEntity>) =
        record("deleteCommands", commands)
    override suspend fun deleteTaskOperations(operations: List<PendingTaskOperationEntity>) =
        record("deleteTaskOperations", operations)
    override suspend fun deleteAutoStartOperations(operations: List<PendingAutoStartOperationEntity>) =
        record("deleteAutoStartOperations", operations)
    override suspend fun deleteAllCommands() = record("deleteAllCommands")
    override suspend fun deleteAllTaskOperations() = record("deleteAllTaskOperations")
    override suspend fun deleteDurationOperationsById(operationIds: List<String>) =
        record("deleteDurationOperationsById", operationIds)
    override suspend fun deleteAllDurationOperations() = record("deleteAllDurationOperations")
    override suspend fun deleteAllAutoStartOperations() = record("deleteAllAutoStartOperations")
    override suspend fun deleteAllSelectedTaskOperations() = record("deleteAllSelectedTaskOperations")
    override suspend fun deleteSelectedTaskOperations(
        operations: List<PendingSelectedTaskOperationEntity>,
    ) = record("deleteSelectedTaskOperations", operations)
    override suspend fun upsertBootstrapResolution(resolution: PendingBootstrapResolutionEntity) =
        record("upsertBootstrapResolution", resolution)
    override suspend fun deleteBootstrapResolution() = record("deleteBootstrapResolution")
}

internal object DaoBoundaryFixtures {
    private const val At = "2026-01-01T00:00:00Z"
    private val json = Json { explicitNulls = false }

    val local = LocalStateEntity(
        deviceId = "device-0001",
        settingsJson = json.encodeToString(TimerSettings()),
    )
    val command = PendingCommandEntity.from(
        TimerCommand(
            id = "command-0001",
            deviceSequence = 1,
            timerId = "timer-0001",
            type = CommandType.Start,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = At,
            hlcWallMs = 1,
            hlcCounter = 0,
            observedElapsedMs = 0,
        ),
        "finish-0001",
    )
    val task = PendingTaskOperationEntity.from(
        TaskOperation("task-op-0001", "task-0001", TaskOperationType.Upsert, "Task", At, 2, 0),
    )
    val duration = PendingDurationOperationEntity.from(
        DurationOperation("duration-op-0001", TimerPhase.Focus, 1_500_000, At, 3, 0),
    )
    val autoStart = PendingAutoStartOperationEntity.from(
        AutoStartOperation("auto-op-0001", "device-0001", true, At, 4, 0),
    )
    val selected = PendingSelectedTaskOperationEntity.from(
        SelectedTaskOperation("selected-op-0001", "task-0001", At, 5, 0),
    )
    val resolution = PendingBootstrapResolutionEntity(
        requestId = "resolution-0001",
        deviceId = "device-0001",
        expectedRevision = 7,
        strategy = "merge",
        commandsJson = "[]",
        taskOperationsJson = "[]",
        durationOperationsJson = "[]",
        ownerUserId = "account-0001",
        userJson = "{}",
    )
}
