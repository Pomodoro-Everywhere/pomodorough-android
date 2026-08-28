package me.egigoka.pomodorough.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReplicationSettingsDao {
    @Query("SELECT * FROM replication_settings WHERE id = 0")
    suspend fun replicationSettings(): ReplicationSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplicationSettings(settings: ReplicationSettingsEntity)
}

@Dao
interface IrohRoomMetadataDao {
    @Query("SELECT * FROM iroh_rooms ORDER BY roomId")
    suspend fun irohRooms(): List<IrohRoomEntity>

    @Query("SELECT * FROM iroh_rooms WHERE roomId = :roomId")
    suspend fun irohRoom(roomId: String): IrohRoomEntity?

    @Query("SELECT * FROM iroh_rooms WHERE activated = 1 ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun preferredIrohRoom(): IrohRoomEntity?

    @Insert
    suspend fun insertIrohRoom(room: IrohRoomEntity)

    @Update
    suspend fun updateIrohRoom(room: IrohRoomEntity)

    @Query("DELETE FROM iroh_rooms WHERE roomId = :roomId")
    suspend fun deleteIrohRoom(roomId: String)

    @Query(
        "DELETE FROM iroh_rooms WHERE NOT EXISTS (" +
            "SELECT 1 FROM iroh_operations WHERE iroh_operations.roomId = iroh_rooms.roomId " +
            "AND domain = 'genesis' AND operationId = 'genesis')",
    )
    suspend fun deleteIncompleteIrohRooms()
}

@Dao
interface IrohPeersDao {
    @Query("SELECT * FROM iroh_peers WHERE roomId = :roomId ORDER BY endpointId")
    suspend fun irohPeers(roomId: String): List<IrohPeerEntity>

    @Query("SELECT COUNT(*) FROM iroh_peers WHERE roomId = :roomId")
    suspend fun irohPeerCount(roomId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIrohPeer(peer: IrohPeerEntity)
}

@Dao
interface IrohRecordsDao {
    @Query(
        "SELECT * FROM iroh_operations WHERE roomId = :roomId " +
            "ORDER BY domain COLLATE BINARY, operationId COLLATE BINARY",
    )
    suspend fun irohOperations(roomId: String): List<IrohOperationEntity>

    @Query(
        "SELECT * FROM iroh_operations WHERE roomId = :roomId AND domain = :domain " +
            "AND operationId = :operationId",
    )
    suspend fun irohOperation(
        roomId: String,
        domain: String,
        operationId: String,
    ): IrohOperationEntity?

    @Query(
        "SELECT COUNT(*) FROM iroh_operations " +
            "WHERE roomId = :roomId AND domain = 'genesis' AND operationId = 'genesis'",
    )
    suspend fun hasIrohGenesis(roomId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIrohOperations(operations: List<IrohOperationEntity>): List<Long>

    @Insert
    suspend fun insertNewIrohOperations(operations: List<IrohOperationEntity>)
}

@Dao
interface IrohInventoryDao {
    @Query(
        "SELECT * FROM iroh_operations WHERE roomId = :roomId AND " +
            "(:afterDomain IS NULL OR domain > :afterDomain COLLATE BINARY OR " +
            "(domain = :afterDomain AND operationId > :afterId COLLATE BINARY)) " +
            "ORDER BY domain COLLATE BINARY, operationId COLLATE BINARY LIMIT :limit",
    )
    suspend fun irohOperationPage(
        roomId: String,
        afterDomain: String?,
        afterId: String?,
        limit: Int,
    ): List<IrohOperationEntity>
}

@Dao
interface IrohConflictsDao {
    @Query("SELECT * FROM iroh_conflicts WHERE roomId = :roomId")
    suspend fun irohConflict(roomId: String): IrohConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIrohConflict(conflict: IrohConflictEntity)
}
