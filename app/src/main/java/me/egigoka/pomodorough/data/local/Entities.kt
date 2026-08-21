package me.egigoka.pomodorough.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TimerCommand

@Entity(tableName = "local_state")
data class LocalStateEntity(
    @PrimaryKey val id: Int = 0,
    val deviceId: String,
    val deviceSequence: Long = 0,
    val hlcWallMs: Long = 0,
    val hlcCounter: Long = 0,
    val revision: Long = 0,
    val canonicalTimerJson: String? = null,
    val historyJson: String = "[]",
    val settingsJson: String,
    val userJson: String? = null,
    val ownerUserId: String? = null,
    val tasksJson: String = "[]",
    val knownTasksJson: String = "[]",
    val selectedTaskId: String? = null,
    val canonicalAutoStartBreaks: Boolean = false,
    val ownedTimerId: String? = null,
    val serverClockOffsetMs: Long? = null,
    val serverClockUncertaintyMs: Long? = null,
    val serverClockSamplePhysicalMs: Long? = null,
    val serverClockSampleElapsedRealtimeMs: Long? = null,
    val serverClockBootId: String? = null,
    val lastUuidV7: String? = null,
)

@Entity(tableName = "pending_bootstrap_resolution")
data class PendingBootstrapResolutionEntity(
    @PrimaryKey val id: Int = 0,
    val requestId: String,
    val deviceId: String,
    val expectedRevision: Long,
    val strategy: String,
    val commandsJson: String,
    val taskOperationsJson: String,
    val durationOperationsJson: String,
    val ownerUserId: String,
    val userJson: String,
    val autoStartOperationsJson: String? = null,
    val selectedTaskOperationsJson: String? = null,
)

@Entity(
    tableName = "pending_commands",
    indices = [Index(value = ["deviceSequence"], unique = true)],
)
data class PendingCommandEntity(
    @PrimaryKey val id: String,
    val deviceSequence: Long,
    val timerId: String,
    val type: String,
    val phase: String,
    val plannedDurationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val observedElapsedMs: Long,
    val taskId: String? = null,
    val generatedByFinishCommandId: String? = null,
    val physicalOccurredAt: String? = null,
) {
    fun toModel() = TimerCommand(
        id = id,
        deviceSequence = deviceSequence,
        timerId = timerId,
        type = type,
        phase = phase,
        plannedDurationMs = plannedDurationMs,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
        observedElapsedMs = observedElapsedMs,
        taskId = taskId,
        physicalOccurredAt = physicalOccurredAt,
    )

    companion object {
        fun from(
            command: TimerCommand,
            generatedByFinishCommandId: String? = null,
        ) = PendingCommandEntity(
            id = command.id,
            deviceSequence = command.deviceSequence,
            timerId = command.timerId,
            type = command.type,
            phase = command.phase,
            plannedDurationMs = command.plannedDurationMs,
            occurredAt = command.occurredAt,
            hlcWallMs = command.hlcWallMs,
            hlcCounter = command.hlcCounter,
            observedElapsedMs = command.observedElapsedMs,
            taskId = command.taskId,
            generatedByFinishCommandId = generatedByFinishCommandId,
            physicalOccurredAt = command.physicalOccurredAt,
        )
    }
}

@Entity(tableName = "pending_task_operations")
data class PendingTaskOperationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String,
    val title: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = TaskOperation(
        id = id,
        taskId = taskId,
        type = type,
        title = title,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: TaskOperation) = PendingTaskOperationEntity(
            id = operation.id,
            taskId = operation.taskId,
            type = operation.type,
            title = operation.title,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}

@Entity(
    tableName = "pending_duration_operations",
    indices = [Index(value = ["id"], unique = true)],
)
data class PendingDurationOperationEntity(
    @PrimaryKey val phase: String,
    val id: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = DurationOperation(
        id = id,
        phase = phase,
        durationMs = durationMs,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: DurationOperation) = PendingDurationOperationEntity(
            phase = operation.phase,
            id = operation.id,
            durationMs = operation.durationMs,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}

@Entity(tableName = "pending_auto_start_operations")
data class PendingAutoStartOperationEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = AutoStartOperation(
        id = id,
        deviceId = deviceId,
        enabled = enabled,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: AutoStartOperation) = PendingAutoStartOperationEntity(
            id = operation.id,
            deviceId = operation.deviceId,
            enabled = operation.enabled,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}

@Entity(tableName = "pending_selected_task_operations")
data class PendingSelectedTaskOperationEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = SelectedTaskOperation(id, taskId, occurredAt, hlcWallMs, hlcCounter)

    companion object {
        fun from(operation: SelectedTaskOperation) = PendingSelectedTaskOperationEntity(
            operation.id,
            operation.taskId,
            operation.occurredAt,
            operation.hlcWallMs,
            operation.hlcCounter,
        )
    }
}

@Entity(tableName = "replication_settings")
data class ReplicationSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val mode: String,
    val activeRoomId: String? = null,
)

@Entity(tableName = "iroh_rooms")
data class IrohRoomEntity(
    @PrimaryKey val roomId: String,
    val roomName: String?,
    val encryptedRoomSecret: ByteArray,
    val returnStateJson: String,
    val roomStateJson: String,
    val createdAtMs: Long,
    val activated: Boolean = false,
)

@Entity(
    tableName = "iroh_peers",
    primaryKeys = ["roomId", "endpointId"],
    indices = [Index(value = ["roomId"])],
    foreignKeys = [
        ForeignKey(
            entity = IrohRoomEntity::class,
            parentColumns = ["roomId"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IrohPeerEntity(
    val roomId: String,
    val endpointId: String,
    val endpointTicket: String,
    val deviceId: String?,
    val displayName: String?,
    val lastSeenAtMs: Long?,
)

@Entity(
    tableName = "iroh_operations",
    primaryKeys = ["roomId", "domain", "operationId"],
    indices = [
        Index(value = ["roomId"]),
        Index(
            value = ["roomId", "originDeviceId", "deviceSequence"],
            unique = true,
        ),
    ],
    foreignKeys = [
        ForeignKey(
            entity = IrohRoomEntity::class,
            parentColumns = ["roomId"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IrohOperationEntity(
    val roomId: String,
    val domain: String,
    val operationId: String,
    val originDeviceId: String,
    val operationJson: String,
    val digest: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val deviceSequence: Long?,
)

@Entity(
    tableName = "iroh_conflicts",
    foreignKeys = [
        ForeignKey(
            entity = IrohRoomEntity::class,
            parentColumns = ["roomId"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IrohConflictEntity(
    @PrimaryKey val roomId: String,
    val domain: String,
    val operationId: String,
    val localDigest: String,
    val receivedDigest: String,
    val detectedAtMs: Long,
)
