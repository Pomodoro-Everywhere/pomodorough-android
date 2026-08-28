package me.egigoka.pomodorough.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

interface BootstrapDao {
    @Query("SELECT * FROM pending_bootstrap_resolution WHERE id = 0")
    suspend fun pendingBootstrapResolution(): PendingBootstrapResolutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBootstrapResolution(resolution: PendingBootstrapResolutionEntity)

    @Query("DELETE FROM pending_bootstrap_resolution")
    suspend fun deleteBootstrapResolution()
}

@Dao
interface CentralizedSyncDao : TimerWorkspaceDao, BootstrapDao {
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
}
