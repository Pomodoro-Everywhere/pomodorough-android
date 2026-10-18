package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50 connected regression: Pause/Finish after an issued Start must keep
 * the retired parent in the mutation projection. Safe filtering alone
 * drops the issued Start, so Core rejects the dependent command and the
 * connected jobs lose the in-flight toggle and the provisional break.
 * Selecting the already-selected task stays a no-op.
 */
class R50ConnectedFailuresRegressionTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val projection by lazy {
        CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }
    }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }
        TimerMutationCoordinator(
            json = json,
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
        )
    }

    @Test
    fun pauseAfterIssuedStartStillApplies() {
        val sent = issuedStart()
        val running = runningTimer(sent.timerId)
        val state = commandState(sent, running)
        val reservation = commandReservation(sequence = 2L, counter = 1L)
        val transition = coordinator.command(
            TimerCommandMutationInput(state, CommandType.Pause, null, reservation, PauseAtMs),
        ) as TimerMutationTransition.Planned
        val pause = transition.plan.commands.single()
        assertEquals(CommandType.Pause, pause.type)
        assertEquals(sent.timerId, pause.timerId)
        assertEquals(TimerStatus.Paused, transition.plan.projection.canonicalTimer?.status)
    }

    @Test
    fun finishAfterIssuedStartStillApplies() {
        val sent = issuedStart()
        val running = runningTimer(sent.timerId)
        val state = commandState(sent, running)
        val reservation = finishReservation()
        val transition = coordinator.finish(
            TimerFinishMutationInput(
                state = state,
                current = running,
                completionRequest = CoreCommandRequestDecision(
                    eligible = true,
                    reserveGeneratedBreak = false,
                ),
                reservation = reservation,
                physicalNowMs = PauseAtMs,
            ),
        ) as TimerMutationTransition.Planned
        assertEquals(listOf(CommandType.Finish), transition.plan.commands.map(TimerCommand::type))
        assertEquals(TimerStatus.Completed, transition.plan.projection.canonicalTimer?.status)
    }

    @Test
    fun selectingAlreadySelectedIsIgnoredWhileDifferentPlans() {
        val taskA = requireNotNull(TaskReducer.taskFromTitle("Select A"))
        val taskB = requireNotNull(TaskReducer.taskFromTitle("Select B"))
        val state = selectionState(listOf(taskA, taskB), taskB.id)
        val same = coordinator.selectedTask(
            SelectedTaskMutationInput(state, taskB.id, selectionReservation()),
        )
        assertTrue(same is TimerMutationTransition.Ignored)
        val different = coordinator.selectedTask(
            SelectedTaskMutationInput(state, taskA.id, selectionReservation()),
        ) as TimerMutationTransition.Planned
        assertEquals(taskA.id, different.plan.projection.selectedTaskId)
    }

    private fun issuedStart() = TimerCommand(
        id = "command-sent",
        deviceSequence = 1L,
        timerId = "timer-1",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_001,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private fun runningTimer(timerId: String) = CanonicalTimer(
        id = timerId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )

    private fun commandState(sent: TimerCommand, running: CanonicalTimer): TimerMutationState {
        val settings = TimerSettings()
        val local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 1L,
            hlcWallMs = sent.hlcWallMs,
            hlcCounter = 0L,
            settingsJson = json.encodeToString(settings),
            safeCanonicalHeadWallMs = 1_767_225_600_000,
            safeCanonicalHeadCounter = 0L,
        )
        return TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(running, emptyList()),
            projectionBase = CoreProjectionBase(),
            queues = PendingSyncQueues(listOf(sent), emptyList(), emptyList(), emptyList(), emptyList()),
            neverSentProof = CoreNeverSentProof(),
            dependencies = emptyMap(),
            knownTasks = emptyMap(),
            visibleTasks = emptyList(),
            selectedTaskId = null,
        )
    }

    private fun commandReservation(sequence: Long, counter: Long): TimerMutationReservation {
        val at = "2026-01-01T00:01:00Z"
        val wallMs = Instant.parse(at).toEpochMilli()
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = sequence,
                    wallMs = wallMs,
                    counter = counter,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(UUID.fromString("00000000-0000-7000-8000-000000000021")),
            lastUuidV7 = "00000000-0000-7000-8000-000000000021",
        )
    }

    private fun finishReservation(): TimerMutationReservation {
        val at = "2026-01-01T00:25:00Z"
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

    private fun selectionState(tasks: List<FocusTask>, selectedId: String): TimerMutationState {
        val settings = TimerSettings()
        val local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 7L,
            hlcWallMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            hlcCounter = 3L,
            settingsJson = json.encodeToString(settings),
            safeCanonicalHeadWallMs = 1_767_225_600_000,
            safeCanonicalHeadCounter = 0L,
        )
        val byId = tasks.associateBy(FocusTask::id)
        return TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(tasks = tasks),
            queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            neverSentProof = CoreNeverSentProof(),
            dependencies = emptyMap(),
            knownTasks = byId,
            visibleTasks = tasks,
            selectedTaskId = selectedId,
        )
    }

    private fun selectionReservation(): TimerMutationReservation {
        val at = "2026-01-01T00:01:00Z"
        val wallMs = Instant.parse(at).toEpochMilli()
        return TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = null,
                    wallMs = wallMs,
                    counter = 4L,
                    occurredAt = at,
                ),
            ),
            uuids = listOf(UUID.fromString("00000000-0000-7000-8000-000000000023")),
            lastUuidV7 = "00000000-0000-7000-8000-000000000023",
        )
    }

    private companion object {
        const val PauseAtMs = 1_767_225_660_000L
    }
}
