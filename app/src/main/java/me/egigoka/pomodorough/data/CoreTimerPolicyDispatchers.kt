package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal data class CoreHlc(val wallMs: Long, val counter: Long)

@Serializable
private data class CoreHlcWire(val wallMs: Long, val counter: Long)

@Serializable
private data class CoreHlcTickInput(
    val local: CoreHlcWire,
    val remote: CoreHlcWire? = null,
    val physicalNowMs: Long,
)

internal class CoreHlcDispatcher(
    private val dispatch: (String, String) -> JsonElement,
) {
    private val wireJson = Json { explicitNulls = false }
    private val strictJson = Json { ignoreUnknownKeys = false }

    fun reserve(physicalNowMs: Long, retained: CoreHlc, count: Int): List<CoreHlc> {
        require(count > 0) { "Mutation reservation must be positive" }
        require(physicalNowMs in 1..SyncWireBounds.MaxSafeInteger) {
            "Physical occurrence time is invalid"
        }
        requireClock(retained, "Persisted hybrid clock is invalid")
        if (physicalNowMs <= retained.wallMs) {
            require(count.toLong() <= SyncWireBounds.MaxSafeInteger - retained.counter) {
                "Hybrid clock counter overflow"
            }
        }
        val clocks = ArrayList<CoreHlc>(count)
        var local = retained
        repeat(count) {
            local = tick(physicalNowMs, local)
            clocks += local
        }
        return clocks
    }

    fun tick(physicalNowMs: Long, local: CoreHlc, remote: CoreHlc? = null): CoreHlc {
        require(physicalNowMs in 1..SyncWireBounds.MaxSafeInteger) {
            "Physical occurrence time is invalid"
        }
        requireClock(local, "Persisted hybrid clock is invalid")
        remote?.let { requireClock(it, "Remote hybrid clock is invalid") }
        val input = CoreHlcTickInput(
            local = CoreHlcWire(local.wallMs, local.counter),
            remote = remote?.let { CoreHlcWire(it.wallMs, it.counter) },
            physicalNowMs = physicalNowMs,
        )
        val output = try {
            strictJson.decodeFromJsonElement(
                CoreHlcWire.serializer(),
                dispatch(Operation, wireJson.encodeToString(input)),
            )
        } catch (error: CoreProjectionException) {
            throw error
        } catch (error: Exception) {
            throw invalidOutput(error)
        }
        val result = CoreHlc(output.wallMs, output.counter)
        requireClock(result, "Shared Core returned invalid hybrid clock")
        if (result.wallMs < maxOf(local.wallMs, remote?.wallMs ?: 0L, physicalNowMs)) {
            throw invalidOutput()
        }
        return result
    }

    private fun requireClock(clock: CoreHlc, message: String) {
        if (clock.wallMs !in 0..SyncWireBounds.MaxSafeInteger ||
            clock.counter !in 0..SyncWireBounds.MaxSafeInteger
        ) throw CoreProjectionException.InvalidInput(message)
    }

    private fun invalidOutput(cause: Throwable? = null) = CoreProjectionException.InvalidOutput(
        "Shared Core returned invalid hybrid clock",
        cause,
    )

    private companion object {
        const val Operation = "hlc.tick.v1"
    }
}

internal data class CoreFinishAppliedInput(
    val commandId: String,
    val timerId: String,
    val phase: String,
    val occurredAt: String,
    val history: List<HistoryItem>,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownedTimerId: String?,
    val reference: Instant,
    val zoneId: ZoneId,
)

internal data class CoreExpiryInput(
    val beforeTimer: CanonicalTimer?,
    val projectedTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val selectedPhase: String,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownedTimerId: String?,
    val reference: Instant,
    val zoneId: ZoneId,
)

internal data class CoreCommandRequestInput(
    val commandType: String,
    val requestedTimer: CanonicalTimer?,
    val projectedTimer: CanonicalTimer?,
    val automatic: Boolean,
    val generateAutoBreak: Boolean,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownedTimerId: String?,
)

internal data class CoreGeneratedBreakInput(
    val commandId: String,
    val timerId: String,
    val canonical: TimerProjection,
    val optimistic: TimerProjection,
    val sourceFinishPending: Boolean,
    val requireCanonical: Boolean,
    val reference: Instant,
    val zoneId: ZoneId,
)

internal data class CoreFinishAppliedDecision(
    val selectedPhase: String,
    val queueAutoBreak: Boolean,
)

internal data class CoreExpiryDecision(
    val expired: Boolean,
    val selectedPhase: String?,
    val generatedBreakPhase: String?,
)

internal data class CoreCommandRequestDecision(
    val eligible: Boolean,
    val reserveGeneratedBreak: Boolean,
)

internal data class CoreGeneratedBreakDecision(
    val eligible: Boolean,
    val phase: String?,
    val sourceAlreadyAccepted: Boolean,
)

@Serializable
private data class CoreCompletionSource(
    val commandId: String,
    val timerId: String,
    val phase: String,
    val occurredAt: String,
)

@Serializable
private data class CoreCompletionIdentity(val commandId: String, val timerId: String)

@Serializable
private data class CoreCompletionOwnership(val timerId: String, val ownerDeviceId: String)

@Serializable
private data class CoreCompletionProjection(
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
)

@Serializable
private data class CoreFinishAppliedWireInput(
    val kind: String = "finishApplied",
    val source: CoreCompletionSource,
    val history: List<HistoryItem>,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownership: CoreCompletionOwnership?,
    val dayStart: String,
    val dayEnd: String,
)

@Serializable
private data class CoreExpiryWireInput(
    val kind: String = "expiry",
    val beforeTimer: CanonicalTimer?,
    val projectedTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val selectedPhase: String,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownership: CoreCompletionOwnership?,
    val dayStart: String,
    val dayEnd: String,
)

@Serializable
private data class CoreCommandRequestWireInput(
    val kind: String = "commandRequest",
    val commandType: String,
    val requestedTimer: CanonicalTimer?,
    val projectedTimer: CanonicalTimer?,
    val automatic: Boolean,
    val generateAutoBreak: Boolean,
    val autoStartBreaks: Boolean,
    val localDeviceId: String,
    val ownership: CoreCompletionOwnership?,
)

@Serializable
private data class CoreGeneratedBreakWireInput(
    val kind: String = "generatedBreak",
    val source: CoreCompletionIdentity,
    val canonical: CoreCompletionProjection,
    val optimistic: CoreCompletionProjection,
    val sourceFinishPending: Boolean,
    val requireCanonical: Boolean,
    val dayStart: String,
    val dayEnd: String,
)

@Serializable
private data class CoreCompletionOutput(
    val expired: Boolean = false,
    val commandEligible: Boolean = false,
    val reserveGeneratedBreak: Boolean = false,
    val selectedPhase: String? = null,
    val queueAutoBreak: Boolean = false,
    val generatedBreakEligible: Boolean = false,
    val generatedBreakPhase: String? = null,
    val sourceAlreadyAccepted: Boolean = false,
)

internal class CoreCompletionDispatcher(
    private val dispatch: (String, String) -> JsonElement,
) {
    private val wireJson = Json { explicitNulls = false; encodeDefaults = true }
    private val strictJson = Json { ignoreUnknownKeys = false }

    fun expiry(input: CoreExpiryInput): CoreExpiryDecision {
        val bounds = dayBounds(input.reference, input.zoneId)
        val wire = CoreExpiryWireInput(
            beforeTimer = input.beforeTimer,
            projectedTimer = input.projectedTimer,
            history = input.history,
            selectedPhase = input.selectedPhase,
            autoStartBreaks = input.autoStartBreaks,
            localDeviceId = input.localDeviceId,
            ownership = input.ownedTimerId?.let {
                CoreCompletionOwnership(it, input.localDeviceId)
            },
            dayStart = bounds.first,
            dayEnd = bounds.second,
        )
        val output = decode(wireJson.encodeToString(wire))
        validateExpiryOutput(output)
        return CoreExpiryDecision(output.expired, output.selectedPhase, output.generatedBreakPhase)
    }

    fun commandRequest(input: CoreCommandRequestInput): CoreCommandRequestDecision {
        val ownership = input.ownedTimerId?.let {
            CoreCompletionOwnership(it, input.localDeviceId)
        }
        val wire = CoreCommandRequestWireInput(
            commandType = input.commandType,
            requestedTimer = input.requestedTimer,
            projectedTimer = input.projectedTimer,
            automatic = input.automatic,
            generateAutoBreak = input.generateAutoBreak,
            autoStartBreaks = input.autoStartBreaks,
            localDeviceId = input.localDeviceId,
            ownership = ownership,
        )
        val output = decode(wireJson.encodeToString(wire))
        if (output.expired || output.selectedPhase != null || output.queueAutoBreak ||
            output.generatedBreakEligible || output.generatedBreakPhase != null ||
            output.sourceAlreadyAccepted || output.reserveGeneratedBreak && !output.commandEligible
        ) invalidOutput()
        return CoreCommandRequestDecision(
            output.commandEligible,
            output.reserveGeneratedBreak,
        )
    }

    fun finishApplied(input: CoreFinishAppliedInput): CoreFinishAppliedDecision {
        val bounds = dayBounds(input.reference, input.zoneId)
        val ownership = input.ownedTimerId?.let {
            CoreCompletionOwnership(it, input.localDeviceId)
        }
        val wire = CoreFinishAppliedWireInput(
            source = CoreCompletionSource(
                input.commandId, input.timerId, input.phase, input.occurredAt,
            ),
            history = input.history,
            autoStartBreaks = input.autoStartBreaks,
            localDeviceId = input.localDeviceId,
            ownership = ownership,
            dayStart = bounds.first,
            dayEnd = bounds.second,
        )
        val output = decode(wireJson.encodeToString(wire))
        val phase = output.selectedPhase?.takeIf(::validPhase) ?: invalidOutput()
        if (output.expired || output.commandEligible || output.reserveGeneratedBreak ||
            output.generatedBreakEligible || output.generatedBreakPhase != null ||
            output.sourceAlreadyAccepted
        ) invalidOutput()
        return CoreFinishAppliedDecision(phase, output.queueAutoBreak)
    }

    fun generatedBreak(input: CoreGeneratedBreakInput): CoreGeneratedBreakDecision {
        val bounds = dayBounds(input.reference, input.zoneId)
        val wire = CoreGeneratedBreakWireInput(
            source = CoreCompletionIdentity(input.commandId, input.timerId),
            canonical = input.canonical.toCoreProjection(),
            optimistic = input.optimistic.toCoreProjection(),
            sourceFinishPending = input.sourceFinishPending,
            requireCanonical = input.requireCanonical,
            dayStart = bounds.first,
            dayEnd = bounds.second,
        )
        val output = decode(wireJson.encodeToString(wire))
        val canonicalHasSource = input.canonical.hasExactSource(input.commandId, input.timerId)
        val sourceAccepted = !input.sourceFinishPending && canonicalHasSource
        val selected = if (input.requireCanonical || sourceAccepted) {
            input.canonical
        } else {
            input.optimistic
        }
        if (output.expired || output.commandEligible || output.reserveGeneratedBreak ||
            output.selectedPhase != null || output.queueAutoBreak ||
            output.generatedBreakPhase?.let(::validPhase) == false ||
            output.generatedBreakEligible != (output.generatedBreakPhase != null) ||
            output.generatedBreakEligible != selected.hasExactSource(input.commandId, input.timerId) ||
            output.sourceAlreadyAccepted != sourceAccepted
        ) invalidOutput()
        return CoreGeneratedBreakDecision(
            output.generatedBreakEligible,
            output.generatedBreakPhase,
            output.sourceAlreadyAccepted,
        )
    }

    private fun decode(input: String): CoreCompletionOutput = try {
        strictJson.decodeFromJsonElement(
            CoreCompletionOutput.serializer(),
            dispatch(Operation, input),
        )
    } catch (error: CoreProjectionException) {
        throw error
    } catch (error: Exception) {
        throw CoreProjectionException.InvalidOutput(
            "Could not decode Shared Core completion plan",
            error,
        )
    }

    private fun dayBounds(reference: Instant, zoneId: ZoneId): Pair<String, String> {
        val start = reference.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val end = reference.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()
        return start.toString() to end.toString()
    }

    private fun TimerProjection.toCoreProjection() =
        CoreCompletionProjection(timer, history)

    private fun TimerProjection.hasExactSource(commandId: String, timerId: String): Boolean =
        timer?.id == timerId && timer.phase == TimerPhase.Focus && timer.status == "completed" &&
            history.any {
                it.timerId == timerId && it.commandId == commandId &&
                    it.phase == TimerPhase.Focus && it.status == "completed"
            }

    private fun validPhase(phase: String): Boolean = phase in setOf(
        TimerPhase.Focus,
        TimerPhase.ShortBreak,
        TimerPhase.LongBreak,
    )

    private fun validateExpiryOutput(output: CoreCompletionOutput) {
        if (output.commandEligible || output.reserveGeneratedBreak || output.queueAutoBreak ||
            output.generatedBreakEligible || output.sourceAlreadyAccepted ||
            output.selectedPhase?.let(::validPhase) == false ||
            output.generatedBreakPhase?.let(::validPhase) == false ||
            !output.expired && (output.selectedPhase != null || output.generatedBreakPhase != null)
        ) invalidOutput()
    }

    private fun invalidOutput(): Nothing = throw CoreProjectionException.InvalidOutput(
        "Shared Core returned invalid completion plan",
    )

    private companion object {
        const val Operation = "timer.completionPlan.v1"
    }
}
