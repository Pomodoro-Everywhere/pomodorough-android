package me.egigoka.pomodorough.data.iroh

internal fun interface IrohEndpointTicketIdentity {
    fun endpointId(ticket: String): String
}

internal class IrohPeerAuthorization(
    private val ticketIdentity: IrohEndpointTicketIdentity,
) {
    fun authorize(
        hello: IrohHello,
        context: IrohServiceContext,
        remoteId: String,
    ) {
        require(
            hello.protocolVersion == IrohProtocolV1.Version &&
                hello.kind == "hello" &&
                hello.roomId == context.roomId &&
                IrohProtocolV1.isRequestId(hello.requestId) &&
                IrohProtocolV1.isIdentifier(hello.deviceId) &&
                IrohProtocolV1.isDisplayName(hello.displayName),
        ) { "Peer hello is invalid" }
        require(ticketIdentity.endpointId(hello.endpointTicket) == remoteId) {
            "Peer ticket does not match authenticated endpoint"
        }
    }

    fun localHello(
        context: IrohServiceContext,
        requestId: String,
        endpointTicket: String,
    ) = IrohRpcMessage.Hello(
        IrohHello(
            protocolVersion = IrohProtocolV1.Version,
            roomId = context.roomId,
            requestId = requestId,
            kind = "hello",
            deviceId = context.deviceId,
            endpointTicket = endpointTicket,
            platform = "android",
            displayName = context.displayName,
        ),
    )
}
