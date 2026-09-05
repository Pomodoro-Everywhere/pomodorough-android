package me.egigoka.pomodorough.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface TimerWorkspaceDao {
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

    @Query("SELECT COUNT(*) FROM pending_commands")
    suspend fun pendingCommandCount(): Int

    @Query("SELECT COUNT(*) FROM pending_task_operations")
    suspend fun pendingTaskOperationCount(): Int

    @Query("SELECT COUNT(*) FROM pending_duration_operations")
    suspend fun pendingDurationOperationCount(): Int

    @Query("SELECT COUNT(*) FROM pending_auto_start_operations")
    suspend fun pendingAutoStartOperationCount(): Int

    @Query("SELECT COUNT(*) FROM pending_selected_task_operations")
    suspend fun pendingSelectedTaskOperationCount(): Int

    @Query("SELECT * FROM pending_commands ORDER BY deviceSequence LIMIT :limit")
    suspend fun pendingCommandsCapped(limit: Int): List<PendingCommandEntity>

    @Query("SELECT * FROM pending_task_operations ORDER BY hlcWallMs, hlcCounter, id LIMIT :limit")
    suspend fun pendingTaskOperationsCapped(limit: Int): List<PendingTaskOperationEntity>

    @Query("SELECT * FROM pending_duration_operations ORDER BY hlcWallMs, hlcCounter, id LIMIT :limit")
    suspend fun pendingDurationOperationsCapped(limit: Int): List<PendingDurationOperationEntity>

    @Query(
        "SELECT * FROM pending_auto_start_operations " +
            "ORDER BY hlcWallMs, hlcCounter, deviceId, id LIMIT :limit",
    )
    suspend fun pendingAutoStartOperationsCapped(limit: Int): List<PendingAutoStartOperationEntity>

    @Query(
        "SELECT * FROM pending_selected_task_operations " +
            "ORDER BY hlcWallMs, hlcCounter, id LIMIT :limit",
    )
    suspend fun pendingSelectedTaskOperationsCapped(limit: Int): List<PendingSelectedTaskOperationEntity>

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

    @Insert
    suspend fun insertAutoStartOperation(operation: PendingAutoStartOperationEntity)

    @Insert
    suspend fun insertSelectedTaskOperation(operation: PendingSelectedTaskOperationEntity)

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
}
