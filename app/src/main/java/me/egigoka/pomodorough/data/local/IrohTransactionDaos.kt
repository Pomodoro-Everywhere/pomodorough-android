package me.egigoka.pomodorough.data.local

import androidx.room.Dao
import androidx.room.Transaction

interface WorkspaceSnapshotDao : TimerWorkspaceDao, BootstrapDao {
    @Transaction
    suspend fun localWorkspaceSnapshot(): LocalWorkspaceSnapshot {
        val local = checkNotNull(localState()) { "Local workspace is not initialized" }
        return LocalWorkspaceSnapshot(
            local = local,
            commands = pendingCommands(),
            taskOperations = pendingTaskOperations(),
            durationOperations = pendingDurationOperations(),
            autoStartOperations = pendingAutoStartOperations(),
            selectedTaskOperations = pendingSelectedTaskOperations(),
            bootstrapResolution = pendingBootstrapResolution(),
        )
    }

    @Transaction
    suspend fun replaceWorkspace(snapshot: LocalWorkspaceSnapshot) {
        insertState(snapshot.local)
        deleteAllCommands()
        if (snapshot.commands.isNotEmpty()) insertCommands(snapshot.commands)
        deleteAllTaskOperations()
        if (snapshot.taskOperations.isNotEmpty()) insertTaskOperations(snapshot.taskOperations)
        deleteAllDurationOperations()
        if (snapshot.durationOperations.isNotEmpty()) {
            upsertDurationOperations(snapshot.durationOperations)
        }
        deleteAllAutoStartOperations()
        if (snapshot.autoStartOperations.isNotEmpty()) {
            insertAutoStartOperations(snapshot.autoStartOperations)
        }
        deleteAllSelectedTaskOperations()
        snapshot.selectedTaskOperations.forEach { insertSelectedTaskOperation(it) }
        deleteBootstrapResolution()
        snapshot.bootstrapResolution?.let { upsertBootstrapResolution(it) }
    }
}

@Dao
interface IrohRoomTransactionsDao : IrohRoomMetadataDao, IrohPeersDao {
    @Transaction
    suspend fun prepareJoinedIrohRoom(
        room: IrohRoomEntity,
        peer: IrohPeerEntity,
    ) {
        insertIrohRoom(room)
        upsertIrohPeer(peer)
    }

    @Transaction
    suspend fun prepareExistingJoinedIrohRoom(
        room: IrohRoomEntity,
        peer: IrohPeerEntity,
    ) {
        updateIrohRoom(room)
        upsertIrohPeerBounded(peer, 64)
    }

    @Transaction
    suspend fun upsertIrohPeerBounded(peer: IrohPeerEntity, maximum: Int) {
        val exists = irohPeers(peer.roomId).any { it.endpointId == peer.endpointId }
        check(exists || irohPeerCount(peer.roomId) < maximum) {
            "Iroh room address book contains $maximum peers"
        }
        upsertIrohPeer(peer)
    }
}

@Dao
interface IrohWorkspaceTransactionsDao :
    WorkspaceSnapshotDao,
    ReplicationSettingsDao,
    IrohRoomMetadataDao,
    IrohRecordsDao {
    @Transaction
    suspend fun resetIrohIdentity(
        roomIds: List<String>,
        snapshot: LocalWorkspaceSnapshot,
        settings: ReplicationSettingsEntity,
    ) {
        roomIds.forEach { deleteIrohRoom(it) }
        upsertReplicationSettings(settings)
        replaceWorkspace(snapshot)
    }

    @Transaction
    suspend fun clearIrohAccountData(
        rooms: List<IrohRoomEntity>,
        malformedRoomIds: List<String>,
        snapshot: LocalWorkspaceSnapshot,
        settings: ReplicationSettingsEntity,
    ) {
        malformedRoomIds.forEach { deleteIrohRoom(it) }
        rooms.forEach { updateIrohRoom(it) }
        upsertReplicationSettings(settings)
        replaceWorkspace(snapshot)
    }

    @Transaction
    suspend fun createIrohRoom(
        room: IrohRoomEntity,
        genesis: IrohOperationEntity,
        settings: ReplicationSettingsEntity,
        snapshot: LocalWorkspaceSnapshot,
    ) {
        insertIrohRoom(room)
        check(insertIrohOperations(listOf(genesis)).single() != -1L)
        upsertReplicationSettings(settings)
        replaceWorkspace(snapshot)
    }

    @Transaction
    suspend fun insertIrohRecordsAtomically(
        room: IrohRoomEntity,
        operations: List<IrohOperationEntity>,
    ): List<Long> {
        val inserted = insertIrohOperations(operations)
        updateIrohRoom(room)
        return inserted
    }

    @Transaction
    suspend fun insertIrohRecordsAtomically(
        operations: List<IrohOperationEntity>,
    ) = insertNewIrohOperations(operations)

    @Transaction
    suspend fun insertIrohRecordsAndActivate(
        room: IrohRoomEntity,
        operations: List<IrohOperationEntity>,
        snapshot: LocalWorkspaceSnapshot,
    ): List<Long> {
        val inserted = insertIrohOperations(operations)
        updateIrohRoom(room)
        replaceWorkspace(snapshot)
        return inserted
    }

    @Transaction
    suspend fun insertRemoteIrohRecordsAndActivate(
        room: IrohRoomEntity,
        operations: List<IrohOperationEntity>,
        snapshot: LocalWorkspaceSnapshot,
    ): List<Long> {
        check(pendingCommands().isEmpty() && pendingTaskOperations().isEmpty() &&
            pendingDurationOperations().isEmpty() && pendingAutoStartOperations().isEmpty() &&
            pendingSelectedTaskOperations().isEmpty()
        ) { "Local Iroh operations must be captured before applying remote records" }
        return insertIrohRecordsAndActivate(room, operations, snapshot)
    }

    @Transaction
    suspend fun activateIrohWorkspace(
        room: IrohRoomEntity,
        settings: ReplicationSettingsEntity,
        snapshot: LocalWorkspaceSnapshot,
    ) {
        updateIrohRoom(room)
        upsertReplicationSettings(settings)
        replaceWorkspace(snapshot)
    }

    @Transaction
    suspend fun restoreFromIrohWorkspace(
        room: IrohRoomEntity,
        settings: ReplicationSettingsEntity,
        snapshot: LocalWorkspaceSnapshot,
    ) {
        activateIrohWorkspace(room, settings, snapshot)
    }

    @Transaction
    suspend fun captureIrohOperations(
        room: IrohRoomEntity,
        operations: List<IrohOperationEntity>,
        snapshot: LocalWorkspaceSnapshot,
    ) {
        insertNewIrohOperations(operations)
        updateIrohRoom(room)
        replaceWorkspace(snapshot)
    }
}

@Dao
interface IrohPersistenceDao :
    IrohRoomTransactionsDao,
    IrohWorkspaceTransactionsDao,
    IrohInventoryDao,
    IrohConflictsDao
