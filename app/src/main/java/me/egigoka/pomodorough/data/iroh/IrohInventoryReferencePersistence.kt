package me.egigoka.pomodorough.data.iroh

import me.egigoka.pomodorough.data.local.IrohConflictEntity
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohInventoryDao
import me.egigoka.pomodorough.data.local.IrohPeersDao
import me.egigoka.pomodorough.data.local.IrohRecordsDao
import me.egigoka.pomodorough.data.local.IrohRoomMetadataDao

internal class IrohInventoryReferencePersistence(
    private val inventory: IrohInventoryDao,
    private val rooms: IrohRoomMetadataDao,
    private val records: IrohRecordsDao,
    private val conflicts: IrohConflictsDao,
    private val peers: IrohPeersDao,
    private val metadata: IrohRoomMetadataPersistence,
    private val canonicalRecords: IrohCanonicalRecordPersistence,
) {
    suspend fun inventory(
        roomId: String,
        after: String?,
        limit: Int,
    ): Pair<List<IrohInventoryEntry>, String?> {
        require(limit in 1..IrohProtocolV1.MaxInventoryEntries)
        val (afterDomain, afterId) = after?.let(::parseCursor) ?: (null to null)
        val loaded = inventory.irohOperationPage(roomId, afterDomain, afterId, limit + 1)
        val entries = loaded.take(limit).map { operation ->
            IrohInventoryEntry(IrohDomain.valueOf(operation.domain), operation.operationId, operation.digest)
        }
        val next = entries.lastOrNull()?.takeIf { loaded.size > limit }?.let(::cursor)
        return entries to next
    }

    suspend fun operations(
        roomId: String,
        references: List<IrohInventoryReference>,
    ): List<IrohOperationRecord> {
        require(references.isNotEmpty() && references.size <= IrohProtocolV1.MaxOperationReferences &&
            references.toSet().size == references.size
        )
        return references.map { reference ->
            records.irohOperation(roomId, reference.domain.name, reference.id)?.toIrohRecord()
                ?: throw NoSuchElementException("Iroh operation was not found")
        }
    }

    suspend fun missingReferences(
        roomId: String,
        remote: List<IrohInventoryEntry>,
    ): List<IrohInventoryReference> {
        require(remote.size <= IrohProtocolV1.MaxInventoryEntries)
        return remote.mapNotNull { entry ->
            val stored = records.irohOperation(roomId, entry.domain.name, entry.id)
                ?: return@mapNotNull entry.reference
            if (stored.digest != entry.digest) {
                canonicalRecords.saveConflict(roomId, stored, entry.digest)
                throw IllegalStateException("Immutable Iroh operation conflict")
            }
            null
        }
    }

    suspend fun snapshot(roomId: String): IrohNetworkState {
        val settings = metadata.replicationSettings()
        val room = rooms.irohRoom(roomId)
        val conflict = conflicts.irohConflict(roomId)?.toIrohEvidence()
        return IrohNetworkState(
            mode = ReplicationMode.valueOf(settings.mode),
            status = if (conflict == null) IrohConnectionStatus.STOPPED else IrohConnectionStatus.CONFLICT,
            roomId = room?.roomId,
            roomName = room?.roomName,
            peerCount = peers.irohPeers(roomId).size,
            operationCount = records.irohOperations(roomId).size,
            conflict = conflict,
        )
    }

    private fun parseCursor(value: String): Pair<String, String> {
        val split = value.split('\u0000')
        require(split.size == 2)
        val domain = IrohDomain.valueOf(split[0])
        val id = split[1]
        require(if (domain == IrohDomain.genesis) id == "genesis" else IrohProtocolV1.isIdentifier(id))
        return domain.name to id
    }

    private fun cursor(entry: IrohInventoryEntry) = entry.domain.name + "\u0000" + entry.id
}

private fun IrohConflictEntity.toIrohEvidence() = IrohConflictEvidence(
    domain = IrohDomain.valueOf(domain),
    id = operationId,
    localDigest = localDigest,
    receivedDigest = receivedDigest,
    detectedAtMs = detectedAtMs,
)
