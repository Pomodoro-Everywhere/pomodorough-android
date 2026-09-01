package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.flow.StateFlow
import me.egigoka.pomodorough.data.local.IrohRoomMetadataDao
import me.egigoka.pomodorough.data.local.TimerWorkspaceDao

interface IrohReplicationController {
    val state: StateFlow<IrohNetworkState>
    val mode: ReplicationMode
        get() = state.value.mode

    suspend fun initialize()
    suspend fun setMode(mode: ReplicationMode)
    suspend fun createRoom(name: String)
    suspend fun joinRoom(invite: String)
    suspend fun leaveRoom()
    suspend fun refreshInvite()
    suspend fun syncNow()
    suspend fun confirmIdentityRecovery() {
        throw UnsupportedOperationException("Iroh identity recovery is unavailable")
    }
    suspend fun afterLocalMutation()
    suspend fun quarantineAccount() {
        onBackground()
    }
    suspend fun releaseAccountQuarantine() {
        // Account quarantine is optional for lightweight replication adapters.
    }
    suspend fun clearAccountData()
    fun onForeground()
    fun onBackground() {
        // Lifecycle observation is optional for replication adapters.
    }
    suspend fun close() {
        // Terminal shutdown is optional for lightweight replication adapters.
    }
}

class IrohReplicationRepository(
    workspace: TimerWorkspaceDao,
    rooms: IrohRoomMetadataDao,
    private val store: IrohRoomStore,
    service: IrohReplicationService,
) : IrohReplicationController {
    internal val workspaceCoordinator
        get() = store.coordinator

    override val state: StateFlow<IrohNetworkState> = service.state

    private val orchestration = IrohRoomOrchestration(
        service = IrohRoomServicePort(
            state = service.state,
            publishState = service::replaceState,
            start = service::start,
            stop = service::stop,
            close = service::close,
            stopIf = service::stopIf,
            join = service::join,
            endpointIdForTicket = service::endpointIdForTicket,
            syncNow = service::syncNow,
            pendingIdentityRecovery = service::pendingIdentityRecovery,
            replaceEndpointIdentity = service::replaceEndpointIdentity,
            beginIdentityReset = service::beginIdentityReset,
            completeIdentityReset = service::completeIdentityReset,
        ),
        persistence = IrohRoomPersistencePort(
            localState = workspace::localState,
            room = rooms::irohRoom,
            replicationSettings = store::replicationSettings,
            discardIncompleteRooms = store::discardIncompleteRooms,
            setMode = { mode ->
                store.setMode(mode)
                Unit
            },
            snapshot = store::snapshot,
            captureLocalOperations = store::captureLocalOperations,
            createRoom = store::createRoom,
            activeRoom = store::activeRoom,
            activeRoomSecret = store::activeRoomSecret,
            prepareJoinedRoom = store::prepareJoinedRoom,
            discardIncompleteInactiveRoom = store::discardIncompleteInactiveRoom,
            leaveActiveRoom = {
                store.leaveActiveRoom()
                Unit
            },
            clearAccountData = store::clearAccountData,
            validateRoomSecrets = store::validateRoomSecrets,
            resetIdentityData = store::resetIdentityData,
        ),
    )

    override suspend fun initialize() = orchestration.initialize()

    override suspend fun setMode(mode: ReplicationMode) = orchestration.setMode(mode)

    override suspend fun createRoom(name: String) = orchestration.createRoom(name)

    override suspend fun joinRoom(invite: String) = orchestration.joinRoom(invite)

    override suspend fun leaveRoom() = orchestration.leaveRoom()

    override suspend fun refreshInvite() = orchestration.refreshInvite()

    override suspend fun syncNow() = orchestration.syncNow()

    override suspend fun confirmIdentityRecovery() = orchestration.confirmIdentityRecovery()

    override suspend fun afterLocalMutation() = orchestration.afterLocalMutation()

    override suspend fun quarantineAccount() = orchestration.quarantineAccount()

    override suspend fun releaseAccountQuarantine() = orchestration.releaseAccountQuarantine()

    override suspend fun clearAccountData() = orchestration.clearAccountData()

    override fun onForeground() = orchestration.onForeground()

    override suspend fun close() = orchestration.close()

    override fun onBackground() = orchestration.onBackground()
}
