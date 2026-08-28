package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.withTimeout

internal class IrohRoomJoinTransport(
    private val sessions: IrohEndpointSessionSource,
    private val transport: IrohEndpointTransport,
    private val authentication: IrohPeerAuthentication,
    private val synchronization: IrohPeerSynchronization,
    private val activateRoom: suspend (String) -> IrohRoomProjection,
    private val onProjection: suspend () -> Unit,
) {
    suspend fun join(invite: IrohRoomInvite): IrohRoomProjection {
        val session = sessions.session()
            ?: throw IllegalStateException("Iroh endpoint is not running")
        val context = session.context.takeIf { it.roomId == invite.roomId }
            ?: throw IllegalStateException("Iroh endpoint is running for another room")
        return withTimeout(45_000L) {
            transport.withParsedTicket(invite.endpointTicket) { expectedId, ticket ->
                require(expectedId == ticket.endpointAddr().use { it.id().use(Any::toString) }) {
                    "Invite endpoint identity changed"
                }
                val connection = ticket.endpointAddr().use { address ->
                    session.endpoint.connect(address, IrohProtocolV1.Alpn)
                }
                try {
                    require(connection.remoteId().use(Any::toString) == expectedId) {
                        "Connected endpoint does not match invite ticket"
                    }
                    authentication.performHello(connection, context)
                    synchronization.pullWhileServing(connection, context, sessions.generation())
                    activateRoom(invite.roomId).also { onProjection() }
                } finally {
                    transport.closeConnection(connection, "join complete")
                }
            }
        }
    }
}
