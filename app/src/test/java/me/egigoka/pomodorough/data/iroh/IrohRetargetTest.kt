package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.iroh.protocol.IrohDomain
import me.egigoka.pomodorough.data.iroh.protocol.IrohJson
import me.egigoka.pomodorough.data.iroh.protocol.IrohMessageCodec
import me.egigoka.pomodorough.data.iroh.protocol.IrohOperationRecord
import me.egigoka.pomodorough.data.iroh.protocol.IrohRetargetCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRetargetTest {
    @Test
    fun retargetAssignAdvertisesCapability() {
        assertEquals("timer-retarget-v1", IrohRetargetCapability.RetargetV1)
        assertEquals(listOf("timer-retarget-v1"), IrohRetargetCapability.advertised())
    }

    @Test
    fun retargetAssignRoundTripsAsNewRecord() {
        val record = IrohOperationRecord.timer(DeviceId, retarget(TaskB))
        record.validate()
        val decoded = IrohOperationRecord.fromJson(record.toJson())
        assertEquals(TaskB, decoded.decodeOperation<TimerCommand>().taskId)
        assertEquals(record.digest(), decoded.digest())
    }

    @Test
    fun retargetExplicitNullUnassignRoundTrips() {
        val record = IrohOperationRecord.timer(DeviceId, retarget(null))
        record.validate()
        assertTrue(record.toJson()["operation"] is JsonObject)
        val operation = record.toJson()["operation"] as JsonObject
        assertTrue(operation.containsKey("taskId"))
        assertTrue(operation["taskId"] is JsonNull)
        val decoded = IrohOperationRecord.fromJson(record.toJson())
        assertEquals(null, decoded.decodeOperation<TimerCommand>().taskId)
    }

    @Test
    fun retargetOmissionIsRejected() {
        val operation = buildJsonObject {
            put("id", "retarget-test0001")
            put("deviceSequence", 2L)
            put("timerId", "timer-test0001")
            put("type", CommandType.Retarget)
            put("phase", TimerPhase.Focus)
            put("plannedDurationMs", 1_500_000L)
            put("occurredAt", At)
            put("hlcWallMs", WallMs)
            put("hlcCounter", 0L)
            put("observedElapsedMs", 100_000L)
        }
        val envelope = buildJsonObject {
            put("domain", IrohDomain.timer.name)
            put("deviceId", DeviceId)
            put("operation", operation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IrohOperationRecord.fromJson(envelope)
        }
    }

    @Test
    fun retargetEmptyTaskIsRejected() {
        val record = IrohOperationRecord.timer(DeviceId, retarget(""))
        assertThrows(IllegalArgumentException::class.java) { record.validate() }
    }

    @Test
    fun retargetNonFocusPhaseIsRejected() {
        val record = IrohOperationRecord.timer(DeviceId, retarget(TaskB).copy(phase = TimerPhase.ShortBreak))
        assertThrows(IllegalArgumentException::class.java) { record.validate() }
    }

    @Test
    fun digestChangesWithTaskAssignment() {
        val assigned = IrohOperationRecord.timer(DeviceId, retarget(TaskB))
        val unassigned = IrohOperationRecord.timer(DeviceId, retarget(null).copy(id = "retarget-2"))
        assertNotEquals(assigned.digest(), unassigned.digest())
    }

    @Test
    fun sameIdSamePayloadKeepsDigest() {
        val first = IrohOperationRecord.timer(DeviceId, retarget(TaskB))
        val second = IrohOperationRecord.timer(DeviceId, retarget(TaskB))
        assertEquals(first.digest(), second.digest())
    }

    @Test
    fun sameIdDifferentPayloadChangesDigest() {
        val stored = IrohOperationRecord.timer(DeviceId, retarget(TaskB))
        val rewritten = IrohOperationRecord.timer(DeviceId, retarget(TaskB).copy(taskId = TaskC))
        assertNotEquals(stored.digest(), rewritten.digest())
    }

    @Test
    fun capabilityGatePassesForAdvertisedPeer() {
        val authorization = IrohPeerAuthorization { "endpoint-1" }
        authorization.requireRetargetSupport(hello(listOf(IrohRetargetCapability.RetargetV1)))
    }

    @Test
    fun capabilityGateFailsClosedForLegacyPeer() {
        val authorization = IrohPeerAuthorization { "endpoint-1" }
        assertThrows(IllegalArgumentException::class.java) {
            authorization.requireRetargetSupport(hello(emptyList()))
        }
    }

    @Test
    fun unknownCapabilityIsRejectedFailClosed() {
        val roomId = IrohProtocolV1.roomId(ByteArray(32) { it.toByte() })
        val requestId = IrohProtocolV1.requestId(1_000_000)
        val envelope = """{"protocolVersion":1,"roomId":"$roomId","requestId":"$requestId","kind":"hello","deviceId":"$DeviceId","endpointTicket":"endpoint-ticket","platform":"android","capabilities":["future-unknown-v9"]}"""
        assertThrows(IllegalArgumentException::class.java) {
            IrohMessageCodec.decode(envelope.encodeToByteArray())
        }
    }

    private fun hello(capabilities: List<String>) = IrohHello(
        protocolVersion = 1,
        roomId = "room-test0001",
        requestId = "request-test0001",
        kind = "hello",
        deviceId = DeviceId,
        endpointTicket = "ticket-test0001",
        platform = "android",
        displayName = null,
        capabilities = capabilities,
    )

    private fun retarget(taskId: String?) = TimerCommand(
        id = "retarget-test0001",
        deviceSequence = 2,
        timerId = "timer-test0001",
        type = CommandType.Retarget,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 0,
        observedElapsedMs = 100_000,
        taskId = taskId,
    )

    private companion object {
        const val DeviceId = "device-test0001"
        const val At = "2026-01-01T00:00:01Z"
        const val WallMs = 1_767_225_601_000L
        const val TaskB = "aaf83054-24b2-8c0e-901f-a974147bfe82"
        const val TaskC = "baf83054-24b2-8c0e-901f-a974147bfe83"
    }
}
