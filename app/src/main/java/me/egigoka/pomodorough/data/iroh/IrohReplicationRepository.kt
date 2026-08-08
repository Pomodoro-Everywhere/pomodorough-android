package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.local.TimerDao

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
    suspend fun afterLocalMutation()
    suspend fun clearAccountData()
    fun onForeground()
    fun onBackground()
}

class IrohReplicationRepository(
    private val dao: TimerDao,
    private val store: IrohRoomStore,
    private val service: IrohReplicationService,
) : IrohReplicationController {
    internal val workspaceCoordinator
        get() = store.coordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var initialized = false
    @Volatile private var foreground = false
    @Volatile private var lifecycleGeneration = 0L
    private var invite: String? = null

    override val state: StateFlow<IrohNetworkState> = service.state

    init {
        scope.launch {
            service.state.collect { next ->
                if (invite != null && next.invite == null) {
                    serviceState(next.copy(invite = invite))
                }
            }
        }
    }

    override suspend fun initialize() = mutex.withLock {
        initializeLocked()
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        store.discardIncompleteRooms()
        val settings = store.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.CENTRALIZED)
        val room = settings.activeRoomId?.let { dao.irohRoom(it) }
        if (mode == ReplicationMode.IROH && room == null) {
            store.setMode(ReplicationMode.OFFLINE)
            serviceState(IrohNetworkState(mode = ReplicationMode.OFFLINE))
            initialized = true
            return
        }
        val snapshot = room?.let { store.snapshot(it.roomId) }
        serviceState((snapshot ?: IrohNetworkState()).copy(mode = mode))
        if (mode == ReplicationMode.IROH) {
            if (snapshot?.conflict == null) {
                runCatching { store.captureLocalOperations() }.onFailure { error ->
                    serviceState(state.value.copy(
                        status = IrohConnectionStatus.UNAVAILABLE,
                        message = error.message ?: "Saved Iroh operations could not be recovered",
                    ))
                    return
                }
            }
        }
        initialized = true
        if (mode == ReplicationMode.IROH && snapshot?.conflict == null && foreground) startIfNeeded()
    }

    override suspend fun setMode(mode: ReplicationMode) = mutex.withLock {
        initializeLocked()
        if (mode == state.value.mode) {
            if (mode == ReplicationMode.IROH && foreground) startIfNeeded()
            return
        }
        serviceState(state.value.copy(transitioning = true))
        try {
            if (state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            store.setMode(mode)
            invite = null
            val room = store.activeRoom()
            val snapshot = room?.let { store.snapshot(it.roomId) }
            serviceState((snapshot ?: IrohNetworkState()).copy(mode = mode, transitioning = false))
            if (mode == ReplicationMode.IROH && foreground) startIfNeeded()
        } catch (error: Exception) {
            recoverPersistedRoute(error, "Replication route could not be changed")
        }
    }

    override suspend fun createRoom(name: String) = mutex.withLock {
        initializeLocked()
        serviceState(state.value.copy(transitioning = true))
        var roomId: String? = null
        try {
            if (state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            val normalized = name.trim().ifEmpty { null }
            val (room, _) = store.createRoom(normalized)
            roomId = room.roomId
            serviceState(store.snapshot(room.roomId).copy(
                mode = ReplicationMode.IROH,
                status = IrohConnectionStatus.STARTING,
                transitioning = false,
            ))
            val ticket = startIfNeeded()
            val secret = checkNotNull(store.activeRoomSecret())
            invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
            secret.fill(0)
            serviceState(service.state.value.copy(invite = invite, message = null))
        } catch (error: Exception) {
            if (roomId == null) {
                recoverPersistedRoute(error, "Iroh room could not be created")
            } else {
                serviceState(service.state.value.copy(
                    mode = ReplicationMode.IROH,
                    status = IrohConnectionStatus.UNAVAILABLE,
                    message = error.message ?: "Iroh endpoint could not start",
                    transitioning = false,
                ))
            }
        }
    }

    override suspend fun joinRoom(invite: String) = mutex.withLock {
        initializeLocked()
        serviceState(state.value.copy(transitioning = true))
        var preparedRoomId: String? = null
        var createdRoom = false
        var decoded: IrohRoomInvite? = null
        try {
            decoded = IrohRoomInvite.decode(invite.trim())
            val endpointId = service.endpointIdForTicket(decoded.endpointTicket)
            if (state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            val prepared = store.prepareJoinedRoom(decoded, endpointId)
            preparedRoomId = decoded.roomId
            createdRoom = prepared.second
            serviceState(IrohNetworkState(
                mode = state.value.mode,
                status = IrohConnectionStatus.STARTING,
                roomId = decoded.roomId,
                roomName = decoded.roomName,
                transitioning = true,
            ))
            val local = checkNotNull(dao.localState())
            val owner = lifecycleGeneration
            startService(
                IrohServiceContext(decoded.roomId, decoded.roomSecret.copyOf(), local.deviceId, null),
                startPeriodicSync = false,
                owner = owner,
            )
            service.join(decoded)
            startService(
                IrohServiceContext(decoded.roomId, decoded.roomSecret.copyOf(), local.deviceId, null),
                owner = owner,
            )
            this.invite = null
            serviceState(store.snapshot(decoded.roomId).copy(
                mode = ReplicationMode.IROH,
                status = service.state.value.status,
                transitioning = false,
            ))
        } catch (error: Exception) {
            service.stop()
            if (createdRoom) {
                preparedRoomId?.let { roomId ->
                    runCatching { store.discardIncompleteInactiveRoom(roomId) }
                }
            }
            recoverPersistedRoute(error, "Iroh room could not be joined")
        } finally {
            decoded?.roomSecret?.fill(0)
        }
    }

    override suspend fun leaveRoom() = mutex.withLock {
        initializeLocked()
        if (state.value.mode != ReplicationMode.IROH) return
        serviceState(state.value.copy(transitioning = true))
        try {
            flushActiveIrohLocked()
            service.stop()
            store.leaveActiveRoom()
            invite = null
            serviceState(IrohNetworkState(mode = ReplicationMode.OFFLINE, transitioning = false))
        } catch (error: Exception) {
            recoverPersistedRoute(error, "Iroh room could not be left")
        }
    }

    override suspend fun refreshInvite() = mutex.withLock {
        initializeLocked()
        val room = checkNotNull(store.activeRoom()) { "No Iroh room is active" }
        val ticket = startIfNeeded()
        val secret = checkNotNull(store.activeRoomSecret())
        invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
        secret.fill(0)
        serviceState(service.state.value.copy(invite = invite, message = null))
    }

    override suspend fun syncNow() = mutex.withLock {
        initializeLocked()
        if (state.value.mode != ReplicationMode.IROH || !foreground) return
        startIfNeeded()
        service.syncNow()
    }

    override suspend fun afterLocalMutation() = mutex.withLock {
        initializeLocked()
        if (state.value.mode != ReplicationMode.IROH) return
        val projection = store.captureLocalOperations()
        serviceState(service.state.value.copy(operationCount = projection.operationCount))
        if (foreground) {
            runCatching { startIfNeeded() }
            service.syncNow()
        }
    }

    override suspend fun clearAccountData() = mutex.withLock {
        initializeLocked()
        if (state.value.mode != ReplicationMode.IROH) return
        val projection = store.captureLocalOperations()
        store.clearAccountData()
        serviceState(state.value.copy(operationCount = projection.operationCount))
    }

    override fun onForeground() {
        foreground = true
        lifecycleGeneration += 1L
        scope.launch {
            mutex.withLock {
                initializeLocked()
                if (state.value.mode == ReplicationMode.IROH) runCatching { startIfNeeded() }
            }
        }
    }

    override fun onBackground() {
        foreground = false
        lifecycleGeneration += 1L
        val owner = lifecycleGeneration
        scope.launch {
            service.stopIf { !foreground && owner == lifecycleGeneration }
        }
    }

    private suspend fun startIfNeeded(): String {
        val room = checkNotNull(store.activeRoom()) { "No Iroh room is active" }
        check(store.snapshot(room.roomId).conflict == null) { "Iroh room requires repair" }
        val secret = checkNotNull(store.activeRoomSecret()) { "Iroh room secret is unavailable" }
        return try {
            val local = checkNotNull(dao.localState())
            startService(
                IrohServiceContext(room.roomId, secret.copyOf(), local.deviceId, null),
                owner = lifecycleGeneration,
            )
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun startService(
        context: IrohServiceContext,
        startPeriodicSync: Boolean = true,
        owner: Long,
    ): String {
        if (!foreground || owner != lifecycleGeneration) {
            context.roomSecret.fill(0)
            throw CancellationException("Iroh endpoint requires foreground lifecycle")
        }
        val ticket = service.start(context, startPeriodicSync)
        if (!foreground || owner != lifecycleGeneration) {
            service.stop()
            throw CancellationException("Iroh endpoint stopped in background")
        }
        return ticket
    }

    private suspend fun flushActiveIrohLocked() {
        if (state.value.mode != ReplicationMode.IROH || state.value.conflict != null) return
        val projection = store.captureLocalOperations()
        serviceState(state.value.copy(operationCount = projection.operationCount))
    }

    private suspend fun recoverPersistedRoute(error: Exception, fallbackMessage: String) {
        val settings = store.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.OFFLINE)
        val snapshot = settings.activeRoomId?.let { roomId -> store.snapshot(roomId) }
        serviceState((snapshot ?: IrohNetworkState(mode = mode)).copy(
            mode = mode,
            status = IrohConnectionStatus.UNAVAILABLE,
            message = error.message ?: fallbackMessage,
            transitioning = false,
        ))
        if (mode == ReplicationMode.IROH && snapshot?.conflict == null && foreground) {
            runCatching { startIfNeeded() }
            serviceState(state.value.copy(
                message = error.message ?: fallbackMessage,
                transitioning = false,
            ))
        }
    }

    private fun serviceState(value: IrohNetworkState) {
        service.replaceState(value)
    }
}
