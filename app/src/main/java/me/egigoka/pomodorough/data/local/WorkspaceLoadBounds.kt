package me.egigoka.pomodorough.data.local

internal object WorkspaceLoadBounds {
    const val MaxIrohOperations = 10_000
    const val MaxPendingCommands = 5_000
    const val MaxPendingTaskOperations = 5_000
    const val MaxPendingDurationOperations = 5_000
    const val MaxPendingAutoStartOperations = 5_000
    const val MaxPendingSelectedTaskOperations = 5_000
    const val MaxIrohRooms = 32
    const val MaxIrohPeers = 64

    fun <T> requireBounded(values: List<T>, limit: Int, name: String): List<T> {
        require(values.size <= limit) { "$name exceeds $limit" }
        return values
    }
}

internal suspend fun IrohRecordsDao.loadOperationsBounded(
    roomId: String,
    limit: Int = WorkspaceLoadBounds.MaxIrohOperations,
): List<IrohOperationEntity> {
    val loaded = irohOperationsCapped(roomId, limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "iroh_operations")
}

internal suspend fun IrohRoomMetadataDao.loadRoomsBounded(
    limit: Int = WorkspaceLoadBounds.MaxIrohRooms,
): List<IrohRoomEntity> {
    val loaded = irohRoomsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "iroh_rooms")
}

internal suspend fun IrohPeersDao.loadPeersBounded(
    roomId: String,
    limit: Int = WorkspaceLoadBounds.MaxIrohPeers,
): List<IrohPeerEntity> {
    val loaded = irohPeersCapped(roomId, limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "iroh_peers")
}

internal suspend fun TimerWorkspaceDao.loadCommandsBounded(
    limit: Int = WorkspaceLoadBounds.MaxPendingCommands,
): List<PendingCommandEntity> {
    val loaded = pendingCommandsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "pending_commands")
}

internal suspend fun TimerWorkspaceDao.loadTaskOperationsBounded(
    limit: Int = WorkspaceLoadBounds.MaxPendingTaskOperations,
): List<PendingTaskOperationEntity> {
    val loaded = pendingTaskOperationsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "pending_task_operations")
}

internal suspend fun TimerWorkspaceDao.loadDurationOperationsBounded(
    limit: Int = WorkspaceLoadBounds.MaxPendingDurationOperations,
): List<PendingDurationOperationEntity> {
    val loaded = pendingDurationOperationsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "pending_duration_operations")
}

internal suspend fun TimerWorkspaceDao.loadAutoStartOperationsBounded(
    limit: Int = WorkspaceLoadBounds.MaxPendingAutoStartOperations,
): List<PendingAutoStartOperationEntity> {
    val loaded = pendingAutoStartOperationsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "pending_auto_start_operations")
}

internal suspend fun TimerWorkspaceDao.loadSelectedTaskOperationsBounded(
    limit: Int = WorkspaceLoadBounds.MaxPendingSelectedTaskOperations,
): List<PendingSelectedTaskOperationEntity> {
    val loaded = pendingSelectedTaskOperationsCapped(limit + 1)
    return WorkspaceLoadBounds.requireBounded(loaded, limit, "pending_selected_task_operations")
}
