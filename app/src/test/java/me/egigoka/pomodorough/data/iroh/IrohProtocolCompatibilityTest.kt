package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IrohProtocolCompatibilityTest {
    @Test
    fun inviteEncodingPreservesExactBytesAndRoundTrip() {
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Design desk",
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret,
        )

        val encoded = invite.encode()

        assertEquals(
            "pomodorough1.eyJ2IjoxLCJyb29tSWQiOiJaX3FMdG52WlFzaS1kMkdpdzFsdmo3eXkxeDIwaHlFNGpVZ09Ea0ZzUUJzIiwicm9vbU5hbWUiOiJEZXNpZ24gZGVzayIsImVuZHBvaW50VGlja2V0IjoiZW5kcG9pbnQtdGlja2V0LXBsYWNlaG9sZGVyIiwicm9vbVNlY3JldCI6IkFBRUNBd1FGQmdjSUNRb0xEQTBPRHhBUkVoTVVGUllYR0JrYUd4d2RIaDgifQ",
            encoded,
        )
        assertEquals(invite, IrohRoomInvite.decode(encoded))
        assertArrayEquals(secret, IrohRoomInvite.decode(encoded).roomSecret)
    }

    @Test
    fun rpcEncodingPreservesExactKeysAndRequiredNullPosition() {
        val message = IrohRpcMessage.Inventory(
            IrohInventoryRequest(
                protocolVersion = 1,
                roomId = "Z_qLtnvZQsi-d2Giw1lvj7yy1x20hyE4jUgODkFsQBs",
                requestId = "0000000f-4240-7000-8000-000000000000",
                kind = "inventory",
                after = null,
                limit = 1_024,
            ),
        )

        val encoded = IrohMessageCodec.encode(message)

        assertEquals(
            """{"protocolVersion":1,"roomId":"Z_qLtnvZQsi-d2Giw1lvj7yy1x20hyE4jUgODkFsQBs","requestId":"0000000f-4240-7000-8000-000000000000","kind":"inventory","limit":1024,"after":null}""",
            encoded.decodeToString(),
        )
        assertEquals(message, IrohMessageCodec.decode(encoded))
    }

    @Test
    fun canonicalJsonPreservesOrderingAndIntegerRules() {
        val value = Json.parseToJsonElement("""{"z":2,"a":[true,null,"x"]}""")

        assertEquals(
            """{"a":[true,null,"x"],"z":2}""",
            JsonCanonicalizer.encode(value).decodeToString(),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            JsonCanonicalizer.encode(Json.parseToJsonElement("1.0"))
        }
        assertEquals("Canonical records require integer numbers", error.message)
    }

    @Test
    fun validationFailuresPreserveOrderingAndMessages() {
        val oversizedOperation = JsonObject(
            mapOf("padding" to JsonPrimitive("x".repeat(IrohProtocolV1.MaxOperationBytes))),
        )
        val invalidIdentity = IrohOperationRecord(
            domain = IrohDomain.autoStart,
            deviceId = "!",
            operation = oversizedOperation,
        )
        val validIdentity = invalidIdentity.copy(deviceId = "device-test0001")

        val identityError = assertThrows(IllegalArgumentException::class.java, invalidIdentity::validate)
        assertEquals("Origin device ID is invalid", identityError.message)
        val sizeError = assertThrows(IllegalArgumentException::class.java, validIdentity::validate)
        assertEquals("Operation exceeds 64 KiB", sizeError.message)

        val unsupportedInvite =
            """{"v":2,"roomId":"bad","endpointTicket":"ticket","roomSecret":"bad"}"""
        val inviteError = assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(
                IrohProtocolV1.InvitePrefix + Base64Url.encode(unsupportedInvite.encodeToByteArray()),
            )
        }
        assertEquals("Unsupported invite version", inviteError.message)

        val envelopeError = assertThrows(IllegalArgumentException::class.java) {
            IrohMessageCodec.decode(
                """{"protocolVersion":1,"roomId":"bad","requestId":"bad","kind":"unknown"}"""
                    .encodeToByteArray(),
            )
        }
        assertEquals("Iroh message envelope is invalid", envelopeError.message)

        val unknownKindError = assertThrows(SerializationException::class.java) {
            IrohMessageCodec.decode(
                """{"protocolVersion":1,"roomId":"Z_qLtnvZQsi-d2Giw1lvj7yy1x20hyE4jUgODkFsQBs","requestId":"0000000f-4240-7000-8000-000000000000","kind":"unknown"}"""
                    .encodeToByteArray(),
            )
        }
        assertEquals("Unknown Iroh message kind", unknownKindError.message)
    }
}
