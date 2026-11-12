package me.egigoka.pomodorough.data.iroh.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * UX round 2, batch C: the Iroh genesis history codec carries `elapsedMs`.
 * Android, Apple and Desktop decode genesis rows with exact field sets, so a
 * row with the field round-trips, a row without it still decodes, and an
 * explicit null or a value outside 0..plannedDurationMs fails closed.
 *
 * The records are built from JSON so that the test names no new model field.
 */
class IrohHistoryElapsedCodecTest {
    @Test
    fun genesisHistoryRoundTripsElapsedThroughTheStrictCodec() {
        val record = IrohOperationRecord.fromJson(genesisRecord("\"elapsedMs\":1200000"))
        val wire = IrohOperationRecord.genesis(record.deviceId, record.decodeOperation<IrohGenesis>()).toJson()

        assertEquals(1, wire.history().size)
        assertEquals("1200000", wire.history().single()["elapsedMs"].toString())
        assertEquals(record, IrohOperationRecord.fromJson(wire))
    }

    @Test
    fun genesisHistoryWithoutElapsedDecodesAndEncodesWithoutIt() {
        val record = IrohOperationRecord.fromJson(genesisRecord(null))
        val wire = IrohOperationRecord.genesis(record.deviceId, record.decodeOperation<IrohGenesis>()).toJson()

        assertEquals(false, "elapsedMs" in wire.history().single())
        assertEquals(record, IrohOperationRecord.fromJson(wire))
    }

    @Test
    fun genesisHistoryElapsedOutsideThePlanFailsClosed() {
        listOf("-1", "1500001").forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                IrohOperationRecord.fromJson(genesisRecord("\"elapsedMs\":$value"))
            }
            assertEquals(value, "Genesis history is invalid", error.message)
        }
    }

    @Test
    fun genesisHistoryExplicitNullElapsedFailsClosed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            IrohOperationRecord.fromJson(genesisRecord("\"elapsedMs\":null"))
        }
        assertEquals("Optional JSON fields must be omitted instead of null", error.message)
    }

    private fun JsonObject.history() = getValue("operation").jsonObject.getValue("history").jsonArray
        .map { it.jsonObject }

    private fun genesisRecord(elapsedField: String?): JsonObject {
        val elapsed = elapsedField?.let { ",$it" }.orEmpty()
        val history = "{\"id\":\"history-0001\",\"timerId\":\"timer-0001\",\"commandId\":\"finish-0001\"," +
            "\"phase\":\"focus\",\"status\":\"completed\",\"plannedDurationMs\":1500000," +
            "\"completedAt\":\"2026-01-01T00:20:00Z\",\"endedAt\":\"2026-01-01T00:20:00Z\"$elapsed}"
        val operation = "{\"canonicalTimer\":null,\"history\":[$history],\"tasks\":[]," +
            "\"durationsMs\":{\"focus\":1500000,\"short_break\":300000,\"long_break\":900000}," +
            "\"autoStartBreaks\":false,\"selectedTaskId\":null," +
            "\"hlcWallMs\":1767225600000,\"hlcCounter\":0}"
        return Json.parseToJsonElement(
            "{\"domain\":\"genesis\",\"deviceId\":\"device-0001\",\"operation\":$operation}",
        ).jsonObject
    }
}
