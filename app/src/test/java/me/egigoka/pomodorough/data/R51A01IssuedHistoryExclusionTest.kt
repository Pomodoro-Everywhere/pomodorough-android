package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R51-A01: a new Start after issued Start+Finish must not restore retired
 * history. Lineage keeps same-timer parents for Pause/Finish/retarget and
 * generated breaks; an unrelated Start carries no lineage.
 */
class R51A01IssuedHistoryExclusionTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val captured = mutableListOf<Pair<String, String>>()
    private val projection by lazy {
        CoreProjectionDispatcher { operation, input ->
            captured.add(operation to input)
            core.dispatch(operation, input)
        }
    }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String ->
            captured.add(operation to input)
            core.dispatch(operation, input)
        }
        TimerMutationCoordinator(
            json = json,
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
            timerId = { NewTimerId },
        )
    }

    @Test
    fun newStartAfterIssuedPairExcludesRetiredHistory() {
        captured.clear()
        val issued = issuedPair()
        val state = issuedState(issued, proof = CoreNeverSentProof())
        val reservation = commandReservation(sequence = 3L, counter = 6L, uuid = NewStartId)
        val transition = coordinator.command(
            TimerCommandMutationInput(state, CommandType.Start, TimerPhase.ShortBreak, reservation, NowMs),
        ) as TimerMutationTransition.Planned
        val plan = transition.plan
        assertEquals(listOf(NewStartId), plan.commands.map(TimerCommand::id))
        assertEquals(NewTimerId, plan.projection.canonicalTimer?.id)
        assertTrue(plan.projection.history.isEmpty())
        assertEquals(setOf(NewStartId), plan.projection.timerOutcomes.keys)
        assertApplied(plan.projection, NewStartId)
        assertCompleteReturnEqualsRaw(plan.projection)
        assertPendingCommandsContainOnly(captured, listOf(NewStartId))
        assertRetainedUnchanged(state, issued)
    }

    @Test
    fun unrelatedAutoStartThenNewStartKeepsProofsAndClocks() {
        captured.clear()
        val issued = issuedPair()
        val base = issuedState(issued, proof = CoreNeverSentProof())
        val autoReservation = autoStartReservation()
        val autoTransition = coordinator.autoStart(
            AutoStartMutationInput(base, true, autoReservation),
        ) as TimerMutationTransition.Planned
        assertEquals(true, autoTransition.plan.settings.autoStartBreaks)
        assertEquals(listOf(autoTransition.plan.operation.id), autoTransition.plan.operations.map(AutoStartOperation::id))
        val settingsState = base.copy(
            local = autoTransition.plan.local,
            settings = autoTransition.plan.settings,
            queues = base.queues.copy(autoStartOperations = autoTransition.plan.operations),
            neverSentProof = CoreNeverSentProof(
                autoStartOperations = autoTransition.plan.operations.map(AutoStartOperation::id),
            ),
            projection = TimerProjection(
                autoTransition.plan.projection.canonicalTimer,
                autoTransition.plan.projection.history,
            ),
        )
        val reservation = commandReservation(sequence = 3L, counter = 6L, uuid = NewStartId)
        val transition = coordinator.command(
            TimerCommandMutationInput(
                settingsState, CommandType.Start, TimerPhase.ShortBreak, reservation, NowMs,
            ),
        ) as TimerMutationTransition.Planned
        assertEquals(NewTimerId, transition.plan.projection.canonicalTimer?.id)
        assertTrue(transition.plan.projection.history.isEmpty())
        assertEquals(
            autoTransition.plan.operation.id,
            transition.plan.projection.winningOperationIds.autoStart,
        )
        assertEquals(listOf(issued[0].id, issued[1].id), issued.map(TimerCommand::id))
        assertEquals(listOf(2L, 3L), issued.map(TimerCommand::hlcCounter))
        assertCompleteReturnEqualsRaw(transition.plan.projection)
    }

    @Test
    fun pauseAfterIssuedStartKeepsSameTimerLineage() {
        captured.clear()
        val start = issuedStart()
        val state = issuedState(listOf(start), proof = CoreNeverSentProof())
            .copy(projection = TimerProjection(runningTimer(start.timerId), emptyList()))
        val reservation = commandReservation(sequence = 2L, counter = 4L, uuid = PauseId)
        val transition = coordinator.command(
            TimerCommandMutationInput(state, CommandType.Pause, null, reservation, NowMs),
        ) as TimerMutationTransition.Planned
        assertEquals(CommandType.Pause, transition.plan.commands.single().type)
        assertEquals(start.timerId, transition.plan.projection.canonicalTimer?.id)
        assertEquals(TimerStatus.Paused, transition.plan.projection.canonicalTimer?.status)
        assertPendingCommandsContainOnly(captured, listOf(start.id, PauseId))
    }

    @Test
    fun finishAfterIssuedStartKeepsSameTimerLineage() {
        captured.clear()
        val start = issuedStart()
        val running = runningTimer(start.timerId)
        val state = issuedState(listOf(start), proof = CoreNeverSentProof())
            .copy(projection = TimerProjection(running, emptyList()))
        val reservation = finishReservation()
        val transition = coordinator.finish(
            TimerFinishMutationInput(state, running, finishRequest(false), reservation, FinishMs),
        ) as TimerMutationTransition.Planned
        assertEquals(listOf(CommandType.Finish), transition.plan.commands.map(TimerCommand::type))
        assertEquals(TimerStatus.Completed, transition.plan.projection.canonicalTimer?.status)
        assertPendingCommandsContainOnly(captured, listOf(start.id, transition.plan.commands.single().id))
    }

    @Test
    fun finishWithAutoStartKeepsGeneratedBreakDependency() {
        captured.clear()
        val start = issuedStart()
        val running = runningTimer(start.timerId)
        val initial = issuedState(listOf(start), proof = CoreNeverSentProof())
        val base = initial.copy(
            local = initial.local.copy(ownedTimerId = start.timerId),
            projection = TimerProjection(running, emptyList()),
            settings = TimerSettings(autoStartBreaks = true),
        )
        val reservation = generatedReservation()
        val transition = coordinator.finish(
            TimerFinishMutationInput(base, running, finishRequest(true), reservation, FinishMs),
        ) as TimerMutationTransition.Planned
        assertEquals(TimerPhase.ShortBreak, transition.plan.projection.canonicalTimer?.phase)
        assertEquals(TimerStatus.Running, transition.plan.projection.canonicalTimer?.status)
        val finishId = transition.plan.commands.first { it.type == CommandType.Finish }.id
        assertPendingCommandsContainOnly(
            captured,
            listOf(start.id, finishId, transition.plan.commands.last().id),
        )
    }

    private fun assertApplied(result: CoreProjectionResult, id: String) {
        assertEquals("applied", result.timerOutcomes.getValue(id).outcome)
    }

    private fun assertCompleteReturnEqualsRaw(expected: CoreProjectionResult) {
        val index = captured.indexOfLast { it.first == "projection.apply.v2" }
        assertTrue(index >= 0)
        val raw = core.dispatch(captured[index].first, captured[index].second)
        assertEquals(expected, json.decodeFromJsonElement(CoreProjectionResult.serializer(), raw))
    }

    private fun assertPendingCommandsContainOnly(
        calls: List<Pair<String, String>>,
        ids: List<String>,
    ) {
        val input = Json.parseToJsonElement(calls.last { it.first == "projection.apply.v2" }.second)
        val pending = input.jsonObject.getValue("pending").jsonObject
            .getValue("commands").jsonArray.map { it.jsonObject.getValue("id").toString().trim('"') }
        assertEquals(ids, pending)
    }

    private fun assertRetainedUnchanged(state: TimerMutationState, issued: List<TimerCommand>) {
        assertEquals(issued.map(TimerCommand::id), state.queues.commands.map(TimerCommand::id))
        assertTrue(state.neverSentProof.commands.isEmpty())
        assertEquals(issued, state.queues.commands)
    }

    private fun autoStartReservation(): TimerMutationReservation {
        val at = "2026-10-20T00:00:00Z"
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = null,
                    wallMs = HeadWallMs,
                    counter = 5L,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(UUID.fromString("01a1569b-9800-7498-ac5e-56a3d6140cfb")),
            lastUuidV7 = "01a1569b-9800-7498-ac5e-56a3d6140cfb",
        )
    }

    private fun issuedStart() = TimerCommand(
        id = "01a1569b-9800-7498-ac5e-56a3d6140cf9",
        deviceSequence = 1L,
        timerId = OldTimerId,
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-10-20T00:00:00Z",
        hlcWallMs = HeadWallMs,
        hlcCounter = 2L,
        observedElapsedMs = 0,
    )

    private fun issuedPair(): List<TimerCommand> = listOf(
        issuedStart(),
        TimerCommand(
            id = "01a1569b-9800-7498-ac5e-56a3d6140cfa",
            deviceSequence = 2L,
            timerId = OldTimerId,
            type = CommandType.Finish,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = "2026-10-20T00:00:00Z",
            hlcWallMs = HeadWallMs,
            hlcCounter = 3L,
            observedElapsedMs = 0,
        ),
    )

    private fun issuedQueues(issued: List<TimerCommand>) = PendingSyncQueues(
        commands = issued,
        taskOperations = emptyList(),
        durationOperations = emptyList(),
        autoStartOperations = emptyList(),
        selectedTaskOperations = emptyList(),
    )

    private fun issuedState(issued: List<TimerCommand>, proof: CoreNeverSentProof): TimerMutationState {
        val settings = TimerSettings()
        return TimerMutationState(
            local = LocalStateEntity(
                deviceId = DeviceId,
                deviceSequence = 2L,
                hlcWallMs = HeadWallMs,
                hlcCounter = 3L,
                settingsJson = json.encodeToString(settings),
                safeCanonicalHeadWallMs = HeadWallMs,
                safeCanonicalHeadCounter = 0L,
            ),
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(),
            queues = issuedQueues(issued),
            neverSentProof = proof,
            dependencies = emptyMap(),
            knownTasks = emptyMap(),
            visibleTasks = emptyList(),
            selectedTaskId = null,
        )
    }

    private fun runningTimer(timerId: String) = CanonicalTimer(
        id = timerId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-10-20T00:00:00Z",
    )

    private fun commandReservation(sequence: Long, counter: Long, uuid: String): TimerMutationReservation {
        val at = "2026-10-20T00:00:00Z"
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = sequence,
                    wallMs = HeadWallMs,
                    counter = counter,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(UUID.fromString(uuid)),
            lastUuidV7 = uuid,
        )
    }

    private fun finishReservation(): TimerMutationReservation {
        val at = "2026-10-20T00:25:00Z"
        val wallMs = Instant.parse(at).toEpochMilli()
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = 2L,
                    wallMs = wallMs,
                    counter = 1L,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(UUID.fromString("00000000-0000-7000-8000-000000000022")),
            lastUuidV7 = "00000000-0000-7000-8000-000000000022",
        )
    }

    private fun generatedReservation(): TimerMutationReservation {
        val at = "2026-10-20T00:25:00Z"
        val wallMs = Instant.parse(at).toEpochMilli()
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = 2L,
                    wallMs = wallMs,
                    counter = 1L,
                    occurredAt = at,
                ),
                SyncWireBounds.MutationStamp(
                    deviceSequence = 3L,
                    wallMs = wallMs,
                    counter = 2L,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(
                UUID.fromString("00000000-0000-7000-8000-000000000032"),
                UUID.fromString("00000000-0000-7000-8000-000000000033"),
            ),
            lastUuidV7 = "00000000-0000-7000-8000-000000000033",
        )
    }

    private fun finishRequest(generated: Boolean) = CoreCommandRequestDecision(
        eligible = true,
        reserveGeneratedBreak = generated,
    )

    private companion object {
        const val DeviceId = "r51-device"
        const val OldTimerId = "43ba46ff-1d67-47bd-ab4e-14409d7e8347"
        const val NewTimerId = "42b0d36d-5d3a-4843-bef6-47ad6d79c2df"
        const val NewStartId = "01a1569b-9800-7498-ac5e-56a3d6140cfc"
        const val PauseId = "00000000-0000-7000-8000-000000000021"
        const val HeadWallMs = 1792454400000L
        val Head = HeadWallMs to 0L
        const val NowMs = 1792454401000L
        const val FinishMs = 1792455900000L
    }
}
