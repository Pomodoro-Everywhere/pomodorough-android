package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A19: a sync response that lands while the local Start is still queued
 * projects without that Start (the safe display omits it). Ownership the
 * Start recorded must survive until the Start leaves the retained queue, or
 * the manual finish later loses its automatic break. An applied
 * acknowledgement hands the decision back to the canonical timer, and an
 * ignored Start gives ownership up.
 */
class R54A19QueuedStartOwnershipTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }
        val projection = CoreProjectionDispatcher(dispatch)
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
    fun ownershipSurvivesResponseThatOmitsQueuedStart() {
        val result = applyWithQueued(owned = StartTimerId, queued = command(CommandType.Start))

        assertEquals(listOf(StartId), result.pending.queues.commands.map(TimerCommand::id))
        assertNull(result.projected.projection.canonicalTimer)
        assertEquals(StartTimerId, result.local.ownedTimerId)
    }

    @Test
    fun queuedStartForAnotherTimerDoesNotKeepStaleOwnership() {
        val result = applyWithQueued(owned = OtherTimerId, queued = command(CommandType.Start))

        assertNull(result.local.ownedTimerId)
    }

    @Test
    fun queuedNonStartCommandDoesNotKeepOwnership() {
        val result = applyWithQueued(owned = StartTimerId, queued = command(CommandType.Pause))

        assertNull(result.local.ownedTimerId)
    }

    @Test
    fun acknowledgedStartKeepsOwnershipOfItsCanonicalTimer() {
        val result = applySentStart(Acknowledgement(StartId, "applied"), runningTimer(StartTimerId))

        assertTrue(result.pending.queues.commands.isEmpty())
        assertEquals(StartTimerId, result.local.ownedTimerId)
    }

    @Test
    fun acknowledgedStartYieldsOwnershipToAnotherCanonicalTimer() {
        val result = applySentStart(Acknowledgement(StartId, "applied"), runningTimer(RemoteTimerId))

        assertTrue(result.pending.queues.commands.isEmpty())
        assertNull(result.local.ownedTimerId)
    }

    @Test
    fun ignoredStartClearsOwnership() {
        val ignored = Acknowledgement(StartId, "ignored", "timer already active")

        val result = applySentStart(ignored, runningTimer(RemoteTimerId))

        assertTrue(result.pending.queues.commands.isEmpty())
        assertNull(result.local.ownedTimerId)
    }

    // The attempt carries only the duration operation. The command is queued
    // after the request left, so the response neither sends nor acknowledges it.
    private fun applyWithQueued(owned: String, queued: TimerCommand): CentralizedSyncApplication {
        val sending = snapshot(PendingSyncQueues(emptyList(), emptyList(), listOf(durationOperation()), emptyList(), emptyList()))
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(SyncAttemptIdentity(9, "a19-attempt"), sending, ServerWallMs, 10_000),
        )
        val current = snapshot(sending.queues.copy(commands = listOf(queued))).let {
            it.copy(
                local = it.local.copy(ownedTimerId = owned, deviceSequence = 1),
                neverSent = CoreNeverSentProof(commands = listOf(queued.id)),
            )
        }
        val durationAcknowledgement = DurationAcknowledgement(DurationId, "applied", "")
        return applyResponse(current, attempt, response(durationAcknowledgements = listOf(durationAcknowledgement)))
    }

    // The attempt sends the Start itself, so it has no never-sent proof. Sync
    // rejects a response without its acknowledgement, so the sent and
    // unacknowledged case is a restart (R54A19QueuedStartRestartTest).
    private fun applySentStart(
        acknowledgement: Acknowledgement,
        canonical: CanonicalTimer,
    ): CentralizedSyncApplication {
        val queues = PendingSyncQueues(listOf(command(CommandType.Start)), emptyList(), emptyList(), emptyList(), emptyList())
        val sending = snapshot(queues).let {
            it.copy(local = it.local.copy(ownedTimerId = StartTimerId, deviceSequence = 1))
        }
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(SyncAttemptIdentity(9, "a19-start-attempt"), sending, ServerWallMs, 10_000),
        )
        return applyResponse(sending, attempt, response(listOf(acknowledgement), canonical))
    }

    private fun applyResponse(
        current: CentralizedSyncSnapshot,
        attempt: SyncAttempt,
        response: SyncResponse,
    ): CentralizedSyncApplication =
        coordinator.applySync(
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
        durationMs = 1_800_000,
        occurredAt = DurationTime,
        hlcWallMs = Instant.parse(DurationTime).toEpochMilli(),
        hlcCounter = 0,
    )

    // Stamped before the server head, like the CI fixture, so the safe
    // projection excludes the whole command domain.
    private fun command(type: String) = TimerCommand(
        id = StartId,
        deviceSequence = 1,
        timerId = StartTimerId,
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_800_000,
        occurredAt = StartTime,
        hlcWallMs = StartWallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
        physicalOccurredAt = StartTime,
    )

    private fun runningTimer(id: String) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_800_000,
        elapsedAtAnchorMs = 0,
        anchorAt = StartTime,
        startedByDeviceId = DeviceId,
    )

    private fun response(
        acknowledgements: List<Acknowledgement> = emptyList(),
        canonical: CanonicalTimer? = null,
        durationAcknowledgements: List<DurationAcknowledgement> = emptyList(),
    ) = SyncResponse(
        acknowledgements = acknowledgements,
        revision = 4,
        canonicalTimer = canonical,
        history = emptyList(),
        serverTime = ServerTime,
        serverHlcWallMs = ServerWallMs,
        serverHlcCounter = 2,
        durationAcknowledgements = durationAcknowledgements,
        durationsMs = DurationsMs(focus = 1_800_000),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = false,
    )

    private companion object {
        const val DeviceId = "device-a"
        const val DurationId = "duration-sent"
        const val StartId = "start-queued"
        const val StartTimerId = "focus-queued"
        const val OtherTimerId = "focus-retired"
        const val RemoteTimerId = "focus-remote"
        const val DurationTime = "2026-07-20T00:01:00Z"
        const val StartTime = "2026-07-20T00:02:00Z"
        const val ServerTime = "2026-07-20T00:06:00Z"
        val StartWallMs = Instant.parse(StartTime).toEpochMilli()
        val ServerWallMs = Instant.parse(ServerTime).toEpochMilli()
    }
}
