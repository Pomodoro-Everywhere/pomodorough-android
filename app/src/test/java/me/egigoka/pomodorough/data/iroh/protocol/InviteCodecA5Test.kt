package me.egigoka.pomodorough.data.iroh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodecA5Test {
    @Test
    fun maximallyEscapedFieldsFitInsideExplicitEnvelope() {
        val payload = maximallyEscapedPayload()
        val payloadBytes = payload.encodeToByteArray()
        val encoded = Base64Url.encode(payloadBytes)

        assertEquals(99_852, payloadBytes.size)
        assertTrue(payloadBytes.size < IrohRoomInvite.MaxPayloadBytes)
        assertTrue(encoded.length < IrohRoomInvite.MaxEncodedPayloadCharacters)
        assertFalse(encoded.contains('='))

        val decoded = IrohRoomInvite.decode(IrohProtocolV1.InvitePrefix + encoded)

        assertEquals("a".repeat(IrohProtocolV1.MaxEndpointTicketBytes), decoded.endpointTicket)
        assertEquals(Rocket.repeat(64), decoded.roomName)
    }

    @Test
    fun exactEnvelopeBoundaryAndOneByteOverflowAreHandled() {
        val compact = validPayload("ticket")
        val exactPayload = compact + " ".repeat(IrohRoomInvite.MaxPayloadBytes - compact.length)
        val exactEncoded = Base64Url.encode(exactPayload.encodeToByteArray())

        assertEquals(IrohRoomInvite.MaxPayloadBytes, exactPayload.encodeToByteArray().size)
        assertEquals(IrohRoomInvite.MaxEncodedPayloadCharacters, exactEncoded.length)
        assertEquals("ticket", IrohRoomInvite.decode(IrohProtocolV1.InvitePrefix + exactEncoded).endpointTicket)

        val decodedOverflow = exactPayload + " "
        assertEquals(IrohRoomInvite.MaxPayloadBytes + 1, decodedOverflow.encodeToByteArray().size)
        assertSizeRejected(IrohProtocolV1.InvitePrefix + Base64Url.encode(decodedOverflow.encodeToByteArray()))

        val encodedOverflow = "A".repeat(IrohRoomInvite.MaxEncodedPayloadCharacters + 1)
        assertSizeRejected(IrohProtocolV1.InvitePrefix + encodedOverflow)
    }

    @Test
    fun hugeEncodedInputIsRejectedBeforeBase64Validation() {
        val malicious = IrohProtocolV1.InvitePrefix + "A".repeat(2_000_000) + "!"

        assertSizeRejected(malicious)
        assertSizeRejected(
            IrohProtocolV1.InvitePrefix + Rocket.repeat(IrohRoomInvite.MaxEncodedPayloadCharacters),
        )
    }

    @Test
    fun versionRequiresCanonicalIntegerToken() {
        val noncanonicalVersions = listOf("1e0", "1E+000", "1.0", "1.00", "01", "+1")

        noncanonicalVersions.forEach { version ->
            assertVersionRejected(payloadWithVersion(version))
            assertVersionRejected(payloadWithVersion("\n  $version  \t", escapedKey = true))
        }

        val spacedCanonical = payloadWithVersion("\n  1  \t", escapedKey = true)
        assertEquals("ticket", IrohRoomInvite.decode(encodePayload(spacedCanonical)).endpointTicket)
    }

    @Test
    fun ticketLimitUsesDecodedUtf8BytesAndRoundTrips() {
        val exactTicket = Rocket.repeat(IrohProtocolV1.MaxEndpointTicketBytes / 4)
        val invite = IrohRoomInvite(RoomId, Rocket.repeat(64), exactTicket, RoomSecret.copyOf())

        assertEquals(IrohProtocolV1.MaxEndpointTicketBytes, exactTicket.encodeToByteArray().size)
        assertEquals(invite, IrohRoomInvite.decode(invite.encode()))

        val oversizedPayload = validPayload(exactTicket + "x")
        val error = assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(encodePayload(oversizedPayload))
        }
        assertEquals("Endpoint ticket exceeds 16 KiB", error.message)
    }

    @Test
    fun paddingAndNoncanonicalBase64urlRemainRejected() {
        val invite = IrohRoomInvite(RoomId, null, "ticket", RoomSecret.copyOf()).encode()
        val padded = invite + "="

        assertMalformedBase64(padded)
        assertMalformedBase64(IrohProtocolV1.InvitePrefix + "Zh")
        assertTrue(invite.removePrefix(IrohProtocolV1.InvitePrefix).matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun malformedJsonAndFieldsRemainRejected() {
        val malformedPayloads = listOf(
            "{",
            "[]",
            """{"v":1,"roomId":"$RoomId","roomSecret":"$EncodedRoomSecret"}""",
            validPayload("ticket").dropLast(1) + ",\"extra\":true}",
            validPayload("ticket").dropLast(1) + ",\"roomName\":null}",
            """{"v":1,"roomId":"$RoomId","endpointTicket":1,"roomSecret":"$EncodedRoomSecret"}""",
        )

        malformedPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                IrohRoomInvite.decode(encodePayload(payload))
            }
        }
    }

    private fun assertSizeRejected(invite: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(invite)
        }
        assertEquals("Invite payload exceeds maximum size", error.message)
    }

    private fun assertMalformedBase64(invite: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(invite)
        }
        assertEquals("Malformed base64url", error.message)
    }

    private fun assertVersionRejected(payload: String) {
        assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(encodePayload(payload))
        }
    }
}

private val RoomSecret = ByteArray(32) { it.toByte() }
private val RoomId = IrohProtocolV1.roomId(RoomSecret)
private val EncodedRoomSecret = Base64Url.encode(RoomSecret)
private const val Rocket = "\uD83D\uDE80"

private fun encodePayload(payload: String): String =
    IrohProtocolV1.InvitePrefix + Base64Url.encode(payload.encodeToByteArray())

private fun validPayload(endpointTicket: String): String =
    """{"v":1,"roomId":"$RoomId","endpointTicket":"$endpointTicket","roomSecret":"$EncodedRoomSecret"}"""

private fun payloadWithVersion(version: String, escapedKey: Boolean = false): String {
    val versionKey = if (escapedKey) "\\u0076" else "v"
    return """{"$versionKey":$version,"roomId":"$RoomId","endpointTicket":"ticket","roomSecret":"$EncodedRoomSecret"}"""
}

private fun maximallyEscapedPayload(): String {
    val escapedRoomId = escapeAscii(RoomId)
    val escapedSecret = escapeAscii(EncodedRoomSecret)
    val escapedTicket = escapeAscii("a".repeat(IrohProtocolV1.MaxEndpointTicketBytes))
    val escapedRoomName = "\\uD83D\\uDE80".repeat(64)
    return """{"${escapeAscii("v")}":1,"${escapeAscii("roomId")}":"$escapedRoomId","${escapeAscii("roomName")}":"$escapedRoomName","${escapeAscii("endpointTicket")}":"$escapedTicket","${escapeAscii("roomSecret")}":"$escapedSecret"}"""
}

private fun escapeAscii(value: String): String = buildString(value.length * 6) {
    value.forEach { character ->
        append("\\u")
        append(character.code.toString(16).padStart(4, '0'))
    }
}
