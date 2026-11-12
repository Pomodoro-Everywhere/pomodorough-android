package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.local.LegacyDependencyAdoptionPending
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity

internal object WorkspaceCodec {
    /**
     * The centralized workspace stashed while a room is active. It keeps the R43-A03 safe base,
     * so leaving the room rebuilds from the same canonical inputs it had before joining.
     */
    fun encodeReturnState(snapshot: LocalWorkspaceSnapshot): String = encode(snapshot, carrySafeBase = true)

    /** R52-A02: a room workspace owns its canonical settings, so it never stores a centralized safe base. */
    fun encodeRoomState(snapshot: LocalWorkspaceSnapshot): String = encode(snapshot, carrySafeBase = false)

    private fun encode(snapshot: LocalWorkspaceSnapshot, carrySafeBase: Boolean): String {
        // A deletion marker here means the workspace is mid-deletion. A background peer pass can
        // reach this between the marker write and the Iroh quarantine; refuse to stash it.
        require(snapshot.local.accountDeletionState == null) { "A workspace in account deletion cannot be stashed" }
        return IrohJson.strict.encodeToString(snapshot.toStored(carrySafeBase))
    }

    fun decode(value: String): LocalWorkspaceSnapshot {
        return IrohJson.strict.decodeFromString<StoredWorkspace>(value).toEntity()
    }
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
    // Absent only in states written before the codec carried the legacy dependency markers.
    // Every current encode writes an object, so absence is distinct from two null markers.
    val legacyDependency: StoredLegacyDependency? = null,
    // Present only in return states. Room states and states written before it decode as null.
    val safeBase: StoredSafeBase? = null,
)

@Serializable
private data class StoredSafeBase(
    val durationsJson: String? = null,
    val selectedTaskId: String? = null,
    val canonicalHeadWallMs: Long? = null,
    val canonicalHeadCounter: Long? = null,
)

@Serializable
private data class StoredLegacyDependency(
    val inputJson: String? = null,
    val resultJson: String? = null,
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
    val neverSent: Boolean = false,
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
    val neverSent: Boolean = false,
)

@Serializable
private data class StoredDurationOperation(
    val phase: String,
    val id: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val neverSent: Boolean = false,
)

@Serializable
private data class StoredAutoStartOperation(
    val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val neverSent: Boolean = false,
)

@Serializable
private data class StoredSelectedTaskOperation(
    val id: String,
    val taskId: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val neverSent: Boolean = false,
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

private fun LocalWorkspaceSnapshot.toStored(carrySafeBase: Boolean) = StoredWorkspace(
    local = local.run {
        StoredLocalState(
            id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
            canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
            tasksJson, knownTasksJson, selectedTaskId, canonicalAutoStartBreaks, ownedTimerId,
            serverClockOffsetMs, serverClockUncertaintyMs, serverClockSamplePhysicalMs,
            serverClockSampleElapsedRealtimeMs, serverClockBootId, lastUuidV7,
            StoredLegacyDependency(legacyDependencyInputJson, legacyDependencyResultJson),
            StoredSafeBase(
                safeBaseDurationsJson, safeBaseSelectedTaskId, safeCanonicalHeadWallMs, safeCanonicalHeadCounter,
            ).takeIf { carrySafeBase },
        )
    },
    commands = commands.map { value -> value.run {
        StoredCommand(
            id, deviceSequence, timerId, type, phase, plannedDurationMs, occurredAt,
            hlcWallMs, hlcCounter, observedElapsedMs, taskId, generatedByFinishCommandId,
            physicalOccurredAt, neverSent,
        )
    } },
    taskOperations = taskOperations.map { value -> value.run {
        StoredTaskOperation(id, taskId, type, title, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    durationOperations = durationOperations.map { value -> value.run {
        StoredDurationOperation(phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    autoStartOperations = autoStartOperations.map { value -> value.run {
        StoredAutoStartOperation(id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    selectedTaskOperations = selectedTaskOperations.map { value -> value.run {
        StoredSelectedTaskOperation(id, taskId, occurredAt, hlcWallMs, hlcCounter, neverSent)
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
        val markers = legacyDependency ?: unrecordedLegacyDependency(commands)
        LocalStateEntity(
            id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
            canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
            tasksJson, knownTasksJson, selectedTaskId, canonicalAutoStartBreaks, ownedTimerId,
            serverClockOffsetMs, serverClockUncertaintyMs, serverClockSamplePhysicalMs,
            serverClockSampleElapsedRealtimeMs, serverClockBootId, lastUuidV7,
            legacyDependencyInputJson = markers.inputJson,
            legacyDependencyResultJson = markers.resultJson,
            safeBaseDurationsJson = safeBase?.durationsJson,
            safeBaseSelectedTaskId = safeBase?.selectedTaskId,
            safeCanonicalHeadWallMs = safeBase?.canonicalHeadWallMs,
            safeCanonicalHeadCounter = safeBase?.canonicalHeadCounter,
        )
    },
    commands = commands.map { value -> value.run {
        PendingCommandEntity(
            id, deviceSequence, timerId, type, phase, plannedDurationMs, occurredAt,
            hlcWallMs, hlcCounter, observedElapsedMs, taskId, generatedByFinishCommandId,
            physicalOccurredAt, neverSent,
        )
    } },
    taskOperations = taskOperations.map { value -> value.run {
        PendingTaskOperationEntity(id, taskId, type, title, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    durationOperations = durationOperations.map { value -> value.run {
        PendingDurationOperationEntity(phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    autoStartOperations = autoStartOperations.map { value -> value.run {
        PendingAutoStartOperationEntity(id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    selectedTaskOperations = selectedTaskOperations.map { value -> value.run {
        PendingSelectedTaskOperationEntity(id, taskId, occurredAt, hlcWallMs, hlcCounter, neverSent)
    } },
    bootstrapResolution = bootstrapResolution?.run {
        PendingBootstrapResolutionEntity(
            id, requestId, deviceId, expectedRevision, strategy, commandsJson,
            taskOperationsJson, durationOperationsJson, ownerUserId, userJson,
            autoStartOperationsJson, selectedTaskOperationsJson,
        )
    },
)

/**
 * A state stashed before the markers existed was never seen by Migration 16 to 17, which marks
 * only the live `local_state` row. Mark it the same way the migration would have, so the next
 * startup asks Core about its retained dependency rows instead of trusting them as modern.
 */
private fun unrecordedLegacyDependency(commands: List<StoredCommand>) = StoredLegacyDependency(
    resultJson = LegacyDependencyAdoptionPending.takeIf {
        commands.any { it.generatedByFinishCommandId != null }
    },
)
