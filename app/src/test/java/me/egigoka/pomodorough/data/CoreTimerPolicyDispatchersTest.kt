package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTimerPolicyDispatchersTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun completionDispatcherAcceptsFutureValidPhasePolicyWithoutRecalculation() {
        val operations = mutableListOf<String>()
        val dispatcher = CoreCompletionDispatcher { operation, _ ->
            operations += operation
            completionOutput(selectedPhase = TimerPhase.LongBreak, queueAutoBreak = true)
        }

        val decision = dispatcher.finishApplied(finishInput())

        assertEquals(listOf("timer.completionPlan.v1"), operations)
        assertEquals(TimerPhase.LongBreak, decision.selectedPhase)
        assertTrue(decision.queueAutoBreak)
    }

    @Test
    fun expiryDispatcherAcceptsAuthoritativeGeneratedPhaseWithoutNativeCounting() {
        val dispatcher = CoreCompletionDispatcher { operation, _ ->
            assertEquals("timer.completionPlan.v1", operation)
            expiryOutput(
                selectedPhase = TimerPhase.ShortBreak,
                generatedBreakPhase = TimerPhase.LongBreak,
            )
        }

        val decision = dispatcher.expiry(expiryInput())

        assertTrue(decision.expired)
        assertEquals(TimerPhase.ShortBreak, decision.selectedPhase)
        assertEquals(TimerPhase.LongBreak, decision.generatedBreakPhase)
    }

    @Test
    fun completionDispatcherRejectsContradictoryGeneratedBreakBeforeReturningDecision() {
        val dispatcher = CoreCompletionDispatcher { _, _ ->
            buildJsonObject {
                put("expired", false)
                put("commandEligible", false)
                put("reserveGeneratedBreak", false)
                put("queueAutoBreak", false)
                put("generatedBreakEligible", false)
                put("generatedBreakPhase", TimerPhase.ShortBreak)
                put("sourceAlreadyAccepted", false)
            }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.generatedBreak(generatedBreakInput())
        }
    }

    @Test
    fun completionDispatcherRejectsEligibleGeneratedBreakWithoutPhase() {
        val dispatcher = CoreCompletionDispatcher { _, _ ->
            buildJsonObject {
                put("expired", false)
                put("commandEligible", false)
                put("reserveGeneratedBreak", false)
                put("queueAutoBreak", false)
                put("generatedBreakEligible", true)
                put("sourceAlreadyAccepted", false)
            }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.generatedBreak(generatedBreakInput())
        }
    }

    @Test
    fun completionDispatcherRejectsEligibleGeneratedBreakWithoutExactSourceEvidence() {
        val dispatcher = CoreCompletionDispatcher { _, _ ->
            buildJsonObject {
                put("expired", false)
                put("commandEligible", false)
                put("reserveGeneratedBreak", false)
                put("queueAutoBreak", false)
                put("generatedBreakEligible", true)
                put("generatedBreakPhase", TimerPhase.ShortBreak)
                put("sourceAlreadyAccepted", false)
            }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.generatedBreak(generatedBreakInput())
        }
    }

    @Test
    fun completionDispatcherRejectsAcceptedSourceWithoutEligibility() {
        val dispatcher = CoreCompletionDispatcher { _, _ ->
            buildJsonObject {
                put("expired", false)
                put("commandEligible", false)
                put("reserveGeneratedBreak", false)
                put("queueAutoBreak", false)
                put("generatedBreakEligible", false)
                put("sourceAlreadyAccepted", true)
            }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.generatedBreak(generatedBreakInput())
        }
    }

    @Test
    fun completionDispatcherAcceptsExactGeneratedBreakEvidence() {
        val dispatcher = CoreCompletionDispatcher { _, _ ->
            buildJsonObject {
                put("expired", false)
                put("commandEligible", false)
                put("reserveGeneratedBreak", false)
                put("queueAutoBreak", false)
                put("generatedBreakEligible", true)
                put("generatedBreakPhase", TimerPhase.ShortBreak)
                put("sourceAlreadyAccepted", true)
            }
        }
        val completed = timer(TimerStatus.Completed, "2026-08-25T12:00:00Z", 1_500_000)
        val history = HistoryItem(
            id = "history-4",
            timerId = "timer-4",
            commandId = "finish-4",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 1_500_000,
            completedAt = "2026-08-25T12:00:00Z",
            endedAt = "2026-08-25T12:00:00Z",
        )
        val input = generatedBreakInput().copy(
            canonical = TimerProjection(completed, listOf(history)),
            optimistic = TimerProjection(completed, listOf(history)),
        )

        assertEquals(
            CoreGeneratedBreakDecision(true, TimerPhase.ShortBreak, true),
            dispatcher.generatedBreak(input),
        )
    }

    @Test
    fun hlcBatchUsesTypedCoreTicksAndAcceptsFutureCounterPolicy() {
        val inputs = mutableListOf<String>()
        val counters = ArrayDeque(listOf(40L, 80L))
        val dispatcher = CoreHlcDispatcher { operation, input ->
            assertEquals("hlc.tick.v1", operation)
            inputs += input
            buildJsonObject {
                put("wallMs", 1_000L)
                put("counter", counters.removeFirst())
            }
        }

        val clocks = dispatcher.reserve(
            physicalNowMs = 1_000L,
            retained = CoreHlc(1_000L, 3L),
            count = 2,
        )

        assertEquals(listOf(40L, 80L), clocks.map(CoreHlc::counter))
        assertTrue(inputs[1].contains("\"counter\":40"))
    }

    @Test
    fun exhaustedHlcBatchIsRejectedBeforeSharedCoreDispatch() {
        var calls = 0
        val dispatcher = CoreHlcDispatcher { _, _ ->
            calls += 1
            error("Exhausted reservation must not cross the shared-core boundary")
        }

        assertThrows(IllegalArgumentException::class.java) {
            dispatcher.reserve(
                physicalNowMs = 1_000L,
                retained = CoreHlc(1_000L, SyncWireBounds.MaxSafeInteger - 1),
                count = 2,
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun malformedSecondHlcTickRejectsWholeBatch() {
        var calls = 0
        val dispatcher = CoreHlcDispatcher { _, _ ->
            calls += 1
            if (calls == 1) {
                buildJsonObject { put("wallMs", 1_000L); put("counter", 4L) }
            } else {
                buildJsonObject { put("wallMs", 999L); put("counter", 5L) }
            }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.reserve(1_000L, CoreHlc(1_000L, 3L), count = 2)
        }
        assertEquals(2, calls)
    }

    @Test
    fun skipDestinationDispatcherAsksSharedCoreWithLocalDayInterval() {
        var captured: JsonElement? = null
        val dispatcher = CoreSkipDestinationDispatcher { operation, input ->
            assertEquals("timer.completionState.v1", operation)
            captured = json.parseToJsonElement(input)
            buildJsonObject {
                put("selection", skipSelection(TimerPhase.LongBreak))
                put("reason", "skipSelected")
                put("advances", buildJsonArray {})
            }
        }

        val phase = dispatcher.destination(skipInput())

        assertEquals(TimerPhase.LongBreak, phase)
        val wire = captured!!.jsonObject
        assertEquals("skip", wire.getValue("kind").jsonPrimitive.content)
        assertEquals(TimerPhase.Focus, wire.getValue("sourcePhase").jsonPrimitive.content)
        assertEquals(TimerPhase.Focus, wire.getValue("selection").jsonObject.getValue("phase").jsonPrimitive.content)
        assertEquals("2024-05-01T12:00:00Z", wire.getValue("referenceTime").jsonPrimitive.content)
        val interval = wire.getValue("calendarIntervals").jsonArray.single().jsonObject
        assertEquals("2024-05-01T00:00:00Z", interval.getValue("start").jsonPrimitive.content)
        assertEquals("2024-05-02T00:00:00Z", interval.getValue("end").jsonPrimitive.content)
    }

    @Test
    fun skipDestinationDispatcherRejectsUnknownPhase() {
        val dispatcher = CoreSkipDestinationDispatcher { _, _ ->
            buildJsonObject { put("selection", skipSelection("nap")) }
        }

        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            dispatcher.destination(skipInput())
        }
    }

    private fun skipSelection(phase: String) = buildJsonObject {
        put("phase", phase)
        put("generation", "0")
        put("explicit", false)
    }

    private fun skipInput() = CoreSkipDestinationInput(
        sourcePhase = TimerPhase.Focus,
        history = emptyList(),
        reference = Instant.parse("2024-05-01T12:00:00Z"),
        zoneId = ZoneId.of("UTC"),
    )

    private fun finishInput() = CoreFinishAppliedInput(
        commandId = "finish-4",
        timerId = "timer-4",
        phase = TimerPhase.Focus,
        occurredAt = "2026-08-25T12:00:00Z",
        history = emptyList(),
        autoStartBreaks = true,
        localDeviceId = "device-1",
        ownedTimerId = "timer-4",
        reference = Instant.parse("2026-08-25T12:00:00Z"),
        zoneId = ZoneId.of("UTC"),
    )

    private fun generatedBreakInput() = CoreGeneratedBreakInput(
        commandId = "finish-4",
        timerId = "timer-4",
        canonical = TimerProjection(null, emptyList()),
        optimistic = TimerProjection(null, emptyList()),
        sourceFinishPending = false,
        requireCanonical = true,
        reference = Instant.parse("2026-08-25T12:00:00Z"),
        zoneId = ZoneId.of("UTC"),
    )

    private fun expiryInput() = CoreExpiryInput(
        beforeTimer = timer(TimerStatus.Running, "2026-08-25T11:35:00Z", 0),
        projectedTimer = timer(TimerStatus.Completed, "2026-08-25T12:00:00Z", 1_500_000),
        history = emptyList(),
        selectedPhase = TimerPhase.Focus,
        autoStartBreaks = true,
        localDeviceId = "device-1",
        ownedTimerId = "timer-4",
        reference = Instant.parse("2026-08-25T12:00:00Z"),
        zoneId = ZoneId.of("UTC"),
    )

    private fun timer(status: String, anchorAt: String, elapsedMs: Long) = CanonicalTimer(
        id = "timer-4",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = elapsedMs,
        anchorAt = anchorAt,
    )

    private fun completionOutput(
        selectedPhase: String,
        queueAutoBreak: Boolean,
    ): JsonElement = buildJsonObject {
        put("expired", false)
        put("commandEligible", false)
        put("reserveGeneratedBreak", false)
        put("selectedPhase", selectedPhase)
        put("queueAutoBreak", queueAutoBreak)
        put("generatedBreakEligible", false)
        put("sourceAlreadyAccepted", false)
    }

    private fun expiryOutput(
        selectedPhase: String,
        generatedBreakPhase: String,
    ): JsonElement = buildJsonObject {
        put("expired", true)
        put("commandEligible", false)
        put("reserveGeneratedBreak", false)
        put("selectedPhase", selectedPhase)
        put("queueAutoBreak", false)
        put("generatedBreakEligible", false)
        put("generatedBreakPhase", generatedBreakPhase)
        put("sourceAlreadyAccepted", false)
    }
}
