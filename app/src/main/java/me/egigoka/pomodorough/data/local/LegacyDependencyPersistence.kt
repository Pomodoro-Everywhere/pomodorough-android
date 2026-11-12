package me.egigoka.pomodorough.data.local

import androidx.room.Transaction

/**
 * Stored in `legacyDependencyResultJson` (with a null input) by Migration 16 to 17 when the
 * upgraded workspace retained pre-R54 dependency rows. Null markers mean a modern workspace.
 */
internal const val LegacyDependencyAdoptionPending = "{\"adoption\":\"pendingLegacyQueue\"}"

/** Room's generated transaction wraps the raw read and compare-and-install together. */
interface LegacyDependencyPersistence : TimerWorkspaceDao, BootstrapDao {
    @Transaction
    suspend fun legacyDependencySnapshot(): LocalWorkspaceSnapshot = LocalWorkspaceSnapshot(
        local = checkNotNull(localState()),
        commands = loadCommandsBounded(),
        taskOperations = loadTaskOperationsBounded(),
        durationOperations = loadDurationOperationsBounded(),
        autoStartOperations = loadAutoStartOperationsBounded(),
        selectedTaskOperations = loadSelectedTaskOperationsBounded(),
        bootstrapResolution = pendingBootstrapResolution(),
    )

    /** Writes only the decision markers. Retained queue rows are never rewritten. */
    @Transaction
    suspend fun installLegacyDependencies(
        expected: LocalWorkspaceSnapshot,
        next: LocalStateEntity,
    ) {
        check(legacyDependencySnapshot() == expected) { "Legacy dependency workspace changed" }
        updateState(next)
    }
}
