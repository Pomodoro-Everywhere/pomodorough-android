package me.egigoka.pomodorough.data.iroh

import computer.iroh.Connection
import computer.iroh.Endpoint
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.egigoka.pomodorough.data.local.IrohPeerEntity

internal data class IrohPeerSyncDependencies(
    val peers: suspend (String) -> List<IrohPeerEntity>,
    val snapshot: suspend (String) -> IrohNetworkState,
    val hasGenesis: suspend (String) -> Boolean,
    val missingReferences: suspend (
        String,
        List<IrohInventoryEntry>,
    ) -> List<IrohInventoryReference>,
    val insertRemoteRecords: suspend (String, List<IrohOperationRecord>) -> Unit,
    val refreshProjection: suspend (String) -> IrohRoomProjection,
    val onProjection: suspend () -> Unit,
)

internal sealed interface IrohPeerSyncEvent {
    data class Status(
        val status: IrohConnectionStatus,
        val roomId: String? = null,
        val endpointMark: String? = null,
        val message: String? = null,
    ) : IrohPeerSyncEvent

    data class Conflict(val roomId: String, val owner: Long) : IrohPeerSyncEvent
}

internal class IrohPeerSynchronization(
    private val sessions: IrohEndpointSessionSource,
    private val transport: IrohEndpointTransport,
    private val authentication: IrohPeerAuthentication,
    private val incomingRpc: IrohIncomingRpcHandler,
    private val dependencies: IrohPeerSyncDependencies,
    private val onEvent: suspend (IrohPeerSyncEvent) -> Unit,
    private val random: Random,
) {
    private val mutex = Mutex()

    suspend fun syncNow() {
        val session = sessions.session() ?: return
        withTimeout(60_000L) {
            mutex.withLock { syncKnownPeers(session.context, sessions.generation()) }
        }
    }

    suspend fun periodicSyncLoop(owner: Long) {
        var retryMs = 2_000L
        while (owner == sessions.generation()) {
            val context = sessions.session()?.context ?: return
            val success = mutex.withLock { syncKnownPeers(context, owner) }
            retryMs = if (success) 15_000L else min(60_000L, retryMs * 2)
            val jitterMs = random.nextLong(0, retryMs / 5 + 1)
            delay(cappedRetryDelayMs(retryMs, jitterMs))
        }
    }

    suspend fun pullWhileServing(
        connection: Connection,
        context: IrohServiceContext,
        owner: Long,
    ) = coroutineScope {
        val serving = launch { incomingRpc.serveAuthenticatedRequests(connection, context, owner) }
        try {
            pullUntilConverged(connection, context)
        } finally {
            serving.cancelAndJoin()
        }
    }

    private suspend fun syncKnownPeers(context: IrohServiceContext, owner: Long): Boolean {
        val endpoint = sessions.session()?.endpoint ?: return false
        if (quarantineIfConflicted(context.roomId, owner)) return false
        val peers = knownPeers(context.roomId) ?: return false
        if (peers.isEmpty()) {
            onEvent(IrohPeerSyncEvent.Status(
                IrohConnectionStatus.WAITING_FOR_PEERS,
                context.roomId,
            ))
            return true
        }
        val synchronized = syncPeers(endpoint, context, owner, peers) ?: return false
        if (quarantineIfConflicted(context.roomId, owner)) return false
        onEvent(IrohPeerSyncEvent.Status(
            if (synchronized) IrohConnectionStatus.LISTENING else
                IrohConnectionStatus.WAITING_FOR_PEERS,
            context.roomId,
        ))
        return synchronized
    }

    private suspend fun knownPeers(roomId: String): List<IrohPeerEntity>? {
        return runCatching { dependencies.peers(roomId) }.getOrElse { error ->
            onEvent(IrohPeerSyncEvent.Status(
                IrohConnectionStatus.UNAVAILABLE,
                roomId = roomId,
                message = error.message,
            ))
            null
        }
    }

    private suspend fun syncPeers(
        endpoint: Endpoint,
        context: IrohServiceContext,
        owner: Long,
        peers: List<IrohPeerEntity>,
    ): Boolean? {
        var synchronized = false
        for (peer in peers) {
            if (owner != sessions.generation()) return null
            try {
                syncPeer(endpoint, context, owner, peer)
                synchronized = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (runCatching { dependencies.snapshot(context.roomId).conflict }
                        .getOrNull() != null
                ) break
                continue
            }
        }
        return synchronized
    }

    private suspend fun syncPeer(
        endpoint: Endpoint,
        context: IrohServiceContext,
        owner: Long,
        peer: IrohPeerEntity,
    ) = withTimeout(45_000L) {
        transport.withParsedTicket(peer.endpointTicket) { endpointId, ticket ->
            require(endpointId == peer.endpointId) { "Saved endpoint ticket identity changed" }
            onEvent(IrohPeerSyncEvent.Status(
                IrohConnectionStatus.SYNCING,
                context.roomId,
                endpointMark = endpointId.takeLast(6),
            ))
            val connection = ticket.endpointAddr().use { address ->
                endpoint.connect(address, IrohProtocolV1.Alpn)
            }
            try {
                require(connection.remoteId().use(Any::toString) == peer.endpointId)
                authentication.performHello(connection, context)
                pullWhileServing(connection, context, owner)
            } finally {
                transport.closeConnection(connection, "sync complete")
            }
        }
    }

    private suspend fun quarantineIfConflicted(roomId: String, owner: Long): Boolean {
        if (dependencies.snapshot(roomId).conflict == null) return false
        onEvent(IrohPeerSyncEvent.Conflict(roomId, owner))
        return true
    }

    private suspend fun pullUntilConverged(
        connection: Connection,
        context: IrohServiceContext,
    ) {
        if (!dependencies.hasGenesis(context.roomId)) pullGenesis(connection, context)
        var foundChanges: Boolean
        do {
            foundChanges = pullInventoryPass(connection, context)
            if (foundChanges) {
                dependencies.refreshProjection(context.roomId)
                dependencies.onProjection()
            }
        } while (foundChanges)
    }

    private suspend fun pullGenesis(connection: Connection, context: IrohServiceContext) {
        val requestId = IrohProtocolV1.requestId()
        val response = transport.request(
            IrohRpcMessage.Operations(IrohOperationsRequest(
                IrohProtocolV1.Version,
                context.roomId,
                requestId,
                "operations",
                listOf(IrohInventoryReference(IrohDomain.genesis, "genesis")),
            )),
            connection,
            context.roomSecret,
        )
        val result = (response as? IrohRpcMessage.OperationsResult)?.value
            ?: throw IllegalArgumentException("Peer returned no room genesis")
        require(
            result.requestId == requestId && result.roomId == context.roomId &&
                result.records.size == 1 &&
                result.records.single().domain == IrohDomain.genesis &&
                result.records.single().id == "genesis",
        ) { "Peer returned an invalid room genesis" }
        dependencies.insertRemoteRecords(context.roomId, result.records)
    }

    private suspend fun pullInventoryPass(
        connection: Connection,
        context: IrohServiceContext,
    ): Boolean {
        var foundChanges = false
        var cursor: String? = null
        do {
            val page = requestInventoryPage(connection, context, cursor)
            val missing = dependencies.missingReferences(context.roomId, page.entries)
            if (missing.isNotEmpty()) {
                pullMissingOperations(connection, context, missing, page.entries)
                foundChanges = true
            }
            cursor = page.next
        } while (cursor != null)
        return foundChanges
    }

    private suspend fun requestInventoryPage(
        connection: Connection,
        context: IrohServiceContext,
        cursor: String?,
    ): IrohInventoryResult {
        val requestId = IrohProtocolV1.requestId()
        val response = transport.request(
            IrohRpcMessage.Inventory(IrohInventoryRequest(
                IrohProtocolV1.Version,
                context.roomId,
                requestId,
                "inventory",
                cursor,
                IrohProtocolV1.MaxInventoryEntries,
            )),
            connection,
            context.roomSecret,
        )
        val inventory = (response as? IrohRpcMessage.InventoryResult)?.value
            ?: throw IllegalArgumentException("Peer returned wrong inventory response")
        require(inventory.requestId == requestId && inventory.roomId == context.roomId)
        validateInventoryCursor(cursor, inventory)
        return inventory
    }

    private fun validateInventoryCursor(cursor: String?, inventory: IrohInventoryResult) {
        val afterReference = cursor?.let(::cursorReference)
        require(afterReference == null || inventory.entries.all { entry ->
            IrohMessageCodec.referenceComparator.compare(afterReference, entry.reference) < 0
        }) { "Peer inventory did not advance past cursor" }
        require(inventory.next == null || inventory.entries.lastOrNull()?.let(::cursor) == inventory.next) {
            "Peer inventory cursor is inconsistent"
        }
    }

    private suspend fun pullMissingOperations(
        connection: Connection,
        context: IrohServiceContext,
        missing: List<IrohInventoryReference>,
        entries: List<IrohInventoryEntry>,
    ) {
        val advertised = entries.associateBy(IrohInventoryEntry::reference)
        missing.chunked(IrohProtocolV1.MaxOperationReferences).forEach { references ->
            val result = requestOperations(connection, context, references)
            require(
                result.records.size == references.size &&
                    result.records.map { IrohInventoryReference(it.domain, it.id) }.toSet() ==
                    references.toSet(),
            ) { "Peer returned partial operation set" }
            require(result.records.all { record ->
                advertised[IrohInventoryReference(record.domain, record.id)]?.digest == record.digest()
            }) { "Peer operation digest does not match inventory" }
            dependencies.insertRemoteRecords(context.roomId, result.records)
        }
    }

    private suspend fun requestOperations(
        connection: Connection,
        context: IrohServiceContext,
        references: List<IrohInventoryReference>,
    ): IrohOperationsResult {
        val requestId = IrohProtocolV1.requestId()
        val response = transport.request(
            IrohRpcMessage.Operations(IrohOperationsRequest(
                IrohProtocolV1.Version,
                context.roomId,
                requestId,
                "operations",
                references,
            )),
            connection,
            context.roomSecret,
        )
        val result = (response as? IrohRpcMessage.OperationsResult)?.value
            ?: throw IllegalArgumentException("Peer returned wrong operations response")
        require(result.requestId == requestId && result.roomId == context.roomId)
        return result
    }

    private fun cursor(entry: IrohInventoryEntry) = entry.domain.name + "\u0000" + entry.id

    private fun cursorReference(value: String): IrohInventoryReference {
        val split = value.split('\u0000')
        require(split.size == 2)
        return IrohInventoryReference(IrohDomain.valueOf(split[0]), split[1])
    }
}
