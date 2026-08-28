package me.egigoka.pomodorough.data.iroh

import android.database.sqlite.SQLiteConstraintException
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.local.IrohConflictEntity
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohWorkspaceTransactionsDao
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

internal class IrohCanonicalRecordPersistence(
    private val dao: IrohWorkspaceTransactionsDao,
    private val conflicts: IrohConflictsDao,
    private val metadata: IrohRoomMetadataPersistence,
    private val projection: IrohRoomProjectionPersistence,
    private val workspaceCoordinator: LocalWorkspaceCoordinator,
    private val currentTimeMillis: () -> Long,
) {
    suspend fun captureLocalOperations(): IrohRoomProjection {
        return workspaceCoordinator.withLock { captureLocalOperationsLocked() }
    }

    private suspend fun captureLocalOperationsLocked(): IrohRoomProjection {
        val room = checkNotNull(metadata.activeRoom()) { "No Iroh room is active" }
        require(conflicts.irohConflict(room.roomId) == null) { "Iroh room requires repair" }
        val current = dao.localWorkspaceSnapshot()
        val pendingCommands = projection.eligibleRoomCommands(current)
        val records = roomRecords(current, pendingCommands)
        val capturedIds = pendingCommands.mapTo(mutableSetOf()) { it.id }
        val cleared = current.copy(
            commands = current.commands.filterNot { it.id in capturedIds },
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
            bootstrapResolution = null,
        )
        val newEntities = newRoomEntities(room.roomId, records)
        val projected = projection.project(
            room.roomId, dao.irohOperations(room.roomId) + newEntities, cleared,
        )
        persistCapturedProjection(room, records.isNotEmpty(), newEntities, projected)
        return if (projection.eligibleRoomCommands(projected.snapshot).isNotEmpty()) {
            captureLocalOperationsLocked()
        } else {
            projected
        }
    }

    private fun roomRecords(
        snapshot: LocalWorkspaceSnapshot,
        commands: List<me.egigoka.pomodorough.data.local.PendingCommandEntity>,
    ): List<IrohOperationRecord> = buildList {
        val deviceId = snapshot.local.deviceId
        commands.forEach { add(IrohOperationRecord.timer(deviceId, it.toModel())) }
        snapshot.taskOperations.forEach { add(IrohOperationRecord.task(deviceId, it.toModel())) }
        snapshot.durationOperations.forEach { add(IrohOperationRecord.duration(deviceId, it.toModel())) }
        snapshot.autoStartOperations.forEach { add(IrohOperationRecord.autoStart(deviceId, it.toModel())) }
        snapshot.selectedTaskOperations.forEach {
            add(IrohOperationRecord.selectedTask(deviceId, it.toModel()))
        }
    }.onEach(IrohOperationRecord::validate)

    private suspend fun newRoomEntities(
        roomId: String,
        records: List<IrohOperationRecord>,
    ): List<IrohOperationEntity> = records.map { it.toIrohEntity(roomId) }.filter { candidate ->
        val stored = dao.irohOperation(roomId, candidate.domain, candidate.operationId)
            ?: return@filter true
        if (stored.digest != candidate.digest) {
            saveConflict(roomId, stored, candidate.digest)
            throw IllegalStateException("Immutable Iroh operation conflict")
        }
        false
    }

    private suspend fun persistCapturedProjection(
        room: IrohRoomEntity,
        hadRecords: Boolean,
        newEntities: List<IrohOperationEntity>,
        projected: IrohRoomProjection,
    ) {
        val updatedRoom = room.copy(roomStateJson = WorkspaceCodec.encode(projected.snapshot))
        if (hadRecords) {
            dao.captureIrohOperations(updatedRoom, newEntities, projected.snapshot)
        } else {
            val settings = ReplicationSettingsEntity(
                mode = ReplicationMode.IROH.name, activeRoomId = room.roomId,
            )
            dao.activateIrohWorkspace(updatedRoom, settings, projected.snapshot)
        }
    }

    suspend fun insertRemoteRecords(roomId: String, records: List<IrohOperationRecord>) =
        workspaceCoordinator.withLock {
            require(records.isNotEmpty() && records.size <= IrohProtocolV1.MaxOperationReferences) {
                "Iroh operation batch is invalid"
            }
            require(records.map { it.domain to it.id }.toSet().size == records.size) {
                "Iroh operation batch contains duplicate references"
            }
            records.forEach(IrohOperationRecord::validate)
            var room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
            require(conflicts.irohConflict(roomId) == null) { "Iroh room requires repair" }
            val existing = records.mapNotNull { record ->
                dao.irohOperation(roomId, record.domain.name, record.id)?.let { entity ->
                    (record.domain.name to record.id) to entity
                }
            }.toMap()
            for (record in records) {
                val stored = existing[record.domain.name to record.id] ?: continue
                if (stored.digest != record.digest()) {
                    saveConflict(roomId, stored, record.digest())
                    throw IllegalStateException("Immutable Iroh operation conflict")
                }
            }
            val newEntities = records.filter { (it.domain.name to it.id) !in existing }.map {
                it.toIrohEntity(roomId)
            }
            if (newEntities.isEmpty()) return@withLock
            val settings = metadata.replicationSettings()
            val active = settings.mode == ReplicationMode.IROH.name && settings.activeRoomId == roomId
            val base = if (active) captureLocalOperationsLocked().snapshot else null
            room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
            val projected = projection.project(
                roomId = roomId,
                records = dao.irohOperations(roomId) + newEntities,
                baseSnapshot = base,
            )
            val updated = room.copy(roomStateJson = WorkspaceCodec.encode(projected.snapshot))
            try {
                if (active) {
                    dao.insertRemoteIrohRecordsAndActivate(updated, newEntities, projected.snapshot)
                } else {
                    dao.insertIrohRecordsAtomically(updated, newEntities)
                }
            } catch (error: SQLiteConstraintException) {
                throw IllegalArgumentException("Iroh device sequence is reused", error)
            }
        }

    suspend fun refreshProjection(roomId: String): IrohRoomProjection =
        workspaceCoordinator.withLock {
            val settings = metadata.replicationSettings()
            val active = settings.mode == ReplicationMode.IROH.name && settings.activeRoomId == roomId
            if (active) return@withLock captureLocalOperationsLocked()
            val projected = projection.project(roomId)
            val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
            val updated = room.copy(roomStateJson = WorkspaceCodec.encode(projected.snapshot))
            dao.updateIrohRoom(updated)
            projected
        }

    suspend fun saveConflict(
        roomId: String,
        stored: IrohOperationEntity,
        receivedDigest: String,
    ) {
        conflicts.upsertIrohConflict(
            IrohConflictEntity(
                roomId = roomId,
                domain = stored.domain,
                operationId = stored.operationId,
                localDigest = stored.digest,
                receivedDigest = receivedDigest,
                detectedAtMs = currentTimeMillis(),
            ),
        )
    }
}

internal fun IrohOperationRecord.toIrohEntity(roomId: String) = IrohOperationEntity(
    roomId = roomId,
    domain = domain.name,
    operationId = id,
    originDeviceId = deviceId,
    operationJson = operation.toString(),
    digest = digest(),
    hlcWallMs = hlcWallMs,
    hlcCounter = hlcCounter,
    deviceSequence = deviceSequence,
)

internal fun IrohOperationEntity.toIrohRecord() = IrohOperationRecord(
    domain = IrohDomain.valueOf(domain),
    deviceId = originDeviceId,
    operation = IrohJson.strict.parseToJsonElement(operationJson).jsonObject,
).also { record ->
    require(record.id == operationId && record.digest() == digest) { "Saved Iroh operation is corrupted" }
}
