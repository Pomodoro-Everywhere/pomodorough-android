package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity

internal object WorkspaceCodec {
    fun encode(snapshot: LocalWorkspaceSnapshot): String = IrohJson.strict.encodeToString(snapshot.toStored())
    fun decode(value: String): LocalWorkspaceSnapshot =
        IrohJson.strict.decodeFromString<StoredWorkspace>(value).toEntity()
}

@Serializable
private data class StoredWorkspace(
    val local: StoredLocalState,
    val commands: List<StoredCommand>,
    val taskOperations: List<StoredTaskOperation>,
    val durationOperations: List<StoredDurationOperation>,
    val autoStartOperations: List<StoredAutoStartOperation>,
    val selectedTaskOperations: List<StoredSelectedTaskOperation> = emptyList(),
    val bootstrapResolution: StoredBootstrapResolution?,
)

@Serializable
private data class StoredLocalState(
    val id: Int,
    val deviceId: String,
    val deviceSequence: Long,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val revision: Long,
    val canonicalTimerJson: String?,
    val historyJson: String,
    val settingsJson: String,
    val userJson: String?,
    val ownerUserId: String?,
    val tasksJson: String,
    val knownTasksJson: String,
    val selectedTaskId: String?,
    val canonicalAutoStartBreaks: Boolean,
    val ownedTimerId: String?,
    val serverClockOffsetMs: Long?,
    val serverClockUncertaintyMs: Long?,
    val serverClockSamplePhysicalMs: Long?,
    val serverClockSampleElapsedRealtimeMs: Long?,
    val serverClockBootId: String?,
    val lastUuidV7: String?,
)

@Serializable
private data class StoredCommand(
    val id: String,
    val deviceSequence: Long,
    val timerId: String,
    val type: String,
    val phase: String,
    val plannedDurationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val observedElapsedMs: Long,
    val taskId: String?,
    val generatedByFinishCommandId: String?,
    val physicalOccurredAt: String?,
)

@Serializable
private data class StoredTaskOperation(
    val id: String,
    val taskId: String,
    val type: String,
    val title: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
private data class StoredDurationOperation(
    val phase: String,
    val id: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
private data class StoredAutoStartOperation(
    val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
private data class StoredSelectedTaskOperation(
    val id: String,
    val taskId: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
private data class StoredBootstrapResolution(
    val id: Int,
    val requestId: String,
    val deviceId: String,
    val expectedRevision: Long,
    val strategy: String,
    val commandsJson: String,
    val taskOperationsJson: String,
    val durationOperationsJson: String,
    val ownerUserId: String,
    val userJson: String,
    val autoStartOperationsJson: String?,
    val selectedTaskOperationsJson: String? = null,
)

private fun LocalWorkspaceSnapshot.toStored() = StoredWorkspace(
    local = local.run {
        StoredLocalState(
            id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
            canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
            tasksJson, knownTasksJson, selectedTaskId, canonicalAutoStartBreaks, ownedTimerId,
            serverClockOffsetMs, serverClockUncertaintyMs, serverClockSamplePhysicalMs,
            serverClockSampleElapsedRealtimeMs, serverClockBootId, lastUuidV7,
        )
    },
    commands = commands.map { value -> value.run {
        StoredCommand(
            id, deviceSequence, timerId, type, phase, plannedDurationMs, occurredAt,
            hlcWallMs, hlcCounter, observedElapsedMs, taskId, generatedByFinishCommandId,
            physicalOccurredAt,
        )
    } },
    taskOperations = taskOperations.map { value -> value.run {
        StoredTaskOperation(id, taskId, type, title, occurredAt, hlcWallMs, hlcCounter)
    } },
    durationOperations = durationOperations.map { value -> value.run {
        StoredDurationOperation(phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter)
    } },
    autoStartOperations = autoStartOperations.map { value -> value.run {
        StoredAutoStartOperation(id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter)
    } },
    selectedTaskOperations = selectedTaskOperations.map { value -> value.run {
        StoredSelectedTaskOperation(id, taskId, occurredAt, hlcWallMs, hlcCounter)
    } },
    bootstrapResolution = bootstrapResolution?.run {
        StoredBootstrapResolution(
            id, requestId, deviceId, expectedRevision, strategy, commandsJson,
            taskOperationsJson, durationOperationsJson, ownerUserId, userJson,
            autoStartOperationsJson, selectedTaskOperationsJson,
        )
    },
)

private fun StoredWorkspace.toEntity() = LocalWorkspaceSnapshot(
    local = local.run {
        LocalStateEntity(
            id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
            canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
            tasksJson, knownTasksJson, selectedTaskId, canonicalAutoStartBreaks, ownedTimerId,
            serverClockOffsetMs, serverClockUncertaintyMs, serverClockSamplePhysicalMs,
            serverClockSampleElapsedRealtimeMs, serverClockBootId, lastUuidV7,
        )
    },
    commands = commands.map { value -> value.run {
        PendingCommandEntity(
            id, deviceSequence, timerId, type, phase, plannedDurationMs, occurredAt,
            hlcWallMs, hlcCounter, observedElapsedMs, taskId, generatedByFinishCommandId,
            physicalOccurredAt,
        )
    } },
    taskOperations = taskOperations.map { value -> value.run {
        PendingTaskOperationEntity(id, taskId, type, title, occurredAt, hlcWallMs, hlcCounter)
    } },
    durationOperations = durationOperations.map { value -> value.run {
        PendingDurationOperationEntity(phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter)
    } },
    autoStartOperations = autoStartOperations.map { value -> value.run {
        PendingAutoStartOperationEntity(id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter)
    } },
    selectedTaskOperations = selectedTaskOperations.map { value -> value.run {
        PendingSelectedTaskOperationEntity(id, taskId, occurredAt, hlcWallMs, hlcCounter)
    } },
    bootstrapResolution = bootstrapResolution?.run {
        PendingBootstrapResolutionEntity(
            id, requestId, deviceId, expectedRevision, strategy, commandsJson,
            taskOperationsJson, durationOperationsJson, ownerUserId, userJson,
            autoStartOperationsJson, selectedTaskOperationsJson,
        )
    },
)
