package me.egigoka.pomodorough.data.iroh

import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohRoomTransactionsDao
import me.egigoka.pomodorough.data.local.loadPeersBounded

internal class IrohPeerRegistryPersistence(
    private val dao: IrohRoomTransactionsDao,
) {
    fun joinedRoomPeer(invite: IrohRoomInvite, endpointId: String): IrohPeerEntity {
        require(IrohProtocolV1.isIdentifier(endpointId)) { "Endpoint ID is invalid" }
        return IrohPeerEntity(
            roomId = invite.roomId,
            endpointId = endpointId,
            endpointTicket = invite.endpointTicket,
            deviceId = null,
            displayName = null,
            lastSeenAtMs = null,
        )
    }

    suspend fun prepareJoinedRoom(room: IrohRoomEntity, peer: IrohPeerEntity) {
        dao.prepareJoinedIrohRoom(room, peer)
    }

    suspend fun prepareExistingJoinedRoom(room: IrohRoomEntity, peer: IrohPeerEntity) {
        dao.prepareExistingJoinedIrohRoom(room, peer)
    }

    suspend fun upsertPeer(peer: IrohPeerEntity) {
        require(peer.endpointTicket.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes &&
            IrohProtocolV1.isDisplayName(peer.displayName)
        )
        dao.upsertIrohPeerBounded(peer, IrohProtocolV1.MaxPeers)
    }

    suspend fun peers(roomId: String): List<IrohPeerEntity> = dao.loadPeersBounded(roomId)
}
