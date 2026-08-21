package me.egigoka.pomodorough.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

@Dao
interface TimerDao {
    @Query("SELECT * FROM local_state WHERE id = 0")
    suspend fun localState(): LocalStateEntity?

    @Query("SELECT * FROM pending_commands ORDER BY deviceSequence")
    suspend fun pendingCommands(): List<PendingCommandEntity>

    @Query("SELECT * FROM pending_task_operations ORDER BY hlcWallMs, hlcCounter, id")
    suspend fun pendingTaskOperations(): List<PendingTaskOperationEntity>

    @Query("SELECT * FROM pending_duration_operations ORDER BY hlcWallMs, hlcCounter, id")
    suspend fun pendingDurationOperations(): List<PendingDurationOperationEntity>

    @Query("SELECT * FROM pending_auto_start_operations ORDER BY hlcWallMs, hlcCounter, deviceId, id")
    suspend fun pendingAutoStartOperations(): List<PendingAutoStartOperationEntity>

    @Query("SELECT * FROM pending_selected_task_operations ORDER BY hlcWallMs, hlcCounter, id")
    suspend fun pendingSelectedTaskOperations(): List<PendingSelectedTaskOperationEntity>

    @Query("SELECT * FROM pending_bootstrap_resolution WHERE id = 0")
    suspend fun pendingBootstrapResolution(): PendingBootstrapResolutionEntity?

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

    @Query("SELECT * FROM replication_settings WHERE id = 0")
    suspend fun replicationSettings(): ReplicationSettingsEntity?

    @Query("SELECT * FROM iroh_rooms WHERE roomId = :roomId")
    suspend fun irohRoom(roomId: String): IrohRoomEntity?

    @Query("SELECT * FROM iroh_rooms WHERE activated = 1 ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun preferredIrohRoom(): IrohRoomEntity?

    @Query("SELECT * FROM iroh_peers WHERE roomId = :roomId ORDER BY endpointId")
    suspend fun irohPeers(roomId: String): List<IrohPeerEntity>

    @Query("SELECT COUNT(*) FROM iroh_peers WHERE roomId = :roomId")
    suspend fun irohPeerCount(roomId: String): Int

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

    @Query(
        "SELECT COUNT(*) FROM iroh_operations " +
            "WHERE roomId = :roomId AND domain = 'genesis' AND operationId = 'genesis'",
    )
    suspend fun hasIrohGenesis(roomId: String): Int

    @Query("SELECT * FROM iroh_conflicts WHERE roomId = :roomId")
    suspend fun irohConflict(roomId: String): IrohConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: LocalStateEntity)

    @Update
    suspend fun updateState(state: LocalStateEntity)

    @Insert
    suspend fun insertCommand(command: PendingCommandEntity)

    @Insert
    suspend fun insertCommands(commands: List<PendingCommandEntity>)

    @Insert
    suspend fun insertTaskOperations(operations: List<PendingTaskOperationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDurationOperations(operations: List<PendingDurationOperationEntity>)

    @Insert
    suspend fun insertAutoStartOperations(operations: List<PendingAutoStartOperationEntity>)

    @Update
    suspend fun updateCommands(commands: List<PendingCommandEntity>)

    @Update
    suspend fun updateTaskOperations(operations: List<PendingTaskOperationEntity>)

    @Update
    suspend fun updateDurationOperations(operations: List<PendingDurationOperationEntity>)

    @Update
    suspend fun updateAutoStartOperations(operations: List<PendingAutoStartOperationEntity>)

    @Update
    suspend fun updateSelectedTaskOperations(operations: List<PendingSelectedTaskOperationEntity>)

    @Insert
    suspend fun insertTaskOperation(operation: PendingTaskOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDurationOperation(operation: PendingDurationOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBootstrapResolution(resolution: PendingBootstrapResolutionEntity)

    @Insert
    suspend fun insertAutoStartOperation(operation: PendingAutoStartOperationEntity)

    @Insert
    suspend fun insertSelectedTaskOperation(operation: PendingSelectedTaskOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplicationSettings(settings: ReplicationSettingsEntity)

    @Insert
    suspend fun insertIrohRoom(room: IrohRoomEntity)

    @Update
    suspend fun updateIrohRoom(room: IrohRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIrohPeer(peer: IrohPeerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIrohOperations(operations: List<IrohOperationEntity>): List<Long>

    @Insert
    suspend fun insertNewIrohOperations(operations: List<IrohOperationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIrohConflict(conflict: IrohConflictEntity)

    @Query("DELETE FROM iroh_rooms WHERE roomId = :roomId")
    suspend fun deleteIrohRoom(roomId: String)

    @Query(
        "DELETE FROM iroh_rooms WHERE NOT EXISTS (" +
            "SELECT 1 FROM iroh_operations WHERE iroh_operations.roomId = iroh_rooms.roomId " +
            "AND domain = 'genesis' AND operationId = 'genesis')",
    )
    suspend fun deleteIncompleteIrohRooms()

    @Delete
    suspend fun deleteCommands(commands: List<PendingCommandEntity>)

    @Delete
    suspend fun deleteTaskOperations(operations: List<PendingTaskOperationEntity>)

    @Delete
    suspend fun deleteAutoStartOperations(operations: List<PendingAutoStartOperationEntity>)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAllCommands()

    @Query("DELETE FROM pending_task_operations")
    suspend fun deleteAllTaskOperations()

    @Query("DELETE FROM pending_duration_operations WHERE id IN (:operationIds)")
    suspend fun deleteDurationOperationsById(operationIds: List<String>)

    @Query("DELETE FROM pending_duration_operations")
    suspend fun deleteAllDurationOperations()

    @Query("DELETE FROM pending_auto_start_operations")
    suspend fun deleteAllAutoStartOperations()

    @Query("DELETE FROM pending_selected_task_operations")
    suspend fun deleteAllSelectedTaskOperations()

    @Delete
    suspend fun deleteSelectedTaskOperations(operations: List<PendingSelectedTaskOperationEntity>)

    @Query("DELETE FROM pending_bootstrap_resolution")
    suspend fun deleteBootstrapResolution()

    @Transaction
    suspend fun persistCommand(command: PendingCommandEntity, state: LocalStateEntity) {
        insertCommand(command)
        updateState(state)
    }

    @Transaction
    suspend fun persistCommands(commands: List<PendingCommandEntity>, state: LocalStateEntity) {
        insertCommands(commands)
        updateState(state)
    }

    @Transaction
    suspend fun persistTaskOperation(
        operation: PendingTaskOperationEntity,
        state: LocalStateEntity,
        selectedTaskOperation: PendingSelectedTaskOperationEntity? = null,
    ) {
        insertTaskOperation(operation)
        selectedTaskOperation?.let { insertSelectedTaskOperation(it) }
        updateState(state)
    }

    @Transaction
    suspend fun persistDurationOperation(
        operation: PendingDurationOperationEntity,
        state: LocalStateEntity,
    ) {
        upsertDurationOperation(operation)
        updateState(state)
    }

    @Transaction
    suspend fun persistAutoStartOperation(
        operation: PendingAutoStartOperationEntity,
        state: LocalStateEntity,
    ) {
        insertAutoStartOperation(operation)
        updateState(state)
    }

    @Transaction
    suspend fun persistSelectedTaskOperation(
        operation: PendingSelectedTaskOperationEntity,
        state: LocalStateEntity,
    ) {
        insertSelectedTaskOperation(operation)
        updateState(state)
    }

    @Transaction
    suspend fun updateMutationState(
        state: LocalStateEntity,
        commands: List<PendingCommandEntity>,
        taskOperations: List<PendingTaskOperationEntity>,
        durationOperations: List<PendingDurationOperationEntity>,
        autoStartOperations: List<PendingAutoStartOperationEntity>,
        selectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
    ) {
        if (commands.isNotEmpty()) updateCommands(commands)
        if (taskOperations.isNotEmpty()) updateTaskOperations(taskOperations)
        if (durationOperations.isNotEmpty()) updateDurationOperations(durationOperations)
        if (autoStartOperations.isNotEmpty()) updateAutoStartOperations(autoStartOperations)
        if (selectedTaskOperations.isNotEmpty()) updateSelectedTaskOperations(selectedTaskOperations)
        updateState(state)
    }

    @Transaction
    suspend fun persistBootstrapPreparation(
        state: LocalStateEntity,
        commands: List<PendingCommandEntity>,
        taskOperations: List<PendingTaskOperationEntity>,
        durationOperations: List<PendingDurationOperationEntity>,
        autoStartOperations: List<PendingAutoStartOperationEntity>,
        resolution: PendingBootstrapResolutionEntity,
        selectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
    ) {
        updateMutationState(
            state,
            commands,
            taskOperations,
            durationOperations,
            autoStartOperations,
            selectedTaskOperations,
        )
        upsertBootstrapResolution(resolution)
    }

    @Transaction
    suspend fun applySync(
        acknowledged: List<PendingCommandEntity>,
        state: LocalStateEntity,
    ) {
        if (acknowledged.isNotEmpty()) deleteCommands(acknowledged)
        updateState(state)
    }

    @Transaction
    suspend fun applyFullSync(
        acknowledgedCommands: List<PendingCommandEntity>,
        acknowledgedTaskOperations: List<PendingTaskOperationEntity>,
        acknowledgedDurationOperationIds: List<String>,
        state: LocalStateEntity,
        acknowledgedAutoStartOperations: List<PendingAutoStartOperationEntity> = emptyList(),
        updatedCommands: List<PendingCommandEntity> = emptyList(),
        updatedTaskOperations: List<PendingTaskOperationEntity> = emptyList(),
        updatedDurationOperations: List<PendingDurationOperationEntity> = emptyList(),
        updatedAutoStartOperations: List<PendingAutoStartOperationEntity> = emptyList(),
        discardedCommands: List<PendingCommandEntity> = emptyList(),
        acknowledgedSelectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
        updatedSelectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
    ) {
        if (acknowledgedCommands.isNotEmpty()) deleteCommands(acknowledgedCommands)
        if (acknowledgedTaskOperations.isNotEmpty()) deleteTaskOperations(acknowledgedTaskOperations)
        if (acknowledgedDurationOperationIds.isNotEmpty()) {
            deleteDurationOperationsById(acknowledgedDurationOperationIds)
        }
        if (acknowledgedAutoStartOperations.isNotEmpty()) {
            deleteAutoStartOperations(acknowledgedAutoStartOperations)
        }
        if (acknowledgedSelectedTaskOperations.isNotEmpty()) {
            deleteSelectedTaskOperations(acknowledgedSelectedTaskOperations)
        }
        if (discardedCommands.isNotEmpty()) deleteCommands(discardedCommands)
        if (updatedCommands.isNotEmpty()) updateCommands(updatedCommands)
        if (updatedTaskOperations.isNotEmpty()) updateTaskOperations(updatedTaskOperations)
        if (updatedDurationOperations.isNotEmpty()) updateDurationOperations(updatedDurationOperations)
        if (updatedAutoStartOperations.isNotEmpty()) {
            updateAutoStartOperations(updatedAutoStartOperations)
        }
        if (updatedSelectedTaskOperations.isNotEmpty()) {
            updateSelectedTaskOperations(updatedSelectedTaskOperations)
        }
        updateState(state)
    }

    @Transaction
    suspend fun clearAccount(state: LocalStateEntity) {
        deleteAllCommands()
        deleteAllTaskOperations()
        deleteAllDurationOperations()
        deleteAllAutoStartOperations()
        deleteAllSelectedTaskOperations()
        deleteBootstrapResolution()
        updateState(state)
    }

    @Transaction
    suspend fun applyBootstrapResolution(
        state: LocalStateEntity,
        clearAutoStartOperations: Boolean = true,
        retainedCommands: List<PendingCommandEntity> = emptyList(),
        retainedAutoStartOperations: List<PendingAutoStartOperationEntity> = emptyList(),
        clearSelectedTaskOperations: Boolean = true,
        retainedSelectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
    ) {
        deleteAllCommands()
        if (retainedCommands.isNotEmpty()) insertCommands(retainedCommands)
        deleteAllTaskOperations()
        deleteAllDurationOperations()
        if (clearAutoStartOperations) {
            deleteAllAutoStartOperations()
        } else if (retainedAutoStartOperations.isNotEmpty()) {
            updateAutoStartOperations(retainedAutoStartOperations)
        }
        if (clearSelectedTaskOperations) {
            deleteAllSelectedTaskOperations()
        } else if (retainedSelectedTaskOperations.isNotEmpty()) {
            updateSelectedTaskOperations(retainedSelectedTaskOperations)
        }
        deleteBootstrapResolution()
        updateState(state)
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
    suspend fun upsertIrohPeerBounded(peer: IrohPeerEntity, maximum: Int) {
        val exists = irohPeers(peer.roomId).any { it.endpointId == peer.endpointId }
        check(exists || irohPeerCount(peer.roomId) < maximum) {
            "Iroh room address book contains $maximum peers"
        }
        upsertIrohPeer(peer)
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

@Database(
    entities = [
        LocalStateEntity::class,
        PendingCommandEntity::class,
        PendingTaskOperationEntity::class,
        PendingDurationOperationEntity::class,
        PendingBootstrapResolutionEntity::class,
        PendingAutoStartOperationEntity::class,
        PendingSelectedTaskOperationEntity::class,
        ReplicationSettingsEntity::class,
        IrohRoomEntity::class,
        IrohPeerEntity::class,
        IrohOperationEntity::class,
        IrohConflictEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class PomodoroughDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao

    companion object {
        fun create(context: Context): PomodoroughDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PomodoroughDatabase::class.java,
                "pomodorough.db",
            ).addMigrations(
                Migration1To2,
                Migration2To3,
                Migration3To4,
                Migration4To5,
                Migration5To6,
                Migration6To7,
                Migration7To8,
                Migration8To9,
                Migration9To10,
                Migration10To11,
                Migration11To12,
            ).build()

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN ownerUserId TEXT")
            }
        }

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN tasksJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE local_state ADD COLUMN knownTasksJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE local_state ADD COLUMN selectedTaskId TEXT")
                db.execSQL("ALTER TABLE pending_commands ADD COLUMN taskId TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_task_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        title TEXT,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )
            }
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_duration_operations (
                        phase TEXT NOT NULL PRIMARY KEY,
                        id TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX IF NOT EXISTS index_pending_duration_operations_id
                        ON pending_duration_operations (id)""".trimIndent(),
                )

                db.query(
                    "SELECT settingsJson FROM local_state WHERE id = 0",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val settings = runCatching { JSONObject(cursor.getString(0)) }.getOrNull() ?: return
                    val customDurations = listOf(
                        Triple("focus", settings.optInt("focusMinutes", 25), 25),
                        Triple("short_break", settings.optInt("shortBreakMinutes", 5), 5),
                        Triple("long_break", settings.optInt("longBreakMinutes", 15), 15),
                    ).mapNotNull { (phase, minutes, defaultMinutes) ->
                        val bounded = minutes.coerceIn(1, 180)
                        if (bounded == defaultMinutes) null else phase to bounded * 60_000L
                    }
                    if (customDurations.isEmpty()) return

                    val occurredAt = Instant.EPOCH.toString()
                    customDurations.forEach { (phase, durationMs) ->
                        db.execSQL(
                            """INSERT INTO pending_duration_operations (
                                phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter
                            ) VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                            arrayOf<Any>(
                                phase,
                                "duration-operation-${UUID.randomUUID()}",
                                durationMs,
                                occurredAt,
                                0L,
                                0L,
                            ),
                        )
                    }
                }
            }
        }

        val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_bootstrap_resolution (
                        id INTEGER NOT NULL PRIMARY KEY,
                        requestId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        expectedRevision INTEGER NOT NULL,
                        strategy TEXT NOT NULL,
                        commandsJson TEXT NOT NULL,
                        taskOperationsJson TEXT NOT NULL,
                        durationOperationsJson TEXT NOT NULL,
                        ownerUserId TEXT NOT NULL,
                        userJson TEXT NOT NULL
                    )""".trimIndent(),
                )
            }
        }

        val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE local_state ADD COLUMN canonicalAutoStartBreaks INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE pending_bootstrap_resolution ADD COLUMN autoStartOperationsJson TEXT",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_auto_start_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        deviceId TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )

                db.query(
                    "SELECT deviceId, settingsJson FROM local_state WHERE id = 0",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val settings = runCatching { JSONObject(cursor.getString(1)) }.getOrNull() ?: return
                    if (!settings.optBoolean("autoStartBreaks", false)) return

                    db.execSQL(
                        """INSERT INTO pending_auto_start_operations (
                            id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter
                        ) VALUES (?, ?, 1, ?, ?, ?)""".trimIndent(),
                        arrayOf<Any>(
                            UUID.randomUUID().toString(),
                            cursor.getString(0),
                            Instant.EPOCH.toString(),
                            0L,
                            0L,
                        ),
                    )
                    db.execSQL(
                        "UPDATE local_state SET canonicalAutoStartBreaks = 1 WHERE id = 0",
                    )
                }
            }
        }

        val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN ownedTimerId TEXT")
                db.execSQL(
                    "ALTER TABLE pending_commands ADD COLUMN generatedByFinishCommandId TEXT",
                )
                // Version 6 lacks trigger-time auto-start state, so legacy commands stay independent.
                db.execSQL(
                    """UPDATE local_state
                        SET ownedTimerId = (
                            SELECT timerId FROM pending_commands
                            WHERE type = 'start'
                            ORDER BY deviceSequence DESC LIMIT 1
                        )
                        WHERE id = 0""".trimIndent(),
                )
            }
        }

        val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockOffsetMs INTEGER")
                db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockUncertaintyMs INTEGER")
                db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockSamplePhysicalMs INTEGER")
                db.execSQL(
                    "ALTER TABLE local_state ADD COLUMN serverClockSampleElapsedRealtimeMs INTEGER",
                )
                db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockBootId TEXT")
                db.execSQL("ALTER TABLE pending_commands ADD COLUMN physicalOccurredAt TEXT")
                db.execSQL("UPDATE pending_commands SET physicalOccurredAt = occurredAt")
            }
        }

        val Migration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN lastUuidV7 TEXT")
            }
        }

        val Migration9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """UPDATE pending_commands
                        SET deviceSequence = -9223372036854775807 + rowid
                        WHERE typeof(deviceSequence) != 'integer'
                            OR deviceSequence <= 0 OR deviceSequence IN (
                            SELECT deviceSequence
                            FROM pending_commands
                            GROUP BY deviceSequence
                            HAVING COUNT(*) > 1
                        )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX IF NOT EXISTS
                        index_pending_commands_deviceSequence
                        ON pending_commands(deviceSequence)""".trimIndent(),
                )
            }
        }


        val Migration10To11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS replication_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        mode TEXT NOT NULL,
                        activeRoomId TEXT
                    )""".trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO replication_settings (id, mode, activeRoomId) " +
                        "VALUES (0, 'CENTRALIZED', NULL)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS iroh_rooms (
                        roomId TEXT NOT NULL PRIMARY KEY,
                        roomName TEXT,
                        encryptedRoomSecret BLOB NOT NULL,
                        returnStateJson TEXT NOT NULL,
                        roomStateJson TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        activated INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS iroh_peers (
                        roomId TEXT NOT NULL,
                        endpointId TEXT NOT NULL,
                        endpointTicket TEXT NOT NULL,
                        deviceId TEXT,
                        displayName TEXT,
                        lastSeenAtMs INTEGER,
                        PRIMARY KEY(roomId, endpointId),
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX index_iroh_peers_roomId ON iroh_peers(roomId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS iroh_operations (
                        roomId TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        originDeviceId TEXT NOT NULL,
                        operationJson TEXT NOT NULL,
                        digest TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL,
                        deviceSequence INTEGER,
                        PRIMARY KEY(roomId, domain, operationId),
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX index_iroh_operations_roomId ON iroh_operations(roomId)")
                db.execSQL(
                    """CREATE UNIQUE INDEX index_iroh_operations_roomId_originDeviceId_deviceSequence
                        ON iroh_operations(roomId, originDeviceId, deviceSequence)""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS iroh_conflicts (
                        roomId TEXT NOT NULL PRIMARY KEY,
                        domain TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        localDigest TEXT NOT NULL,
                        receivedDigest TEXT NOT NULL,
                        detectedAtMs INTEGER NOT NULL,
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
            }
        }

        val Migration11To12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pending_bootstrap_resolution " +
                        "ADD COLUMN selectedTaskOperationsJson TEXT",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_selected_task_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )
            }
        }
    }
}
