package me.egigoka.pomodorough.data.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointTicket
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import computer.iroh.SendStream
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.local.IrohPeerEntity

data class IrohServiceContext(
    val roomId: String,
    val roomSecret: ByteArray,
    val deviceId: String,
    val displayName: String?,
)

class IrohReplicationService(
    private val store: IrohRoomStore,
    private val vault: IrohSecretVault,
    private val onProjection: suspend () -> Unit,
    private val random: Random = Random.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val syncMutex = Mutex()
    private val inboundSessions = Semaphore(8)
    private val generation = AtomicLong()
    private val _state = MutableStateFlow(IrohNetworkState())
    val state: StateFlow<IrohNetworkState> = _state.asStateFlow()

    internal fun replaceState(value: IrohNetworkState) {
        _state.value = value
    }

    private var endpoint: Endpoint? = null
    private var context: IrohServiceContext? = null
    private var endpointTicket: String? = null
    private var acceptJob: Job? = null
    private var syncJob: Job? = null

    suspend fun start(
        nextContext: IrohServiceContext,
        startPeriodicSync: Boolean = true,
    ): String = lifecycleMutex.withLock {
        val currentEndpoint = endpoint
        if (context?.roomId == nextContext.roomId && currentEndpoint?.isClosed() == false &&
            acceptJob?.isActive == true
        ) {
            nextContext.roomSecret.fill(0)
            if (startPeriodicSync && syncJob == null) {
                val owner = generation.get()
                syncJob = scope.launch { periodicSyncLoop(owner) }
            }
            return endpointTicket ?: createEndpointTicket(currentEndpoint).also { endpointTicket = it }
        }
        stopLocked()
        updateState(IrohConnectionStatus.STARTING, nextContext.roomId)
        val builder = EndpointBuilder()
        val bound = try {
            val secret = endpointSecret()
            try {
                builder.applyN0()
                builder.alpns(listOf(IrohProtocolV1.Alpn))
                builder.secretKey(secret)
                builder.bind()
            } finally {
                secret.fill(0)
            }
        } catch (error: Exception) {
            updateState(
                IrohConnectionStatus.UNAVAILABLE,
                nextContext.roomId,
                message = error.message ?: "Iroh endpoint could not start",
            )
            throw error
        } finally {
            builder.close()
        }
        val ticket = try {
            createEndpointTicket(bound)
        } catch (error: Exception) {
            runCatching { bound.shutdown() }
            bound.close()
            updateState(
                IrohConnectionStatus.UNAVAILABLE,
                nextContext.roomId,
                message = error.message ?: "Iroh endpoint ticket could not be created",
            )
            throw error
        }
        endpoint = bound
        context = nextContext
        endpointTicket = ticket
        val owner = generation.incrementAndGet()
        acceptJob = scope.launch { acceptLoop(bound, owner) }
        if (startPeriodicSync) syncJob = scope.launch { periodicSyncLoop(owner) }
        val mark = bound.id().use { it.fmtShort() }
        updateState(IrohConnectionStatus.LISTENING, nextContext.roomId, endpointMark = mark)
        ticket
    }

    suspend fun stop() = lifecycleMutex.withLock { stopLocked() }

    suspend fun stopIf(condition: () -> Boolean) = lifecycleMutex.withLock {
        if (condition()) stopLocked()
    }

    suspend fun currentEndpointTicket(): String {
        val current = endpoint?.takeUnless(Endpoint::isClosed)
            ?: throw IllegalStateException("Iroh endpoint is not running")
        return createEndpointTicket(current).also { endpointTicket = it }
    }

    suspend fun join(invite: IrohRoomInvite): IrohRoomProjection {
        val currentEndpoint = checkNotNull(endpoint) { "Iroh endpoint is not running" }
        val currentContext = checkNotNull(context).takeIf { it.roomId == invite.roomId }
            ?: throw IllegalStateException("Iroh endpoint is running for another room")
        return withTimeout(45_000L) { withParsedTicket(invite.endpointTicket) { expectedId, ticket ->
            require(expectedId == ticket.endpointAddr().use { it.id().use(Any::toString) }) {
                "Invite endpoint identity changed"
            }
            val connection = ticket.endpointAddr().use { address ->
                currentEndpoint.connect(address, IrohProtocolV1.Alpn)
            }
            try {
                require(connection.remoteId().use(Any::toString) == expectedId) {
                    "Connected endpoint does not match invite ticket"
                }
                performHello(connection, currentContext)
                pullWhileServing(connection, currentContext, generation.get())
                store.activateJoinedRoom(invite.roomId).also { onProjection() }
            } finally {
                closeConnection(connection, "join complete")
            }
        } }
    }

    fun endpointIdForTicket(value: String): String {
        var result: String? = null
        withParsedTicketBlocking(value) { result = it }
        return checkNotNull(result)
    }

    suspend fun syncNow() {
        val currentContext = context ?: return
        withTimeout(60_000L) {
            syncMutex.withLock { syncKnownPeers(currentContext, generation.get()) }
        }
    }

    private suspend fun stopLocked() {
        generation.incrementAndGet()
        val accepting = acceptJob
        val syncing = syncJob
        acceptJob = null
        syncJob = null
        accepting?.cancel()
        syncing?.cancel()
        if (accepting != null) runCatching { accepting.cancelAndJoin() }
        if (syncing != null) runCatching { syncing.cancelAndJoin() }
        val closing = endpoint
        context?.roomSecret?.fill(0)
        endpoint = null
        endpointTicket = null
        context = null
        if (closing != null) {
            runCatching { closing.shutdown() }
            closing.close()
        }
        _state.value = _state.value.copy(
            status = IrohConnectionStatus.STOPPED,
            endpointMark = null,
        )
    }

    private suspend fun acceptLoop(endpoint: Endpoint, owner: Long) = supervisorScope {
        while (currentCoroutineContext().isActive && owner == generation.get() && !endpoint.isClosed()) {
            val incoming = try {
                endpoint.acceptNext()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateState(IrohConnectionStatus.UNAVAILABLE, message = error.message)
                return@supervisorScope
            } ?: return@supervisorScope
            launch {
                if (!inboundSessions.tryAcquire()) {
                    runCatching { incoming.ignore() }
                    incoming.close()
                    return@launch
                }
                try {
                    val connection = withTimeout(10_000L) {
                        val accepting = incoming.accept()
                        try {
                            require(accepting.alpn().contentEquals(IrohProtocolV1.Alpn)) { "Wrong ALPN" }
                            accepting.connect()
                        } finally {
                            accepting.close()
                        }
                    }
                    handleIncoming(connection, owner)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    runCatching { incoming.ignore() }
                } finally {
                    incoming.close()
                    inboundSessions.release()
                }
            }
        }
    }

    private suspend fun handleIncoming(connection: Connection, owner: Long) {
        try {
            val activeContext = context
            if (owner != generation.get() || activeContext == null ||
                !connection.alpn().contentEquals(IrohProtocolV1.Alpn)
            ) return closeConnection(connection, "wrong protocol")
            withTimeout(10_000L) {
                val helloStream = connection.acceptBi()
                val helloRecv = helloStream.recv()
                val helloSend = helloStream.send()
                val helloMessage = try {
                    readMessage(
                        helloRecv,
                        activeContext.roomSecret,
                        IrohProtocolV1.MaxHelloBodyBytes,
                    )
                } finally {
                    helloRecv.close()
                }
                val hello = (helloMessage as? IrohRpcMessage.Hello)?.value
                    ?: return@withTimeout closeConnection(connection, "hello required")
                validateHello(hello, activeContext, connection.remoteId().use(Any::toString))
                store.upsertPeer(
                    IrohPeerEntity(
                        roomId = activeContext.roomId,
                        endpointId = connection.remoteId().use(Any::toString),
                        endpointTicket = hello.endpointTicket,
                        deviceId = hello.deviceId,
                        displayName = hello.displayName,
                        lastSeenAtMs = System.currentTimeMillis(),
                    ),
                )
                try {
                    writeMessage(
                        IrohRpcMessage.Hello(IrohHello(
                            protocolVersion = IrohProtocolV1.Version,
                            roomId = activeContext.roomId,
                            requestId = hello.requestId,
                            kind = "hello",
                            deviceId = activeContext.deviceId,
                            endpointTicket = checkNotNull(endpointTicket),
                            platform = "android",
                            displayName = activeContext.displayName,
                        )),
                        helloSend,
                        activeContext.roomSecret,
                    )
                } finally {
                    helloSend.close()
                    helloStream.close()
                }
            }
            serveAuthenticatedRequests(connection, activeContext, owner)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            closeConnection(connection, "connection ended")
        } finally {
            connection.close()
        }
    }

    private suspend fun serveAuthenticatedRequests(
        connection: Connection,
        activeContext: IrohServiceContext,
        owner: Long,
    ) {
        while (currentCoroutineContext().isActive && owner == generation.get()) {
            val stream = withTimeout(60_000L) { connection.acceptBi() }
            handleAuthenticatedRequest(stream, activeContext)
        }
    }

    private suspend fun handleAuthenticatedRequest(stream: BiStream, activeContext: IrohServiceContext) {
        val recv = stream.recv()
        val send = stream.send()
        try {
            val message = try {
                readMessage(recv, activeContext.roomSecret)
            } catch (_: Exception) {
                return
            }
            val response = try {
                when (message) {
                    is IrohRpcMessage.Inventory -> {
                        require(message.value.roomId == activeContext.roomId) { "Wrong room" }
                        val inventory = store.inventory(
                            activeContext.roomId,
                            message.value.after,
                            message.value.limit,
                        )
                        IrohRpcMessage.InventoryResult(
                            IrohInventoryResult(
                                IrohProtocolV1.Version,
                                activeContext.roomId,
                                message.requestId,
                                "inventoryResult",
                                inventory.first,
                                inventory.second,
                            ),
                        )
                    }
                    is IrohRpcMessage.Operations -> {
                        require(message.value.roomId == activeContext.roomId) { "Wrong room" }
                        IrohRpcMessage.OperationsResult(
                            IrohOperationsResult(
                                IrohProtocolV1.Version,
                                activeContext.roomId,
                                message.requestId,
                                store.operations(activeContext.roomId, message.value.refs),
                            ),
                        )
                    }
                    else -> throw IllegalArgumentException("Request kind is unavailable after hello")
                }
            } catch (error: Exception) {
                IrohRpcMessage.Error(
                    IrohErrorResponse(
                        protocolVersion = IrohProtocolV1.Version,
                        roomId = activeContext.roomId,
                        requestId = message.requestId,
                        kind = "error",
                        code = when {
                            error is NoSuchElementException -> "not_found"
                            error.message == "Wrong room" -> "wrong_room"
                            else -> "invalid_request"
                        },
                        message = error.message?.take(1_024) ?: "Invalid request",
                        retryable = false,
                    ),
                )
            }
            writeMessage(response, send, activeContext.roomSecret)
        } finally {
            send.close()
            recv.close()
            stream.close()
        }
    }

    private suspend fun periodicSyncLoop(owner: Long) {
        var retryMs = 2_000L
        while (scope.isActive && owner == generation.get()) {
            val activeContext = context ?: return
            val success = syncMutex.withLock { syncKnownPeers(activeContext, owner) }
            retryMs = if (success) 15_000L else min(60_000L, retryMs * 2)
            val jitterMs = random.nextLong(0, retryMs / 5 + 1)
            delay(cappedRetryDelayMs(retryMs, jitterMs))
        }
    }

    private suspend fun syncKnownPeers(activeContext: IrohServiceContext, owner: Long): Boolean {
        val currentEndpoint = endpoint ?: return false
        if (store.snapshot(activeContext.roomId).conflict != null) {
            updateState(IrohConnectionStatus.CONFLICT, activeContext.roomId)
            quarantineConflict(activeContext.roomId, owner)
            return false
        }
        val peers = runCatching { store.peers(activeContext.roomId) }.getOrElse { error ->
            updateState(IrohConnectionStatus.UNAVAILABLE, message = error.message)
            return false
        }
        if (peers.isEmpty()) {
            updateState(IrohConnectionStatus.WAITING_FOR_PEERS, activeContext.roomId)
            return true
        }
        var synchronized = false
        for (peer in peers) {
            if (owner != generation.get()) return false
            try {
                withTimeout(45_000L) { withParsedTicket(peer.endpointTicket) { endpointId, ticket ->
                    require(endpointId == peer.endpointId) { "Saved endpoint ticket identity changed" }
                    updateState(
                        IrohConnectionStatus.SYNCING,
                        activeContext.roomId,
                        endpointMark = endpointId.takeLast(6),
                    )
                    val connection = ticket.endpointAddr().use { address ->
                        currentEndpoint.connect(address, IrohProtocolV1.Alpn)
                    }
                    try {
                        require(connection.remoteId().use(Any::toString) == peer.endpointId)
                        performHello(connection, activeContext)
                        pullWhileServing(connection, activeContext, owner)
                        synchronized = true
                    } finally {
                        closeConnection(connection, "sync complete")
                    }
                } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (runCatching { store.snapshot(activeContext.roomId).conflict }.getOrNull() != null) break
                continue
            }
        }
        if (store.snapshot(activeContext.roomId).conflict != null) {
            updateState(IrohConnectionStatus.CONFLICT, activeContext.roomId)
            quarantineConflict(activeContext.roomId, owner)
            return false
        }
        updateState(
            if (synchronized) IrohConnectionStatus.LISTENING else IrohConnectionStatus.WAITING_FOR_PEERS,
            activeContext.roomId,
        )
        return synchronized
    }

    private fun quarantineConflict(roomId: String, owner: Long) {
        scope.launch {
            lifecycleMutex.withLock {
                if (owner != generation.get() || context?.roomId != roomId) return@withLock
                stopLocked()
                updateState(IrohConnectionStatus.CONFLICT, roomId)
            }
        }
    }

    private suspend fun performHello(connection: Connection, activeContext: IrohServiceContext) {
        val requestId = IrohProtocolV1.requestId()
        val response = request(
            IrohRpcMessage.Hello(IrohHello(
                protocolVersion = IrohProtocolV1.Version,
                roomId = activeContext.roomId,
                requestId = requestId,
                kind = "hello",
                deviceId = activeContext.deviceId,
                endpointTicket = checkNotNull(endpointTicket),
                platform = "android",
                displayName = activeContext.displayName,
            )),
            connection,
            activeContext.roomSecret,
            maxResponseBodyBytes = IrohProtocolV1.MaxHelloBodyBytes,
        )
        val hello = (response as? IrohRpcMessage.Hello)?.value
            ?: throw IllegalArgumentException("Peer did not return hello")
        require(hello.requestId == requestId)
        val remoteId = connection.remoteId().use(Any::toString)
        validateHello(hello, activeContext, remoteId)
        store.upsertPeer(
            IrohPeerEntity(
                roomId = activeContext.roomId,
                endpointId = remoteId,
                endpointTicket = hello.endpointTicket,
                deviceId = hello.deviceId,
                displayName = hello.displayName,
                lastSeenAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun pullUntilConverged(connection: Connection, activeContext: IrohServiceContext) {
        if (!store.hasGenesis(activeContext.roomId)) {
            val requestId = IrohProtocolV1.requestId()
            val genesisReference = IrohInventoryReference(IrohDomain.genesis, "genesis")
            val response = request(
                IrohRpcMessage.Operations(
                    IrohOperationsRequest(
                        IrohProtocolV1.Version,
                        activeContext.roomId,
                        requestId,
                        "operations",
                        listOf(genesisReference),
                    ),
                ),
                connection,
                activeContext.roomSecret,
            )
            val result = (response as? IrohRpcMessage.OperationsResult)?.value
                ?: throw IllegalArgumentException("Peer returned no room genesis")
            require(result.requestId == requestId && result.roomId == activeContext.roomId &&
                result.records.size == 1 && result.records.single().domain == IrohDomain.genesis &&
                result.records.single().id == "genesis"
            ) { "Peer returned an invalid room genesis" }
            store.insertRemoteRecords(activeContext.roomId, result.records)
        }
        var foundChanges: Boolean
        do {
            foundChanges = false
            var cursor: String? = null
            do {
                val requestId = IrohProtocolV1.requestId()
                val response = request(
                    IrohRpcMessage.Inventory(
                        IrohInventoryRequest(
                            IrohProtocolV1.Version,
                            activeContext.roomId,
                            requestId,
                            "inventory",
                            cursor,
                            IrohProtocolV1.MaxInventoryEntries,
                        ),
                    ),
                    connection,
                    activeContext.roomSecret,
                )
                val inventory = (response as? IrohRpcMessage.InventoryResult)?.value
                    ?: throw IllegalArgumentException("Peer returned wrong inventory response")
                require(inventory.requestId == requestId && inventory.roomId == activeContext.roomId)
                val afterReference = cursor?.let(::cursorReference)
                require(afterReference == null || inventory.entries.all { entry ->
                    IrohMessageCodec.referenceComparator.compare(afterReference, entry.reference) < 0
                }) { "Peer inventory did not advance past cursor" }
                require(inventory.next == null || inventory.entries.lastOrNull()?.let(::cursor) == inventory.next) {
                    "Peer inventory cursor is inconsistent"
                }
                val missing = store.missingReferences(activeContext.roomId, inventory.entries)
                val advertised = inventory.entries.associateBy(IrohInventoryEntry::reference)
                missing.chunked(IrohProtocolV1.MaxOperationReferences).forEach { references ->
                    val operationsId = IrohProtocolV1.requestId()
                    val operationsResponse = request(
                        IrohRpcMessage.Operations(
                            IrohOperationsRequest(
                                IrohProtocolV1.Version,
                                activeContext.roomId,
                                operationsId,
                                "operations",
                                references,
                            ),
                        ),
                        connection,
                        activeContext.roomSecret,
                    )
                    val result = (operationsResponse as? IrohRpcMessage.OperationsResult)?.value
                        ?: throw IllegalArgumentException("Peer returned wrong operations response")
                    require(result.requestId == operationsId && result.roomId == activeContext.roomId &&
                        result.records.size == references.size &&
                        result.records.map { IrohInventoryReference(it.domain, it.id) }.toSet() ==
                        references.toSet()
                    ) { "Peer returned partial operation set" }
                    require(result.records.all { record ->
                        advertised[IrohInventoryReference(record.domain, record.id)]?.digest == record.digest()
                    }) { "Peer operation digest does not match inventory" }
                    store.insertRemoteRecords(activeContext.roomId, result.records)
                    foundChanges = true
                }
                cursor = inventory.next
            } while (cursor != null)
            if (foundChanges) {
                store.refreshProjection(activeContext.roomId)
                onProjection()
            }
        } while (foundChanges)
    }

    private suspend fun pullWhileServing(
        connection: Connection,
        activeContext: IrohServiceContext,
        owner: Long,
    ) = coroutineScope {
        val serving = launch {
            serveAuthenticatedRequests(connection, activeContext, owner)
        }
        try {
            pullUntilConverged(connection, activeContext)
        } finally {
            serving.cancelAndJoin()
        }
    }

    private suspend fun request(
        message: IrohRpcMessage,
        connection: Connection,
        secret: ByteArray,
        maxResponseBodyBytes: Int = IrohProtocolV1.MaxFrameBodyBytes,
    ): IrohRpcMessage {
        return withTimeout(30_000L) {
            val stream = connection.openBi()
            val send = stream.send()
            val recv = stream.recv()
            try {
                writeMessage(message, send, secret)
                readMessage(recv, secret, maxResponseBodyBytes).also { response ->
                    require(response.requestId == message.requestId) { "Response request ID does not match" }
                    if (response is IrohRpcMessage.Error) {
                        throw IllegalStateException(response.value.message)
                    }
                }
            } finally {
                send.close()
                recv.close()
                stream.close()
            }
        }
    }

    private suspend fun readMessage(
        stream: RecvStream,
        secret: ByteArray,
        maxBodyBytes: Int = IrohProtocolV1.MaxFrameBodyBytes,
    ): IrohRpcMessage {
        return withTimeout(30_000L) {
            val header = stream.readExact(4u)
            val bodyLength = ByteBuffer.wrap(header).int
            require(bodyLength in 0..maxBodyBytes) { "Invalid Iroh frame length" }
            val mac = stream.readExact(32u)
            val body = stream.readExact(bodyLength.toUInt())
            require(stream.read(1u).isEmpty()) { "Iroh stream contains trailing bytes" }
            IrohMessageCodec.decode(IrohFrameCodec.decode(header + mac + body, secret))
        }
    }

    private suspend fun writeMessage(message: IrohRpcMessage, stream: SendStream, secret: ByteArray) {
        stream.writeAll(IrohFrameCodec.encode(IrohMessageCodec.encode(message), secret))
        stream.finish()
    }

    private fun validateHello(hello: IrohHello, activeContext: IrohServiceContext, remoteId: String) {
        require(hello.protocolVersion == IrohProtocolV1.Version && hello.kind == "hello" &&
            hello.roomId == activeContext.roomId && IrohProtocolV1.isRequestId(hello.requestId) &&
            IrohProtocolV1.isIdentifier(hello.deviceId) && IrohProtocolV1.isDisplayName(hello.displayName)
        ) { "Peer hello is invalid" }
        withParsedTicketBlocking(hello.endpointTicket) { endpointId ->
            require(endpointId == remoteId) { "Peer ticket does not match authenticated endpoint" }
        }
    }

    private fun endpointSecret(): ByteArray {
        vault.endpointSecret()?.let { secret ->
            require(secret.size == 32) { "Saved Iroh endpoint identity is invalid" }
            return secret
        }
        val key = SecretKey.generate()
        return try {
            val generated = key.toBytes()
            try {
                vault.writeEndpointSecret(generated)
                generated
            } catch (error: Exception) {
                generated.fill(0)
                throw error
            }
        } finally {
            key.close()
        }
    }

    private fun createEndpointTicket(endpoint: Endpoint): String {
        val address = endpoint.addr()
        return try {
            EndpointTicket.fromAddr(address).use(Any::toString)
        } finally {
            address.close()
        }
    }

    private suspend fun <T> withParsedTicket(
        value: String,
        block: suspend (endpointId: String, ticket: EndpointTicket) -> T,
    ): T {
        require(value.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes)
        val ticket = EndpointTicket.fromString(value)
        return try {
            val endpointId = ticket.endpointAddr().use { it.id().use(Any::toString) }
            block(endpointId, ticket)
        } finally {
            ticket.close()
        }
    }

    private fun withParsedTicketBlocking(value: String, block: (String) -> Unit) {
        require(value.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes)
        EndpointTicket.fromString(value).use { ticket ->
            block(ticket.endpointAddr().use { it.id().use(Any::toString) })
        }
    }

    private fun closeConnection(connection: Connection, reason: String) {
        runCatching { connection.close(0, reason.encodeToByteArray()) }
        connection.close()
    }

    private suspend fun updateState(
        status: IrohConnectionStatus,
        roomId: String? = context?.roomId,
        endpointMark: String? = _state.value.endpointMark,
        message: String? = null,
    ) {
        val snapshot = roomId?.let { runCatching { store.snapshot(it) }.getOrNull() }
        _state.value = (snapshot ?: _state.value).copy(
            status = if (snapshot?.conflict != null) IrohConnectionStatus.CONFLICT else status,
            roomId = roomId,
            endpointMark = endpointMark,
            message = message,
        )
    }

    private fun cursor(entry: IrohInventoryEntry) = entry.domain.name + "\u0000" + entry.id

    private fun cursorReference(value: String): IrohInventoryReference {
        val split = value.split('\u0000')
        require(split.size == 2)
        return IrohInventoryReference(IrohDomain.valueOf(split[0]), split[1])
    }
}

internal fun cappedRetryDelayMs(baseMs: Long, jitterMs: Long): Long {
    require(baseMs >= 0L && jitterMs >= 0L)
    return min(60_000L, baseMs + jitterMs)
}
