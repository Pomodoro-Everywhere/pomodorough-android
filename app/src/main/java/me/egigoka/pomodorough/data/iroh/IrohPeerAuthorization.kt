package me.egigoka.pomodorough.data.iroh

import me.egigoka.pomodorough.data.iroh.protocol.IrohRetargetCapability

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

    // Explicit per-peer retarget gate. Not yet wired into the sync hot path:
    // IrohPeerEntity persists no capabilities, so per-peer filtering would need
    // a schema change. Mixed-version rollout stays fail-closed without it:
    // old peers reject unknown retarget records in CanonicalRecordCodec.validate
    // (IllegalArgumentException, per-peer sync continues), and centralized sync
    // remains authoritative. Upgrade all peers before relying on retarget.
    // Callers that need strict gating (tests, future serve-side filtering) use this.
    fun requireRetargetSupport(hello: IrohHello) {
        require(IrohRetargetCapability.supportsRetarget(hello)) {
            "Peer does not support timer retarget"
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
            capabilities = IrohRetargetCapability.advertised(),
        ),
    )
}
