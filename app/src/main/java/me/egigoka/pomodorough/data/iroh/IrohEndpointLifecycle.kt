package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointTicket
import computer.iroh.SecretKey
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class IrohEndpointSession(
    val endpoint: Endpoint,
    val context: IrohServiceContext,
    val endpointTicket: String,
    val owner: Long,
)

internal interface IrohEndpointSessionSource {
    fun session(): IrohEndpointSession?
    fun generation(): Long
}

internal sealed interface IrohEndpointEvent {
    data class Status(
        val status: IrohConnectionStatus,
        val roomId: String? = null,
        val endpointMark: String? = null,
        val message: String? = null,
    ) : IrohEndpointEvent

    data object Stopped : IrohEndpointEvent
}

internal class IrohEndpointLifecycle(
    private val vault: IrohSecretVault,
    private val onEvent: suspend (IrohEndpointEvent) -> Unit,
) : IrohEndpointSessionSource {
    private val lifecycleJob: Job = SupervisorJob()
    private val scope = CoroutineScope(lifecycleJob + Dispatchers.IO)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val owner = AtomicLong()

    private var endpoint: Endpoint? = null
    private var context: IrohServiceContext? = null
    private var endpointTicket: String? = null
    private var acceptJob: Job? = null
    private var syncJob: Job? = null

    override fun session(): IrohEndpointSession? {
        val activeEndpoint = endpoint ?: return null
        val activeContext = context ?: return null
        val ticket = endpointTicket ?: return null
        return IrohEndpointSession(activeEndpoint, activeContext, ticket, owner.get())
    }

    override fun generation(): Long = owner.get()

    suspend fun start(
        nextContext: IrohServiceContext,
        startPeriodicSync: Boolean,
        acceptLoop: suspend (Endpoint, Long) -> Unit,
        periodicSync: suspend (Long) -> Unit,
    ): String = mutex.withLock {
        check(!closed.get()) { "Iroh endpoint lifecycle is closed" }
        reuse(nextContext, startPeriodicSync, periodicSync)?.let { return it }
        stopLocked()
        onEvent(IrohEndpointEvent.Status(IrohConnectionStatus.STARTING, nextContext.roomId))
        val bound = bindEndpoint(nextContext)
        val ticket = createTicketOrClose(bound, nextContext)
        activate(bound, nextContext, ticket, startPeriodicSync, acceptLoop, periodicSync)
        ticket
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutex.withLock { stopLocked() }
        lifecycleJob.cancelAndJoin()
    }

    suspend fun stopIf(condition: () -> Boolean) = mutex.withLock {
        if (condition()) stopLocked()
    }

    suspend fun currentEndpointTicket(): String {
        val current = endpoint?.takeUnless(Endpoint::isClosed)
            ?: throw IllegalStateException("Iroh endpoint is not running")
        return createEndpointTicket(current).also { endpointTicket = it }
    }

    fun quarantine(roomId: String, quarantineOwner: Long) {
        if (closed.get()) return
        scope.launch {
            mutex.withLock {
                if (quarantineOwner != owner.get() || context?.roomId != roomId) return@withLock
                stopLocked()
                onEvent(IrohEndpointEvent.Status(IrohConnectionStatus.CONFLICT, roomId))
            }
        }
    }

    private fun reuse(
        nextContext: IrohServiceContext,
        startPeriodicSync: Boolean,
        periodicSync: suspend (Long) -> Unit,
    ): String? {
        val currentEndpoint = endpoint
        if (context?.roomId != nextContext.roomId || currentEndpoint?.isClosed() != false ||
            acceptJob?.isActive != true
        ) return null
        nextContext.roomSecret.fill(0)
        if (startPeriodicSync && syncJob == null) {
            val currentOwner = owner.get()
            syncJob = scope.launch { periodicSync(currentOwner) }
        }
        return endpointTicket ?: createEndpointTicket(currentEndpoint).also { endpointTicket = it }
    }

    private suspend fun bindEndpoint(nextContext: IrohServiceContext): Endpoint {
        val builder = EndpointBuilder()
        return try {
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
            onEvent(IrohEndpointEvent.Status(
                IrohConnectionStatus.UNAVAILABLE,
                nextContext.roomId,
                message = error.message ?: "Iroh endpoint could not start",
            ))
            throw error
        } finally {
            builder.close()
        }
    }

    private suspend fun createTicketOrClose(
        bound: Endpoint,
        nextContext: IrohServiceContext,
    ): String = try {
        createEndpointTicket(bound)
    } catch (error: Exception) {
        runCatching { bound.shutdown() }
        bound.close()
        onEvent(IrohEndpointEvent.Status(
            IrohConnectionStatus.UNAVAILABLE,
            nextContext.roomId,
            message = error.message ?: "Iroh endpoint ticket could not be created",
        ))
        throw error
    }

    private suspend fun activate(
        bound: Endpoint,
        nextContext: IrohServiceContext,
        ticket: String,
        startPeriodicSync: Boolean,
        acceptLoop: suspend (Endpoint, Long) -> Unit,
        periodicSync: suspend (Long) -> Unit,
    ) {
        endpoint = bound
        context = nextContext
        endpointTicket = ticket
        val currentOwner = owner.incrementAndGet()
        acceptJob = scope.launch { acceptLoop(bound, currentOwner) }
        if (startPeriodicSync) syncJob = scope.launch { periodicSync(currentOwner) }
        val mark = bound.id().use { it.fmtShort() }
        onEvent(IrohEndpointEvent.Status(
            IrohConnectionStatus.LISTENING,
            nextContext.roomId,
            endpointMark = mark,
        ))
    }

    private suspend fun stopLocked() {
        owner.incrementAndGet()
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
        onEvent(IrohEndpointEvent.Stopped)
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
}
