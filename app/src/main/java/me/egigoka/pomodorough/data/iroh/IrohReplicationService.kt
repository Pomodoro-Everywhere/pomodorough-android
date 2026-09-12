package me.egigoka.pomodorough.data.iroh

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.egigoka.pomodorough.data.local.IrohPeerEntity

interface IrohReplicationStore {
    suspend fun upsertPeer(peer: IrohPeerEntity)
    suspend fun inventory(
        roomId: String,
        after: String?,
        limit: Int,
    ): Pair<List<IrohInventoryEntry>, String?>
    suspend fun operations(
        roomId: String,
        references: List<IrohInventoryReference>,
    ): List<IrohOperationRecord>
    suspend fun peers(roomId: String): List<IrohPeerEntity>
    suspend fun snapshot(roomId: String): IrohNetworkState
    suspend fun hasGenesis(roomId: String): Boolean
    suspend fun missingReferences(
        roomId: String,
        remote: List<IrohInventoryEntry>,
    ): List<IrohInventoryReference>
    suspend fun insertRemoteRecords(roomId: String, records: List<IrohOperationRecord>)
    suspend fun refreshProjection(roomId: String): IrohRoomProjection
    suspend fun activateJoinedRoom(roomId: String): IrohRoomProjection
}

data class IrohServiceContext(
    val roomId: String,
    val roomSecret: ByteArray,
    val deviceId: String,
    val displayName: String?,
)

class IrohReplicationService internal constructor(
    private val store: IrohReplicationStore,
    binding: IrohEndpointBinding,
    onProjection: suspend () -> Unit,
    random: Random = Random.Default,
) {
    constructor(
        store: IrohRoomStore,
        vault: IrohSecretVault,
        onProjection: suspend () -> Unit,
        random: Random = Random.Default,
    ) : this(
        store,
        IrohNativeEndpointBinding(vault),
        onProjection,
        random,
    )

    private val _state = MutableStateFlow(IrohNetworkState())
    val state: StateFlow<IrohNetworkState> = _state.asStateFlow()

    private val transport = IrohEndpointTransport()
    private val lifecycle = IrohEndpointLifecycle(binding, ::handleEndpointEvent)
    private val authorization = IrohPeerAuthorization(
        IrohEndpointTicketIdentity(transport::endpointIdForTicket),
    )
    private val authentication = IrohPeerAuthentication(
        transport = transport,
        authorization = authorization,
        endpointTicket = { lifecycle.session()?.endpointTicket },
        rememberPeer = store::upsertPeer,
    )
    private val incomingRpc = IrohIncomingRpcHandler(
        sessions = lifecycle,
        authentication = authentication,
        transport = transport,
        dependencies = IrohIncomingRpcDependencies(store::inventory, store::operations),
        onEvent = ::handleIncomingEvent,
    )
    private val synchronization = IrohPeerSynchronization(
        sessions = lifecycle,
        transport = transport,
        authentication = authentication,
        incomingRpc = incomingRpc,
        dependencies = IrohPeerSyncDependencies(
            peers = store::peers,
            snapshot = store::snapshot,
            hasGenesis = store::hasGenesis,
            missingReferences = store::missingReferences,
            insertRemoteRecords = store::insertRemoteRecords,
            refreshProjection = store::refreshProjection,
            onProjection = onProjection,
        ),
        onEvent = ::handleSyncEvent,
        random = random,
    )
    private val roomJoin = IrohRoomJoinTransport(
        sessions = lifecycle,
        transport = transport,
        authentication = authentication,
        synchronization = synchronization,
        activateRoom = store::activateJoinedRoom,
        onProjection = onProjection,
    )

    internal fun replaceState(value: IrohNetworkState) {
        _state.value = value
    }

    suspend fun start(nextContext: IrohServiceContext, startPeriodicSync: Boolean = true): String =
        lifecycle.start(
            nextContext,
            startPeriodicSync,
            incomingRpc::acceptLoop,
            synchronization::periodicSyncLoop,
        )

    suspend fun stop() = lifecycle.stop()

    suspend fun replaceEndpointIdentity() = lifecycle.replaceEndpointIdentity()

    suspend fun beginIdentityReset() = lifecycle.beginIdentityReset()

    suspend fun completeIdentityReset() = lifecycle.completeIdentityReset()

    suspend fun close() = lifecycle.close()

    suspend fun stopIf(condition: () -> Boolean) = lifecycle.stopIf(condition)

    suspend fun currentEndpointTicket(): String = lifecycle.currentEndpointTicket()

    fun pendingIdentityRecovery(): IrohIdentityRecoveryKind? = lifecycle.pendingIdentityRecovery()

    suspend fun join(invite: IrohRoomInvite): IrohRoomProjection = roomJoin.join(invite)

    fun endpointIdForTicket(value: String): String = transport.endpointIdForTicket(value)

    suspend fun syncNow() = synchronization.syncNow()

    private suspend fun handleEndpointEvent(event: IrohEndpointEvent) {
        when (event) {
            is IrohEndpointEvent.Status -> updateState(
                event.status,
                event.roomId,
                event.endpointMark ?: _state.value.endpointMark,
                event.message,
            )
            is IrohEndpointEvent.RecoveryRequired -> {
                _state.value = _state.value.copy(
                    status = IrohConnectionStatus.UNAVAILABLE,
                    roomId = event.roomId,
                    endpointMark = null,
                    message = null,
                    transitioning = false,
                    identityRecovery = event.kind,
                    recoveryAttemptFailed = false,
                )
            }
            IrohEndpointEvent.Stopped -> {
                _state.value = _state.value.copy(
                    status = if (_state.value.identityRecovery == null) {
                        IrohConnectionStatus.STOPPED
                    } else {
                        IrohConnectionStatus.UNAVAILABLE
                    },
                    endpointMark = null,
                )
            }
        }
    }

    private suspend fun handleIncomingEvent(event: IrohIncomingRpcEvent) {
        when (event) {
            is IrohIncomingRpcEvent.Unavailable -> updateState(
                IrohConnectionStatus.UNAVAILABLE,
                message = event.message,
            )
        }
    }

    private suspend fun handleSyncEvent(event: IrohPeerSyncEvent) {
        when (event) {
            is IrohPeerSyncEvent.Status -> updateState(
                event.status,
                event.roomId,
                event.endpointMark ?: _state.value.endpointMark,
                event.message,
            )
            is IrohPeerSyncEvent.Conflict -> {
                updateState(IrohConnectionStatus.CONFLICT, event.roomId)
                lifecycle.quarantine(event.roomId, event.owner)
            }
        }
    }

    internal suspend fun updateState(
        status: IrohConnectionStatus,
        roomId: String? = lifecycle.session()?.context?.roomId,
        endpointMark: String? = _state.value.endpointMark,
        message: String? = null,
    ) {
        // expected-silent: snapshot failure keeps the last published state and
        // still publishes the new status event; storage errors surface through
        // the repository sync-failure UI, not a second crash here.
        // Cancellation still propagates via the getOrElse guard below.
        val snapshot = roomId?.let {
            runCatching { store.snapshot(it) }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
        }
        _state.value = (snapshot ?: _state.value).copy(
            status = if (snapshot?.conflict != null) IrohConnectionStatus.CONFLICT else status,
            roomId = roomId,
            endpointMark = endpointMark,
            message = message,
            identityRecovery = _state.value.identityRecovery,
            recoveryAttemptFailed = _state.value.recoveryAttemptFailed,
        )
    }
}

internal fun cappedRetryDelayMs(baseMs: Long, jitterMs: Long): Long {
    require(baseMs >= 0L && jitterMs >= 0L)
    return min(60_000L, baseMs + jitterMs)
}
