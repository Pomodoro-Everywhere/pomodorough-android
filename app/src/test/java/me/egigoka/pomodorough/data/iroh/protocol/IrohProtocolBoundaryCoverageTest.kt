package me.egigoka.pomodorough.data.iroh.protocol

import java.nio.charset.CharacterCodingException
import me.egigoka.pomodorough.data.iroh.IrohRpcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IrohProtocolBoundaryCoverageTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val roomId = IrohProtocolV1.roomId(secret)
    private val requestId = IrohProtocolV1.requestId(1_000_000L)

    @Test
    fun strictJsonAcceptsEveryValueShapeAndEscapeForm() {
        val values = listOf(
            "{}",
            "[]",
            " [ true, false, null, -12, 0, 1.25e+2 ] ",
            """{"text":"quote:\" slash:\/ reverse:\\ controls:\b\f\n\r\t"}""",
            """{"unicode":"\u0061\uD83D\uDE00","nested":{"items":[1]}}""",
        )

        values.forEach { value -> assertEquals(value, strictJson(value.encodeToByteArray())) }
    }

    @Test
    fun strictJsonRejectsAmbiguousOrMalformedDocuments() {
        val malformed = listOf(
            "",
            "true false",
            "tru",
            "[1,]",
            "{\"a\":1,}",
            "{\"a\":1,\"a\":2}",
            "{\"é\":1,\"é\":2}",
            "01",
            "-",
            "1.",
            "1e",
            "1e+",
            "\"unterminated",
            "\"\\x\"",
            "\"\\u12xz\"",
            "\"\\uD800x\"",
            "\"\\uD800\\u0041\"",
            "\"\\uDC00\"",
            "\"${'\u0001'}\"",
        )

        malformed.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                strictJson(value.encodeToByteArray())
            }
        }
        assertThrows(CharacterCodingException::class.java) {
            strictJson(byteArrayOf(0xc3.toByte(), 0x28))
        }
    }

    @Test
    fun codecRoundTripsEverySimpleRpcMessageKind() {
        val messages = listOf(
            IrohRpcMessage.Hello(
                IrohHello(1, roomId, requestId, "hello", "device-test0001", "ticket", "android", "Desk"),
            ),
            IrohRpcMessage.InventoryResult(
                IrohInventoryResult(1, roomId, requestId, "inventoryResult", emptyList(), null),
            ),
            IrohRpcMessage.Operations(
                IrohOperationsRequest(
                    1,
                    roomId,
                    requestId,
                    "operations",
                    listOf(IrohInventoryReference(IrohDomain.genesis, "genesis")),
                ),
            ),
            IrohRpcMessage.Error(
                IrohErrorResponse(1, roomId, requestId, "error", "not_found", "missing", false),
            ),
        )

        messages.forEach { message ->
            assertEquals(message, IrohMessageCodec.decode(IrohMessageCodec.encode(message)))
        }
    }

    @Test
    fun codecRejectsSemanticBoundaryViolationsAfterJsonParsing() {
        val validDigest = Base64Url.encode(ByteArray(32))
        val invalidBodies = listOf(
            message("hello", """"deviceId":"device-test0001","endpointTicket":"ticket","platform":"plan9"""),
            message("inventory", """"after":"bad-cursor","limit":1"""),
            message(
                "inventoryResult",
                """"entries":[{"domain":"task","id":"task-b","digest":"$validDigest"},{"domain":"task","id":"task-a","digest":"$validDigest"}],"next":null""",
            ),
            message("operations", """"refs":[]"""),
            message("operations", """"refs":[{"domain":"genesis","id":"not-genesis"}]"""),
            message("error", """"code":"unknown","message":"bad","retryable":false"""),
        )

        invalidBodies.forEach { body ->
            assertThrows(IllegalArgumentException::class.java) {
                IrohMessageCodec.decode(body.encodeToByteArray())
            }
        }
    }

    @Test
    fun codecRejectsWrongShapesBeforeSemanticValidation() {
        val invalidBodies = listOf(
            message("inventoryResult", """"entries":{},"next":null"""),
            message("operations", """"refs":{}"""),
            message("operationsResult", """"records":{}"""),
            message("inventory", """"after":null,"limit":1,"unknown":true"""),
        )

        invalidBodies.forEach { body ->
            assertThrows(Exception::class.java) {
                IrohMessageCodec.decode(body.encodeToByteArray())
            }
        }
    }

    @Test
    fun base64AndFrameCodecsRejectNonCanonicalOrTamperedInput() {
        listOf("", "a", "AA==", "A+", "A/").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Base64Url.decode(value) }
        }
        val frame = IrohFrameCodec.encode("body".encodeToByteArray(), secret)
        val tampered = frame.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { IrohFrameCodec.decode(tampered, secret) }
        assertThrows(IllegalArgumentException::class.java) { IrohFrameCodec.decode(frame, ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { IrohFrameCodec.decode(frame + 0, secret) }
    }

    private fun message(kind: String, fields: String): String =
        """{"protocolVersion":1,"roomId":"$roomId","requestId":"$requestId","kind":"$kind",$fields}"""
}
