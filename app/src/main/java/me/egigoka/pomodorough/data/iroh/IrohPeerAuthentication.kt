package me.egigoka.pomodorough.data.iroh

import computer.iroh.Connection
import kotlinx.coroutines.withTimeout
import me.egigoka.pomodorough.data.local.IrohPeerEntity

internal class IrohPeerAuthentication(
    private val transport: IrohEndpointTransport,
    private val authorization: IrohPeerAuthorization,
    private val endpointTicket: () -> String?,
    private val rememberPeer: suspend (IrohPeerEntity) -> Unit,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun authenticateIncoming(
        connection: Connection,
        context: IrohServiceContext,
    ): Boolean = withTimeout(10_000L) {
        val stream = connection.acceptBi()
        val recv = stream.recv()
        val send = stream.send()
        val message = try {
            transport.readMessage(
                recv,
                context.roomSecret,
                IrohProtocolV1.MaxHelloBodyBytes,
            )
        } finally {
            recv.close()
        }
        val hello = (message as? IrohRpcMessage.Hello)?.value
        if (hello == null) {
            transport.closeConnection(connection, "hello required")
            return@withTimeout false
        }
        val remoteId = connection.remoteId().use(Any::toString)
        authorization.authorize(hello, context, remoteId)
        remember(hello, context, remoteId)
        try {
            transport.writeMessage(localHello(context, hello.requestId), send, context.roomSecret)
        } finally {
            send.close()
            stream.close()
        }
        true
    }

    suspend fun performHello(connection: Connection, context: IrohServiceContext) {
        val requestId = IrohProtocolV1.requestId()
        val response = transport.request(
            localHello(context, requestId),
            connection,
            context.roomSecret,
            maxResponseBodyBytes = IrohProtocolV1.MaxHelloBodyBytes,
        )
        val hello = (response as? IrohRpcMessage.Hello)?.value
            ?: throw IllegalArgumentException("Peer did not return hello")
        require(hello.requestId == requestId)
        val remoteId = connection.remoteId().use(Any::toString)
        authorization.authorize(hello, context, remoteId)
        remember(hello, context, remoteId)
    }

    private fun localHello(context: IrohServiceContext, requestId: String): IrohRpcMessage.Hello {
        return authorization.localHello(context, requestId, checkNotNull(endpointTicket()))
    }

    private suspend fun remember(
        hello: IrohHello,
        context: IrohServiceContext,
        remoteId: String,
    ) {
        rememberPeer(IrohPeerEntity(
            roomId = context.roomId,
            endpointId = remoteId,
            endpointTicket = hello.endpointTicket,
            deviceId = hello.deviceId,
            displayName = hello.displayName,
            lastSeenAtMs = currentTimeMillis(),
        ))
    }
}
