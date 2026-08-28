package me.egigoka.pomodorough.data.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IrohPeerAuthorizationTest {
    private val requestId = IrohProtocolV1.requestId()
    private val context = IrohServiceContext(
        roomId = "room-valid01",
        roomSecret = ByteArray(32),
        deviceId = "device-local01",
        displayName = null,
    )
    private val authorization = IrohPeerAuthorization(
        IrohEndpointTicketIdentity { ticket ->
            if (ticket == "ticket-valid") "endpoint-valid" else "endpoint-other"
        },
    )

    @Test
    fun wrongRoomFailsBeforeTicketAuthorization() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            authorization.authorize(hello(roomId = "room-other01"), context, "endpoint-valid")
        }

        assertEquals("Peer hello is invalid", error.message)
    }

    @Test
    fun ticketForDifferentEndpointIsRejectedWithExactError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            authorization.authorize(hello(endpointTicket = "ticket-other"), context, "endpoint-valid")
        }

        assertEquals("Peer ticket does not match authenticated endpoint", error.message)
    }

    @Test
    fun authorizedHelloPreservesLocalResponseFields() {
        val response = authorization.localHello(context, requestId, "ticket-local").value

        assertEquals(context.roomId, response.roomId)
        assertEquals(context.deviceId, response.deviceId)
        assertEquals(requestId, response.requestId)
        assertEquals("ticket-local", response.endpointTicket)
        assertEquals("android", response.platform)
    }

    private fun hello(
        roomId: String = context.roomId,
        endpointTicket: String = "ticket-valid",
    ) = IrohHello(
        protocolVersion = IrohProtocolV1.Version,
        roomId = roomId,
        requestId = requestId,
        kind = "hello",
        deviceId = "device-remote01",
        endpointTicket = endpointTicket,
        platform = "android",
        displayName = null,
    )
}
