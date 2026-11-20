package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R55-A03: the user pressed Start right after a break ended while the
 * automatic sync was in flight. The response's serverHlc* includes the
 * server's physical now, which is ahead of the Start, so installing it as the
 * canonical head hid the never-sent Start until the next sync. The head now
 * follows the receiver rule: the canonical pair when present, else serverHlc*.
 */
class R55A03CanonicalHeadTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val reconciliationInputs = mutableListOf<String>()
    private val dispatch = { operation: String, input: String ->
        if (operation == "reconcile.rebase.v2") reconciliationInputs += input
        core.dispatch(operation, input)
    }
    private val projection by lazy { CoreProjectionDispatcher(dispatch) }
    private val coordinator by lazy {
        CentralizedSyncCoordinator(
            json = json,
            bootstrapDispatcher = CoreBootstrapDispatcher(dispatch),
            reconciliationDispatcher = CoreReconciliationDispatcher(dispatch, projection),
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            batchPlanner = CoreBatchPlanDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
        )
    }

    @Test
    fun startQueuedDuringSyncStaysProjectedUnderAnOlderCanonicalHead() {
        val result = applyWithQueuedStart(canonical = DurationWallMs to 0L)

        assertEquals(DurationWallMs to 0L, SafeProjectionPolicy.headOrNull(result.local))
        val timer = rebuiltProjection(result).canonicalTimer
        assertEquals(StartTimerId, timer?.id)
        assertEquals(TimerStatus.Running, timer?.status)
    }

    @Test
    fun startStaysHiddenWhenAnotherDeviceOrderedALaterOperation() {
        val later = StartWallMs + 1_000L to 0L

        val result = applyWithQueuedStart(canonical = later)

        assertEquals(later, SafeProjectionPolicy.headOrNull(result.local))
        assertNull(rebuiltProjection(result).canonicalTimer)
    }

    @Test
    fun responseWithoutThePairKeepsTheServerClockAsTheHead() {
        val result = applyWithQueuedStart(canonical = null)

        assertEquals(ServerWallMs to ServerCounter, SafeProjectionPolicy.headOrNull(result.local))
        assertNull(rebuiltProjection(result).canonicalTimer)
    }

    @Test
    fun reconciliationPassesThePairToCoreBesideTheServerClock() {
        applyWithQueuedStart(canonical = DurationWallMs to 0L)

        val response = sentResponse()
        assertEquals(DurationWallMs, response.getValue("canonicalHlcWallMs").jsonPrimitive.long)
        assertEquals(0L, response.getValue("canonicalHlcCounter").jsonPrimitive.long)
        assertEquals(ServerWallMs, response.getValue("serverHlcWallMs").jsonPrimitive.long)
        assertEquals(ServerCounter, response.getValue("serverHlcCounter").jsonPrimitive.long)
    }

    @Test
    fun reconciliationOmitsAnAbsentPairInsteadOfSendingNull() {
        applyWithQueuedStart(canonical = null)

        val response = sentResponse()
        assertFalse("canonicalHlcWallMs" in response)
        assertFalse("canonicalHlcCounter" in response)
    }

    @Test
    fun bootstrapInstallationStoresTheCanonicalPairAsTheHead() {
        val current = snapshot(PendingSyncQueues(listOf(startCommand()), emptyList(), emptyList(), emptyList(), emptyList()))
            .let { it.copy(neverSent = NeverSentStart, local = it.local.copy(deviceSequence = 1)) }
        val response = response(DurationWallMs to 0L, acknowledgeDuration = false)

        val result = coordinator.applyBootstrapInstallation(
            CentralizedBootstrapInstallationInput(
                snapshot = current,
                profile = User("user-1", "user@example.com", "User", ""),
                response = response,
                clearLocal = false,
                sampledLocal = current.local,
                localizedTimer = null,
                localizedHistory = emptyList(),
                projectionNow = Instant.parse(ServerTime),
            ),
        )

        assertEquals(DurationWallMs to 0L, SafeProjectionPolicy.headOrNull(result.local))
        assertEquals(StartTimerId, rebuiltProjection(result).canonicalTimer?.id)
    }

    // A bootstrap newer than the sync response wins with its own state, so it
    // must keep its own canonical pair; only the acknowledgements come along.
    @Test
    fun aNewerBootstrapKeepsItsOwnCanonicalPair() {
        val newer = response(DurationWallMs to 0L).copy(revision = 6)
        val answer = response(StartWallMs to 1L)

        val chosen = coordinator.canonicalBootstrapResponse(newer, answer)

        assertEquals(DurationWallMs to 0L, chosen.canonicalHead())
        assertEquals(answer.durationAcknowledgements, chosen.durationAcknowledgements)
        val stale = coordinator.canonicalBootstrapResponse(newer.copy(revision = 3), answer)
        assertEquals(StartWallMs to 1L, stale.canonicalHead())
    }

    // Needs R55-C14 (Core 0.57.0): Core reads the canonical pair in reconcile.rebase.v2.
    @Test
    fun coreReconciliationProjectsTheQueuedStartUnderAnOlderCanonicalHead() {
        val result = applyWithQueuedStart(canonical = DurationWallMs to 0L)

        assertEquals(StartTimerId, result.projected.projection.canonicalTimer?.id)
    }

    private fun sentResponse(): JsonObject =
        Json.parseToJsonElement(reconciliationInputs.single()).jsonObject.getValue("response").jsonObject

    // The attempt carries only a duration operation. The Start is queued after
    // the request left, so the response neither sends nor acknowledges it.
    private fun applyWithQueuedStart(canonical: Pair<Long, Long>?): CentralizedSyncApplication {
        val sending = snapshot(PendingSyncQueues(emptyList(), emptyList(), listOf(durationOperation()), emptyList(), emptyList()))
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(SyncAttemptIdentity(9, "a03-attempt"), sending, ServerWallMs, 10_000),
        )
        val current = sending.copy(
            queues = sending.queues.copy(commands = listOf(startCommand())),
            neverSent = NeverSentStart,
            local = sending.local.copy(deviceSequence = 1),
        )
        val response = response(canonical)
        return coordinator.applySync(
            CentralizedSyncApplicationInput(
                snapshot = current,
                attempt = attempt,
                response = response,
                sampledLocal = current.local.copy(hlcWallMs = ServerWallMs, hlcCounter = 3),
                localizedTimer = response.canonicalTimer,
                localizedHistory = response.history,
                projectionNow = Instant.parse(response.serverTime),
            ),
        )
    }

    // Mirrors TimerRepository.projectSynchronizedState: every rebuild after
    // the sync filters the retained queues by the stored head.
    private fun rebuiltProjection(result: CentralizedSyncApplication): CoreProjectionResult {
        val queues = result.pending.queues
        val safe = SafeProjectionPolicy.safeQueues(queues, NeverSentStart, SafeProjectionPolicy.headOrNull(result.local))
        val base = CoreProjectionBase(durationsMs = DurationsMs(focus = FocusMs))
        val request = SynchronizedProjectionRequestFactory.create(base, safe, DeviceId)
        return projection.apply(request.base, request.pending, Instant.parse(ServerTime))
    }

    private fun snapshot(queues: PendingSyncQueues): CentralizedSyncSnapshot {
        val settings = TimerSettings()
        return CentralizedSyncSnapshot(
            local = LocalStateEntity(
                deviceId = DeviceId,
                deviceSequence = 0,
                hlcWallMs = StartWallMs,
                hlcCounter = 0,
                revision = 3,
                settingsJson = json.encodeToString(settings),
            ),
            queues = queues,
            neverSent = CoreNeverSentProof(),
            dependencies = emptyMap(),
            canonicalTimer = null,
            canonicalHistory = emptyList(),
            canonicalTasks = emptyList(),
            canonicalAutoStartBreaks = false,
            knownTasks = emptyMap(),
            settings = settings,
            selectedPhaseGeneration = 0,
            projectionBase = CoreProjectionBase(durationsMs = settings.effectiveDurationsMs()),
        )
    }

    private fun durationOperation() = DurationOperation(
        id = DurationId,
        phase = TimerPhase.Focus,
        durationMs = FocusMs,
        occurredAt = DurationTime,
        hlcWallMs = DurationWallMs,
        hlcCounter = 0,
    )

    private fun startCommand() = TimerCommand(
        id = StartId,
        deviceSequence = 1,
        timerId = StartTimerId,
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = FocusMs,
        occurredAt = StartTime,
        hlcWallMs = StartWallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
        physicalOccurredAt = StartTime,
    )

    private fun response(canonical: Pair<Long, Long>?, acknowledgeDuration: Boolean = true) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 4,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = ServerTime,
        serverHlcWallMs = ServerWallMs,
        serverHlcCounter = ServerCounter,
        durationAcknowledgements = listOf(DurationAcknowledgement(DurationId, "applied", ""))
            .filter { acknowledgeDuration },
        durationsMs = DurationsMs(focus = FocusMs),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = false,
        canonicalHlcWallMs = canonical?.first,
        canonicalHlcCounter = canonical?.second,
    ).also { TimerSyncValidation.validateCanonicalResponse(it, "Sync") }

    private companion object {
        const val DeviceId = "device-a"
        const val DurationId = "duration-sent"
        const val StartId = "start-queued"
        const val StartTimerId = "focus-queued"
        const val FocusMs = 1_800_000L
        const val ServerCounter = 2L
        const val DurationTime = "2026-07-20T00:01:00Z"
        const val StartTime = "2026-07-20T00:02:00Z"
        const val ServerTime = "2026-07-20T00:06:00Z"
        val DurationWallMs = Instant.parse(DurationTime).toEpochMilli()
        val StartWallMs = Instant.parse(StartTime).toEpochMilli()
        val ServerWallMs = Instant.parse(ServerTime).toEpochMilli()
        val NeverSentStart = CoreNeverSentProof(commands = listOf(StartId))
    }
}
