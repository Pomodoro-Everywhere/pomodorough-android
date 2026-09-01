package me.egigoka.pomodorough.data.iroh

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

internal data class IrohRoomServicePort(
    val state: StateFlow<IrohNetworkState>,
    val publishState: (IrohNetworkState) -> Unit,
    val start: suspend (IrohServiceContext, Boolean) -> String,
    val stop: suspend () -> Unit,
    val close: suspend () -> Unit,
    val stopIf: suspend (() -> Boolean) -> Unit,
    val join: suspend (IrohRoomInvite) -> IrohRoomProjection,
    val endpointIdForTicket: (String) -> String,
    val syncNow: suspend () -> Unit,
    val pendingIdentityRecovery: () -> IrohIdentityRecoveryKind? = { null },
    val replaceEndpointIdentity: suspend () -> Unit = ::unsupportedRecoveryAction,
    val beginIdentityReset: suspend () -> Unit = ::unsupportedRecoveryAction,
    val completeIdentityReset: suspend () -> Unit = ::unsupportedRecoveryAction,
)

internal data class IrohRoomPersistencePort(
    val localState: suspend () -> LocalStateEntity?,
    val room: suspend (String) -> IrohRoomEntity?,
    val replicationSettings: suspend () -> ReplicationSettingsEntity,
    val discardIncompleteRooms: suspend () -> Unit,
    val setMode: suspend (ReplicationMode) -> Unit,
    val snapshot: suspend (String) -> IrohNetworkState,
    val captureLocalOperations: suspend () -> IrohRoomProjection,
    val createRoom: suspend (String?) -> Pair<IrohRoomEntity, IrohRoomProjection>,
    val activeRoom: suspend () -> IrohRoomEntity?,
    val activeRoomSecret: suspend () -> ByteArray?,
    val prepareJoinedRoom: suspend (IrohRoomInvite, String) -> Pair<IrohRoomEntity, Boolean>,
    val discardIncompleteInactiveRoom: suspend (String) -> Unit,
    val leaveActiveRoom: suspend () -> Unit,
    val clearAccountData: suspend () -> Unit,
    val validateRoomSecrets: suspend () -> Unit = ::unsupportedRecoveryAction,
    val resetIdentityData: suspend () -> Unit = ::unsupportedRecoveryAction,
)

internal class IrohRoomOrchestration(
    private val service: IrohRoomServicePort,
    private val persistence: IrohRoomPersistencePort,
) {
    private val orchestrationJob: Job = SupervisorJob()
    private val scope = CoroutineScope(orchestrationJob + Dispatchers.IO)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val accountQuarantined = AtomicBoolean(false)
    private val recoveryRequested = AtomicBoolean(false)
    private val recoveryGeneration = AtomicLong()
    private val lifecycle = IrohLifecycleState()
    private var initialized = false
    private var invite: String? = null

    private data class JoinPreparation(
        val roomId: String,
        val createdRoom: Boolean,
    )

    init {
        scope.launch {
            service.state.collect { next ->
                if (invite != null && next.invite == null) {
                    publish(next.copy(invite = invite))
                }
            }
        }
    }

    suspend fun initialize() = mutex.withLock { initializeLocked() }

    suspend fun setMode(mode: ReplicationMode) = runIrohAction {
        if (mode == service.state.value.mode) {
            if (mode == ReplicationMode.IROH && isForeground()) startIfNeeded()
            return@runIrohAction
        }
        publish(service.state.value.copy(transitioning = true))
        try {
            if (service.state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            persistence.setMode(mode)
            invite = null
            val room = persistence.activeRoom()
            val snapshot = room?.let { persistence.snapshot(it.roomId) }
            publish((snapshot ?: IrohNetworkState()).copy(mode = mode, transitioning = false))
            if (mode == ReplicationMode.IROH && isForeground()) startIfNeeded()
        } catch (error: Exception) {
            recoverPersistedRoute(error, "Replication route could not be changed")
        }
    }

    suspend fun createRoom(name: String) = runIrohAction {
        publish(service.state.value.copy(transitioning = true))
        var roomId: String? = null
        try {
            if (service.state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            val normalized = name.trim().ifEmpty { null }
            val (room, _) = persistence.createRoom(normalized)
            roomId = room.roomId
            publish(persistence.snapshot(room.roomId).copy(
                mode = ReplicationMode.IROH,
                status = IrohConnectionStatus.STARTING,
                transitioning = false,
            ))
            val ticket = startIfNeeded()
            val secret = checkNotNull(persistence.activeRoomSecret())
            invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
            secret.fill(0)
            publish(service.state.value.copy(invite = invite, message = null))
        } catch (error: Exception) {
            handleCreateFailure(roomId, error)
        }
    }

    suspend fun joinRoom(encodedInvite: String) = runIrohAction {
        publish(service.state.value.copy(transitioning = true))
        var decoded: IrohRoomInvite? = null
        var preparation: JoinPreparation? = null
        try {
            decoded = IrohRoomInvite.decode(encodedInvite.trim())
            preparation = prepareJoinedRoom(decoded)
            connectJoinedRoom(decoded)
            completeJoinedRoom(decoded.roomId)
        } catch (error: Exception) {
            rollbackJoinedRoom(preparation)
            recoverPersistedRoute(error, "Iroh room could not be joined")
        } finally {
            decoded?.roomSecret?.fill(0)
        }
    }

    suspend fun leaveRoom() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH) return@runIrohAction
        publish(service.state.value.copy(transitioning = true))
        try {
            flushActiveIrohLocked()
            service.stop()
            persistence.leaveActiveRoom()
            invite = null
            publish(IrohNetworkState(mode = ReplicationMode.OFFLINE, transitioning = false))
        } catch (error: Exception) {
            recoverPersistedRoute(error, "Iroh room could not be left")
        }
    }

    suspend fun refreshInvite() = runIrohAction {
        val room = checkNotNull(persistence.activeRoom()) { "No Iroh room is active" }
        val ticket = startIfNeeded()
        val secret = checkNotNull(persistence.activeRoomSecret())
        invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
        secret.fill(0)
        publish(service.state.value.copy(invite = invite, message = null))
    }

    suspend fun syncNow() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH || !isForeground()) {
            return@runIrohAction
        }
        startIfNeeded()
        service.syncNow()
    }

    suspend fun afterLocalMutation() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH) return@runIrohAction
        val projection = persistence.captureLocalOperations()
        publish(service.state.value.copy(operationCount = projection.operationCount))
        if (isForeground()) {
            if (startUnlessIdentityRecoveryRequired()) service.syncNow()
        }
    }

    suspend fun confirmIdentityRecovery() {
        if (!recoveryRequested.compareAndSet(false, true)) return
        recoveryGeneration.incrementAndGet()
        var restart = false
        try {
            mutex.withLock { restart = recoverIdentityLocked() }
        } finally {
            recoveryRequested.set(false)
        }
        if (restart) onForeground()
    }

    suspend fun clearAccountData() = mutex.withLock {
        persistence.clearAccountData()
        publish(
            service.state.value.copy(
                mode = ReplicationMode.OFFLINE,
                operationCount = 0,
                invite = null,
            ),
        )
        initialized = true
    }

    suspend fun quarantineAccount() {
        accountQuarantined.set(true)
        lifecycle.enterBackground()
        mutex.withLock {
            service.stopIf { true }
        }
    }

    fun releaseAccountQuarantine() {
        accountQuarantined.set(false)
    }

    fun onForeground() {
        if (closed.get() || accountQuarantined.get()) return
        lifecycle.enterForeground()
        scope.launch {
            runIrohAction {
                if (service.state.value.mode == ReplicationMode.IROH) {
                    startUnlessIdentityRecoveryRequired()
                }
            }
        }
    }

    fun onBackground() {
        if (closed.get()) return
        val event = lifecycle.enterBackground()
        scope.launch {
            service.stopIf { lifecycle.permitsBackgroundStop(event.snapshot.generation) }
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycle.enterBackground()
        orchestrationJob.cancelAndJoin()
        service.close()
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        val pendingRecovery = service.pendingIdentityRecovery()
        if (pendingRecovery == null) persistence.discardIncompleteRooms()
        val settings = persistence.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.CENTRALIZED)
        val room = settings.activeRoomId?.let { persistence.room(it) }
        val snapshot = room?.let { persistence.snapshot(it.roomId) }
        pendingRecovery?.let { kind ->
            publish((snapshot ?: IrohNetworkState()).copy(mode = mode))
            quarantineRecovery(kind)
            initialized = true
            return
        }
        if (mode == ReplicationMode.IROH && room == null) {
            persistence.setMode(ReplicationMode.OFFLINE)
            publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
            initialized = true
            return
        }
        publish((snapshot ?: IrohNetworkState()).copy(mode = mode))
        if (!recoverLocalOperations(mode, snapshot)) return
        initialized = true
        if (mode == ReplicationMode.IROH && snapshot?.conflict == null && isForeground()) {
            try {
                startIfNeeded()
            } catch (error: IrohSecretVaultException) {
                quarantineRecovery(error.recoveryKind)
            }
        }
    }

    private suspend fun recoverLocalOperations(
        mode: ReplicationMode,
        snapshot: IrohNetworkState?,
    ): Boolean {
        if (mode != ReplicationMode.IROH || snapshot?.conflict != null) return true
        return runCatching { persistence.captureLocalOperations() }.fold(
            onSuccess = { true },
            onFailure = { error ->
                publish(service.state.value.copy(
                    status = IrohConnectionStatus.UNAVAILABLE,
                    message = error.message ?: "Saved Iroh operations could not be recovered",
                ))
                false
            },
        )
    }

    private suspend fun handleCreateFailure(roomId: String?, error: Exception) {
        if (error is IrohSecretVaultException) {
            quarantineRecovery(error.recoveryKind)
            return
        }
        if (roomId == null) {
            recoverPersistedRoute(error, "Iroh room could not be created")
        } else {
            publish(service.state.value.copy(
                mode = ReplicationMode.IROH,
                status = IrohConnectionStatus.UNAVAILABLE,
                message = error.message ?: "Iroh endpoint could not start",
                transitioning = false,
            ))
        }
    }

    private suspend fun prepareJoinedRoom(invite: IrohRoomInvite): JoinPreparation {
        val endpointId = service.endpointIdForTicket(invite.endpointTicket)
        if (service.state.value.mode == ReplicationMode.IROH) {
            flushActiveIrohLocked()
            service.stop()
        }
        val createdRoom = persistence.prepareJoinedRoom(invite, endpointId).second
        publish(IrohNetworkState(
            mode = service.state.value.mode,
            status = IrohConnectionStatus.STARTING,
            roomId = invite.roomId,
            roomName = invite.roomName,
            transitioning = true,
        ))
        return JoinPreparation(invite.roomId, createdRoom)
    }

    private suspend fun connectJoinedRoom(invite: IrohRoomInvite) {
        val local = checkNotNull(persistence.localState())
        val owner = lifecycle.snapshot().generation
        startService(
            IrohServiceContext(invite.roomId, invite.roomSecret.copyOf(), local.deviceId, null),
            startPeriodicSync = false,
            owner = owner,
        )
        service.join(invite)
        startService(
            IrohServiceContext(invite.roomId, invite.roomSecret.copyOf(), local.deviceId, null),
            owner = owner,
        )
    }

    private suspend fun completeJoinedRoom(roomId: String) {
        invite = null
        publish(persistence.snapshot(roomId).copy(
            mode = ReplicationMode.IROH,
            status = service.state.value.status,
            transitioning = false,
        ))
    }

    private suspend fun rollbackJoinedRoom(preparation: JoinPreparation?) {
        service.stop()
        if (preparation?.createdRoom == true) {
            runCatching { persistence.discardIncompleteInactiveRoom(preparation.roomId) }
        }
    }

    private suspend fun startIfNeeded(): String {
        val room = checkNotNull(persistence.activeRoom()) { "No Iroh room is active" }
        check(persistence.snapshot(room.roomId).conflict == null) { "Iroh room requires repair" }
        val secret = checkNotNull(persistence.activeRoomSecret()) {
            "Iroh room secret is unavailable"
        }
        return try {
            val local = checkNotNull(persistence.localState())
            startService(
                IrohServiceContext(room.roomId, secret.copyOf(), local.deviceId, null),
                owner = lifecycle.snapshot().generation,
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
        if (!permitsEndpoint(owner)) {
            context.roomSecret.fill(0)
            throw CancellationException("Iroh endpoint requires active account foreground")
        }
        val ticket = service.start(context, startPeriodicSync)
        if (!permitsEndpoint(owner)) {
            service.stop()
            throw CancellationException("Iroh endpoint stopped by account or lifecycle quarantine")
        }
        return ticket
    }

    private suspend fun flushActiveIrohLocked() {
        if (service.state.value.mode != ReplicationMode.IROH ||
            service.state.value.conflict != null
        ) return
        val projection = persistence.captureLocalOperations()
        publish(service.state.value.copy(operationCount = projection.operationCount))
    }

    private suspend fun recoverPersistedRoute(error: Exception, fallbackMessage: String) {
        if (error is IrohSecretVaultException) {
            quarantineRecovery(error.recoveryKind)
            return
        }
        val settings = persistence.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.OFFLINE)
        val snapshot = settings.activeRoomId?.let { roomId -> persistence.snapshot(roomId) }
        publish((snapshot ?: IrohNetworkState(mode = mode)).copy(
            mode = mode,
            status = IrohConnectionStatus.UNAVAILABLE,
            message = error.message ?: fallbackMessage,
            transitioning = false,
        ))
        if (mode == ReplicationMode.IROH && snapshot?.conflict == null && isForeground()) {
            if (!startUnlessIdentityRecoveryRequired()) return
            publish(service.state.value.copy(
                message = error.message ?: fallbackMessage,
                transitioning = false,
            ))
        }
    }

    private suspend fun startUnlessIdentityRecoveryRequired(): Boolean = try {
        startIfNeeded()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: IrohSecretVaultException) {
        quarantineRecovery(error.recoveryKind)
        false
    } catch (error: Exception) {
        publish(service.state.value.copy(
            status = IrohConnectionStatus.UNAVAILABLE,
            message = error.message ?: "Iroh endpoint could not start",
            transitioning = false,
        ))
        true
    }

    private fun permitsEndpoint(owner: Long): Boolean =
        !accountQuarantined.get() &&
            !recoveryRequested.get() &&
            service.state.value.identityRecovery == null &&
            lifecycle.permitsEndpoint(owner)

    private fun isForeground(): Boolean =
        !accountQuarantined.get() && lifecycle.snapshot().foreground

    private fun publish(value: IrohNetworkState) {
        if (!closed.get()) service.publishState(value)
    }

    private suspend fun runIrohAction(action: suspend () -> Unit) {
        val generation = recoveryGeneration.get()
        if (!permitsIrohAction(generation)) return
        mutex.withLock {
            if (!permitsIrohAction(generation)) return@withLock
            initializeLocked()
            if (!permitsIrohAction(generation)) return@withLock
            action()
        }
    }

    private fun permitsIrohAction(generation: Long): Boolean =
        !closed.get() &&
            !accountQuarantined.get() &&
            !recoveryRequested.get() &&
            recoveryGeneration.get() == generation &&
            service.state.value.identityRecovery == null

    private suspend fun recoverIdentityLocked(): Boolean {
        val kind = service.state.value.identityRecovery ?: return false
        publishRecovery(kind, failed = false, transitioning = true)
        return try {
            when (kind) {
                IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> {
                    repairEndpointIdentity()
                    service.state.value.mode == ReplicationMode.IROH && isForeground()
                }
                IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> {
                    resetIrohIdentity()
                    false
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IrohSecretVaultException) {
            quarantineRecovery(error.recoveryKind, failed = true)
            false
        } catch (_: Exception) {
            publishRecovery(kind, failed = true)
            false
        }
    }

    private suspend fun repairEndpointIdentity() {
        service.stop()
        persistence.validateRoomSecrets()
        service.replaceEndpointIdentity()
        invite = null
        publish(service.state.value.copy(
            status = IrohConnectionStatus.STOPPED,
            invite = null,
            message = null,
            transitioning = false,
            identityRecovery = null,
            recoveryAttemptFailed = false,
        ))
    }

    private suspend fun resetIrohIdentity() {
        service.stop()
        service.beginIdentityReset()
        persistence.resetIdentityData()
        service.completeIdentityReset()
        invite = null
        publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
    }

    private suspend fun quarantineRecovery(
        kind: IrohIdentityRecoveryKind,
        failed: Boolean = false,
    ) {
        try {
            service.stop()
        } finally {
            invite = null
            publishRecovery(kind, failed)
        }
    }

    private fun publishRecovery(
        kind: IrohIdentityRecoveryKind,
        failed: Boolean,
        transitioning: Boolean = false,
    ) {
        publish(service.state.value.copy(
            status = IrohConnectionStatus.UNAVAILABLE,
            invite = null,
            endpointMark = null,
            message = null,
            transitioning = transitioning,
            identityRecovery = kind,
            recoveryAttemptFailed = failed,
        ))
    }
}

private suspend fun unsupportedRecoveryAction(): Nothing =
    throw UnsupportedOperationException("Iroh identity recovery is unavailable")
