package me.egigoka.pomodorough.data.iroh

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IrohProtocolTest {
    @Test
    fun roomIdMatchesCanonicalVector() {
        val secret = ByteArray(32) { it.toByte() }

        assertEquals(
            "Z_qLtnvZQsi-d2Giw1lvj7yy1x20hyE4jUgODkFsQBs",
            IrohProtocolV1.roomId(secret),
        )
    }

    @Test
    fun frameRoundTripAndTamperRejection() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val body = "{\"kind\":\"inventory\"}".encodeToByteArray()
        val frame = IrohFrameCodec.encode(body, secret)

        assertArrayEquals(body, IrohFrameCodec.decode(frame, secret))

        frame[frame.lastIndex] = (frame.last() + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            IrohFrameCodec.decode(frame, secret)
        }
    }

    @Test
    fun frameAndCanonicalDigestMatchCrossClientVectors() {
        val frame = IrohFrameCodec.encode(
            "{\"kind\":\"hello\"}".encodeToByteArray(),
            ByteArray(32) { it.toByte() },
        )
        assertEquals(
            "00000010d9f01510c6ce30066f8318494a013c47657387a9bc3bbb81625b3cd74569d8377b226b696e64223a2268656c6c6f227d",
            frame.joinToString("") { "%02x".format(it) },
        )

        val record = IrohOperationRecord(
            domain = IrohDomain.autoStart,
            deviceId = "device-test0001",
            operation = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("auto-start-operation-peer0001"),
                    "enabled" to JsonPrimitive(true),
                    "occurredAt" to JsonPrimitive("1970-01-01T00:16:40Z"),
                    "hlcWallMs" to JsonPrimitive(1_000_000),
                    "hlcCounter" to JsonPrimitive(0),
                ),
            ),
        )
        assertEquals("ViRTrF---kkCpXCRyxUvXbeZSas4Iyal_dtSbi4TTzE", record.digest())
    }

    @Test
    fun inviteRejectsPrefixBase64UnknownFieldAndRoomMismatch() {
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Design desk",
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret,
        ).encode()

        assertEquals(secret.toList(), IrohRoomInvite.decode(invite).roomSecret.toList())
        assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(invite.removePrefix(IrohProtocolV1.InvitePrefix))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(IrohProtocolV1.InvitePrefix + "not+base64")
        }

        val payload = String(
            Base64.getUrlDecoder().decode(invite.removePrefix(IrohProtocolV1.InvitePrefix)),
        )
        val unknown = payload.dropLast(1) + ",\"extra\":true}"
        assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(
                IrohProtocolV1.InvitePrefix + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(unknown.encodeToByteArray()),
            )
        }
        val mismatch = payload.replace(
            IrohProtocolV1.roomId(secret),
            IrohProtocolV1.roomId(ByteArray(32) { (it + 1).toByte() }),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IrohRoomInvite.decode(
                IrohProtocolV1.InvitePrefix + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mismatch.encodeToByteArray()),
            )
        }
    }

    @Test
    fun strictJsonRejectsDuplicateKeysAndUnpairedEscapedSurrogatesRecursively() {
        val secret = ByteArray(32) { it.toByte() }
        val roomId = IrohProtocolV1.roomId(secret)
        listOf(
            "{\"v\":1,\"v\":1,\"roomId\":\"$roomId\",\"endpointTicket\":\"endpoint-ticket\",\"roomSecret\":\"${Base64Url.encode(secret)}\"}",
            "{\"v\":1,\"\\u0076\":1,\"roomId\":\"$roomId\",\"endpointTicket\":\"endpoint-ticket\",\"roomSecret\":\"${Base64Url.encode(secret)}\"}",
            "{\"v\":1,\"roomId\":\"$roomId\",\"endpointTicket\":\"endpoint-ticket\",\"roomSecret\":\"${Base64Url.encode(secret)}\",\"roomName\":\"\\uD800\"}",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                IrohRoomInvite.decode(
                    IrohProtocolV1.InvitePrefix + Base64Url.encode(payload.encodeToByteArray()),
                )
            }
        }

        val validPair = "{\"v\":1,\"roomId\":\"$roomId\",\"endpointTicket\":\"endpoint-ticket\",\"roomSecret\":\"${Base64Url.encode(secret)}\",\"roomName\":\"\\uD83C\\uDFA8\"}"
        assertEquals(
            "\uD83C\uDFA8",
            IrohRoomInvite.decode(
                IrohProtocolV1.InvitePrefix + Base64Url.encode(validPair.encodeToByteArray()),
            ).roomName,
        )
    }

    @Test
    fun operationOptionalsRejectExplicitNullWhileRequiredCursorsAcceptIt() {
        val roomId = IrohProtocolV1.roomId(ByteArray(32) { it.toByte() })
        val requestId = IrohProtocolV1.requestId(1_000_000)
        val withNullTitle = """{"protocolVersion":1,"roomId":"$roomId","requestId":"$requestId","kind":"operationsResult","records":[{"domain":"task","deviceId":"device-test0001","operation":{"id":"operation-test0001","taskId":"task-test000001","type":"delete","title":null,"occurredAt":"1970-01-01T00:16:40Z","hlcWallMs":1000000,"hlcCounter":0}}]}"""

        assertThrows(IllegalArgumentException::class.java) {
            IrohMessageCodec.decode(withNullTitle.encodeToByteArray())
        }

        val inventory = """{"protocolVersion":1,"roomId":"$roomId","requestId":"$requestId","kind":"inventory","after":null,"limit":1024}"""
        assertEquals(null, (IrohMessageCodec.decode(inventory.encodeToByteArray()) as IrohRpcMessage.Inventory).value.after)
    }

    @Test
    fun helloAcceptsWindowsPeer() {
        val roomId = IrohProtocolV1.roomId(ByteArray(32) { it.toByte() })
        val requestId = IrohProtocolV1.requestId(1_000_000)
        val hello = """{"protocolVersion":1,"roomId":"$roomId","requestId":"$requestId","kind":"hello","deviceId":"device-windows01","endpointTicket":"endpoint-ticket","platform":"windows"}"""

        val decoded = IrohMessageCodec.decode(hello.encodeToByteArray()) as IrohRpcMessage.Hello

        assertEquals("windows", decoded.value.platform)
    }

    @Test
    fun displayNameRejectsUnpairedUtf16Surrogates() {
        assertEquals(false, IrohProtocolV1.isDisplayName("bad\uD800name"))
        assertEquals(false, IrohProtocolV1.isDisplayName("bad\uDC00name"))
        assertEquals(true, IrohProtocolV1.isDisplayName("Design \uD83C\uDFA8"))
    }

    @Test
    fun genesisUsesStrictCentralizedTimerIntentShape() {
        val record = IrohOperationRecord.genesis(
            "device-1",
            IrohGenesis(
                canonicalTimer = CanonicalTimer(
                    id = "timer-123",
                    phase = TimerPhase.Focus,
                    status = TimerStatus.Running,
                    plannedDurationMs = 60_000,
                    elapsedAtAnchorMs = 0,
                    anchorAt = "2026-01-01T00:00:00Z",
                    startedByDeviceId = "device-1",
                    lastIntent = TimerIntent(
                        type = "start",
                        commandId = "command-1",
                        occurredAt = "2026-01-01T00:00:00Z",
                        deviceId = "device-1",
                    ),
                ),
                history = emptyList(),
                tasks = emptyList(),
                durationsMs = me.egigoka.pomodorough.data.DurationsMs(),
                autoStartBreaks = false,
                hlcWallMs = 1,
                hlcCounter = 0,
            ),
        )

        val intent = record.operation["canonicalTimer"]!!.jsonObject["lastIntent"]!!.jsonObject
        assertEquals(setOf("type", "commandId", "occurredAt"), intent.keys)
        record.validate()
        assertEquals(record, IrohOperationRecord.fromJson(record.toJson()))
    }

    @Test
    fun replicationModesAreDistinctDeviceLocalChoices() {
        assertEquals(
            listOf("OFFLINE", "IROH", "CENTRALIZED"),
            ReplicationMode.entries.map(Enum<*>::name),
        )
    }

    @Test
    fun operationBatchLimitLeavesFrameEnvelopeCapacity() {
        assertEquals(255, IrohProtocolV1.MaxOperationReferences)
        assertEquals(32 * 1_024, IrohProtocolV1.MaxHelloBodyBytes)
        assertEquals(
            65_536,
            IrohProtocolV1.MaxFrameBodyBytes -
                IrohProtocolV1.MaxOperationReferences * IrohProtocolV1.MaxOperationBytes,
        )
    }
}
