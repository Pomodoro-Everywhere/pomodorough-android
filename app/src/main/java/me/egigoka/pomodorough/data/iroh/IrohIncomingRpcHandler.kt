package me.egigoka.pomodorough.data.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.RecvStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout

internal data class IrohIncomingRpcDependencies(
    val inventory: suspend (String, String?, Int) -> Pair<List<IrohInventoryEntry>, String?>,
    val operations: suspend (String, List<IrohInventoryReference>) -> List<IrohOperationRecord>,
)

internal sealed interface IrohIncomingRpcEvent {
    data class Unavailable(val message: String?) : IrohIncomingRpcEvent
}

internal class IrohIncomingRpcHandler(
    private val sessions: IrohEndpointSessionSource,
    private val authentication: IrohPeerAuthentication,
    private val transport: IrohEndpointTransport,
    private val dependencies: IrohIncomingRpcDependencies,
    private val onEvent: suspend (IrohIncomingRpcEvent) -> Unit,
) {
    private val inboundSessions = Semaphore(8)

    suspend fun acceptLoop(endpoint: Endpoint, owner: Long) = supervisorScope {
        while (currentCoroutineContext().isActive && owner == sessions.generation() &&
            !endpoint.isClosed()
        ) {
            val incoming = try {
                endpoint.acceptNext()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onEvent(IrohIncomingRpcEvent.Unavailable(error.message))
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

    suspend fun serveAuthenticatedRequests(
        connection: Connection,
        context: IrohServiceContext,
        owner: Long,
    ) {
        while (currentCoroutineContext().isActive && owner == sessions.generation()) {
            val stream = withTimeout(60_000L) { connection.acceptBi() }
            handleAuthenticatedRequest(stream, context)
        }
    }

    private suspend fun handleIncoming(connection: Connection, owner: Long) {
        try {
            val session = sessions.session()
            val context = session?.context
            if (owner != sessions.generation() || context == null ||
                !connection.alpn().contentEquals(IrohProtocolV1.Alpn)
            ) return transport.closeConnection(connection, "wrong protocol")
            if (!authentication.authenticateIncoming(connection, context)) return
            serveAuthenticatedRequests(connection, context, owner)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            transport.closeConnection(connection, "connection ended")
        } finally {
            connection.close()
        }
    }

    private suspend fun handleAuthenticatedRequest(
        stream: BiStream,
        context: IrohServiceContext,
    ) {
        val recv = stream.recv()
        val send = stream.send()
        try {
            val message = readAuthenticatedRequest(recv, context) ?: return
            transport.writeMessage(response(message, context), send, context.roomSecret)
        } finally {
            send.close()
            recv.close()
            stream.close()
        }
    }

    private suspend fun readAuthenticatedRequest(
        recv: RecvStream,
        context: IrohServiceContext,
    ): IrohRpcMessage? = try {
        transport.readMessage(recv, context.roomSecret)
    } catch (_: Exception) {
        null
    }

    private suspend fun response(
        message: IrohRpcMessage,
        context: IrohServiceContext,
    ): IrohRpcMessage = try {
        when (message) {
            is IrohRpcMessage.Inventory -> inventoryResponse(message, context)
            is IrohRpcMessage.Operations -> operationsResponse(message, context)
            else -> throw IllegalArgumentException("Request kind is unavailable after hello")
        }
    } catch (error: Exception) {
        errorResponse(message, context, error)
    }

    private suspend fun inventoryResponse(
        message: IrohRpcMessage.Inventory,
        context: IrohServiceContext,
    ): IrohRpcMessage.InventoryResult {
        require(message.value.roomId == context.roomId) { "Wrong room" }
        val inventory = dependencies.inventory(
            context.roomId,
            message.value.after,
            message.value.limit,
        )
        return IrohRpcMessage.InventoryResult(IrohInventoryResult(
            IrohProtocolV1.Version,
            context.roomId,
            message.requestId,
            "inventoryResult",
            inventory.first,
            inventory.second,
        ))
    }

    private suspend fun operationsResponse(
        message: IrohRpcMessage.Operations,
        context: IrohServiceContext,
    ): IrohRpcMessage.OperationsResult {
        require(message.value.roomId == context.roomId) { "Wrong room" }
        return IrohRpcMessage.OperationsResult(IrohOperationsResult(
            IrohProtocolV1.Version,
            context.roomId,
            message.requestId,
            dependencies.operations(context.roomId, message.value.refs),
        ))
    }

    private fun errorResponse(
        message: IrohRpcMessage,
        context: IrohServiceContext,
        error: Exception,
    ) = IrohRpcMessage.Error(IrohErrorResponse(
        protocolVersion = IrohProtocolV1.Version,
        roomId = context.roomId,
        requestId = message.requestId,
        kind = "error",
        code = when {
            error is NoSuchElementException -> "not_found"
            error.message == "Wrong room" -> "wrong_room"
            else -> "invalid_request"
        },
        message = error.message?.take(1_024) ?: "Invalid request",
        retryable = false,
    ))
}
