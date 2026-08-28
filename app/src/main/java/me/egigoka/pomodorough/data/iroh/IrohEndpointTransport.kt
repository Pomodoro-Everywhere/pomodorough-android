package me.egigoka.pomodorough.data.iroh

import computer.iroh.Connection
import computer.iroh.EndpointTicket
import computer.iroh.RecvStream
import computer.iroh.SendStream
import java.nio.ByteBuffer
import kotlinx.coroutines.withTimeout

internal class IrohEndpointTransport {
    suspend fun request(
        message: IrohRpcMessage,
        connection: Connection,
        secret: ByteArray,
        maxResponseBodyBytes: Int = IrohProtocolV1.MaxFrameBodyBytes,
    ): IrohRpcMessage = withTimeout(30_000L) {
        val stream = connection.openBi()
        val send = stream.send()
        val recv = stream.recv()
        try {
            writeMessage(message, send, secret)
            readMessage(recv, secret, maxResponseBodyBytes).also { response ->
                require(response.requestId == message.requestId) {
                    "Response request ID does not match"
                }
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

    suspend fun readMessage(
        stream: RecvStream,
        secret: ByteArray,
        maxBodyBytes: Int = IrohProtocolV1.MaxFrameBodyBytes,
    ): IrohRpcMessage = withTimeout(30_000L) {
        val header = stream.readExact(4u)
        val bodyLength = ByteBuffer.wrap(header).int
        require(bodyLength in 0..maxBodyBytes) { "Invalid Iroh frame length" }
        val mac = stream.readExact(32u)
        val body = stream.readExact(bodyLength.toUInt())
        require(stream.read(1u).isEmpty()) { "Iroh stream contains trailing bytes" }
        IrohMessageCodec.decode(IrohFrameCodec.decode(header + mac + body, secret))
    }

    suspend fun writeMessage(
        message: IrohRpcMessage,
        stream: SendStream,
        secret: ByteArray,
    ) {
        stream.writeAll(IrohFrameCodec.encode(IrohMessageCodec.encode(message), secret))
        stream.finish()
    }

    fun endpointIdForTicket(value: String): String {
        var result: String? = null
        withParsedTicketBlocking(value) { result = it }
        return checkNotNull(result)
    }

    suspend fun <T> withParsedTicket(
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

    fun closeConnection(connection: Connection, reason: String) {
        runCatching { connection.close(0, reason.encodeToByteArray()) }
        connection.close()
    }

    private fun withParsedTicketBlocking(value: String, block: (String) -> Unit) {
        require(value.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes)
        EndpointTicket.fromString(value).use { ticket ->
            block(ticket.endpointAddr().use { it.id().use(Any::toString) })
        }
    }
}
