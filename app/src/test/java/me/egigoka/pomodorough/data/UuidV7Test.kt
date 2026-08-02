package me.egigoka.pomodorough.data

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UuidV7Test {
    @Test
    fun portableFixtureMatchesRfc9562AndSharedHash() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("uuidv7-v1.json")) {
            "uuidv7-v1.json is missing"
        }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        assertEquals(
            "719bf4601f0e82aa9898e891184edcf8f37b183a05f3ddd6fa211e1ac8dc2f10",
            digest,
        )
        val fixture = Json.decodeFromString<UuidV7Fixture>(bytes.decodeToString())
        val entropy = byteArrayOf(
            0x0c,
            0xc3.toByte(),
            0x18,
            0xc4.toByte(),
            0xdc.toByte(),
            0x0c,
            0x0c,
            0x07,
            0x39,
            0x8f.toByte(),
        )

        val generated = UuidV7.reserve(
            timestampMs = fixture.rfc9562.timestampMs,
            previous = null,
            entropy = { entropy },
        ).single()
        val parts = UuidV7.parts(generated)

        assertEquals(1, fixture.schemaVersion)
        assertEquals("330d8c4dc0c0c07398f", fixture.rfc9562.randomValueHex)
        assertEquals(UUID.fromString(fixture.rfc9562.uuid), generated)
        assertEquals(
            UuidV7.Parts(
                timestampMs = 1_645_557_742_000,
                randomHigh = 0x0cc3,
                randomLow = 0x18c4_dc0c_0c07_398f,
            ),
            parts,
        )
        assertEquals(
            generated,
            UuidV7.make(parts.timestampMs, parts.randomHigh, parts.randomLow),
        )
    }

    @Test
    fun equalAndRolledBackTimestampsIncrementCompleteTail() {
        val batch = UuidV7.reserve(
            timestampMs = 2_000,
            count = 2,
            previous = null,
            entropy = { ByteArray(10) },
        )
        val afterRollback = UuidV7.reserve(
            timestampMs = 1_000,
            previous = batch.last(),
            entropy = { error("Rollback path must not draw entropy") },
        ).single()

        assertTrue(UuidV7.compare(batch[0], batch[1]) < 0)
        assertTrue(UuidV7.compare(batch[1], afterRollback) < 0)
        assertEquals(2_000, UuidV7.parts(afterRollback).timestampMs)
        assertEquals(2, UuidV7.parts(afterRollback).randomLow)
    }

    @Test
    fun randomTailCarriesFromLowBitsIntoHighBits() {
        val previous = UuidV7.make(
            timestampMs = 1_000,
            randomHigh = 7,
            randomLow = UuidV7.MaxRandomLow,
        )

        val next = UuidV7.reserve(1_000, previous = previous).single()

        assertEquals(
            UuidV7.Parts(timestampMs = 1_000, randomHigh = 8, randomLow = 0),
            UuidV7.parts(next),
        )
    }

    @Test
    fun invalidCursorTimestampAndExhaustedTailFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            UuidV7.parse(UUID.randomUUID().toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuidV7.reserve(UuidV7.MaxTimestampMs + 1, previous = null)
        }
        val exhausted = UuidV7.make(
            timestampMs = UuidV7.MaxTimestampMs,
            randomHigh = UuidV7.MaxRandomHigh,
            randomLow = UuidV7.MaxRandomLow,
        )
        assertThrows(IllegalArgumentException::class.java) {
            UuidV7.reserve(1, previous = exhausted)
        }
    }

    @Test
    fun payloadAcceptsMixedLegacyAndPrefixedUuidV7Identifiers() {
        val uuid = UuidV7.make(timestampMs = 1_000, randomHigh = 2, randomLow = 3)

        assertEquals(uuid, UuidV7.payload(uuid.toString()))
        assertEquals(uuid, UuidV7.payload("command-$uuid"))
        assertEquals(uuid, UuidV7.payload("task-operation-$uuid"))
        assertEquals(uuid, UuidV7.payload("duration-operation-$uuid"))
        assertNull(UuidV7.payload(UUID.randomUUID().toString()))
        assertNull(UuidV7.payload("legacy-opaque-id"))
        assertNotNull(UuidV7.payload(uuid.toString().uppercase()))
    }
}

@Serializable
private data class UuidV7Fixture(
    val schemaVersion: Int,
    val rfc9562: Rfc9562Fixture,
)

@Serializable
private data class Rfc9562Fixture(
    val timestampMs: Long,
    val randomValueHex: String,
    val uuid: String,
)
