package me.egigoka.pomodorough.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * R55-A03: a malformed canonical pair fails closed the same way a malformed
 * serverHlc* does today (SyncProtocolException from validation, or a decoding
 * failure for a non-integer), on every path that validates a sync result.
 */
class R55A03CanonicalHeadValidationTest {
    // The application's API decoder (PomodoroughApplication).
    private val apiJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun aPairAtOrBelowTheServerClockIsAccepted() {
        for (pair in listOf(ServerWall to ServerCounter, ServerWall to 0L, 1L to 99L, 0L to 0L)) {
            val response = response(pair.first, pair.second)
            TimerSyncValidation.validateCanonicalResponse(response, "Sync")
            assertEquals(pair, response.canonicalHead())
        }
    }

    @Test
    fun aResponseWithoutThePairUsesTheServerClock() {
        val response = response(null, null)

        TimerSyncValidation.validateCanonicalResponse(response, "Sync")
        assertEquals(ServerWall to ServerCounter, response.canonicalHead())
    }

    @Test
    fun onlyOneFieldIsRejected() {
        assertRejected(response(ServerWall - 1, null), "Sync returned an incomplete canonical head")
        assertRejected(response(null, 0L), "Sync returned an incomplete canonical head")
    }

    @Test
    fun aPairAheadOfTheServerClockIsRejected() {
        val ahead = "Bootstrap returned a canonical head ahead of the server clock"
        assertRejected(response(ServerWall + 1, 0L), ahead, "Bootstrap")
        assertRejected(response(ServerWall, ServerCounter + 1), ahead, "Bootstrap")
    }

    @Test
    fun negativeAndUnsafeValuesAreRejected() {
        val invalid = "Sync returned an invalid canonical head"
        assertRejected(response(-1L, 0L), invalid)
        assertRejected(response(0L, -1L), invalid)
        assertRejected(response(SyncWireBounds.MaxSafeInteger + 1, 0L), invalid)
        assertRejected(response(0L, SyncWireBounds.MaxSafeInteger + 1), invalid)
    }

    @Test
    fun theLargestSafeIntegerIsAcceptedInEitherField() {
        val max = SyncWireBounds.MaxSafeInteger
        for (pair in listOf(max to 0L, 0L to max, max to max)) {
            val response = response(pair.first, pair.second, server = max to max)
            TimerSyncValidation.validateCanonicalResponse(response, "Sync")
            assertEquals(pair, response.canonicalHead())
        }
    }

    // The server clock sits at the bound, so only the range check can reject.
    @Test
    fun oneAboveTheLargestSafeIntegerIsRejectedInEitherField() {
        val max = SyncWireBounds.MaxSafeInteger
        val invalid = "Sync returned an invalid canonical head"
        assertRejected(response(max + 1, 0L, server = max to max), invalid)
        assertRejected(response(0L, max + 1, server = max to max), invalid)
    }

    @Test
    fun anExplicitNullInOneFieldFailsDecodingLikeALoneField() {
        for (field in listOf("canonicalHlcWallMs", "canonicalHlcCounter")) {
            for (clock in listOf(
                """"$field": null, "${other(field)}": 0, "serverHlcWallMs": $ServerWall""",
                """"$field": null, "serverHlcWallMs": $ServerWall""",
            )) {
                val error = assertThrows(SerializationException::class.java) { decode(clock) }
                assertEquals(NullFieldError, error.message)
            }
        }
    }

    @Test
    fun anExplicitNullInBothFieldsFailsDecodingInsteadOfFallingBack() {
        val error = assertThrows(SerializationException::class.java) {
            decode(""""canonicalHlcWallMs": null, "canonicalHlcCounter": null, "serverHlcWallMs": $ServerWall""")
        }

        assertEquals(NullFieldError, error.message)
    }

    @Test
    fun onlyMissingKeysMeanAnOlderServer() {
        val decoded = decode(""""serverHlcWallMs": $ServerWall""")

        TimerSyncValidation.validateCanonicalResponse(decoded, "Sync")
        assertEquals(null to null, decoded.canonicalHlcWallMs to decoded.canonicalHlcCounter)
        assertEquals(ServerWall to ServerCounter, decoded.canonicalHead())
    }

    @Test
    fun aNonIntegerFailsDecodingLikeANonIntegerServerClock() {
        assertThrows(SerializationException::class.java) { decode(""""serverHlcWallMs": 1.5""") }
        for (field in listOf("canonicalHlcWallMs", "canonicalHlcCounter")) {
            assertThrows(SerializationException::class.java) {
                decode(""""$field": 1.5, "${other(field)}": 0, "serverHlcWallMs": $ServerWall""")
            }
            assertThrows(SerializationException::class.java) {
                decode(""""$field": true, "${other(field)}": 0, "serverHlcWallMs": $ServerWall""")
            }
        }
    }

    @Test
    fun aDecodedPairIsValidatedLikeAConstructedOne() {
        val decoded = decode(""""canonicalHlcWallMs": 5, "canonicalHlcCounter": 1, "serverHlcWallMs": $ServerWall""")
        TimerSyncValidation.validateCanonicalResponse(decoded, "Sync")
        assertEquals(5L to 1L, decoded.canonicalHead())
        val partial = decode(""""canonicalHlcWallMs": 5, "serverHlcWallMs": $ServerWall""")
        assertRejected(partial, "Sync returned an incomplete canonical head")
    }

    private fun other(field: String) =
        if (field == "canonicalHlcWallMs") "canonicalHlcCounter" else "canonicalHlcWallMs"

    private fun assertRejected(response: SyncResponse, message: String, source: String = "Sync") {
        val error = assertThrows(SyncProtocolException::class.java) {
            TimerSyncValidation.validateCanonicalResponse(response, source)
        }
        assertEquals(message, error.message)
    }

    // `clock` holds the serverHlcWallMs entry plus any canonical fields.
    private fun decode(clock: String): SyncResponse = apiJson.decodeFromString(
        """{"acknowledgements": [], "revision": 4, "canonicalTimer": null, "history": [],
            "serverTime": "2026-07-20T00:06:00Z", $clock, "serverHlcCounter": $ServerCounter,
            "durationAcknowledgements": [], "durationsMs": {"focus": 1500000, "short_break": 300000,
            "long_break": 900000}, "taskAcknowledgements": [], "tasks": [],
            "autoStartAcknowledgements": [], "autoStartBreaks": false,
            "selectedTaskAcknowledgements": [], "selectedTaskId": null}""",
    )

    private fun response(wall: Long?, counter: Long?, server: Pair<Long, Long> = ServerWall to ServerCounter) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 4,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = "2026-07-20T00:06:00Z",
        serverHlcWallMs = server.first,
        serverHlcCounter = server.second,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = false,
        canonicalHlcWallMs = wall,
        canonicalHlcCounter = counter,
    )

    private companion object {
        const val ServerWall = 1_784_506_000_000L
        const val ServerCounter = 2L
        const val NullFieldError = "Canonical HLC head field must be an integer, not null"
    }
}
