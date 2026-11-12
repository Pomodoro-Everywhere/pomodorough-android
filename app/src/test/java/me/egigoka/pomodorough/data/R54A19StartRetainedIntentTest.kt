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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A19: Start reads the retained local intent. A duration, task, or
 * selection that was sent but is not yet acknowledged falls out of the
 * delivery-safe display, yet Core's androidCoordinator intent profile still
 * plans Start from it. The display projection itself stays safe.
 */
class R54A19StartRetainedIntentTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }
        TimerMutationCoordinator(
            json = json,
            projectionDispatcher = CoreProjectionDispatcher(dispatch),
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
            timerId = { StartTimerId },
        )
    }
    private val task = requireNotNull(TaskReducer.taskFromTitle("Shared focus"))

    @Test
    fun startUsesInFlightDurationWhileDisplayKeepsCanonicalDuration() {
        val queues = queues(durations = listOf(issuedFocusDuration()))

        val plan = startPlan(issuedState(queues))

        assertEquals(FocusIntentMs, plan.commands.single().plannedDurationMs)
        assertEquals(CanonicalFocusMs, plan.projection.durationsMs.focus)
    }

    @Test
    fun startUsesInFlightTaskAndSelectionWhileDisplayKeepsCanonicalTasks() {
        val queues = queues(tasks = listOf(issuedUpsert()), selected = listOf(issuedSelection(task.id)))

        val plan = startPlan(issuedState(queues))

        assertEquals(task.id, plan.commands.single().taskId)
        assertTrue(plan.projection.tasks.isEmpty())
        assertNull(plan.projection.selectedTaskId)
    }

    @Test
    fun startHonorsInFlightClearOverCanonicalSelection() {
        val queues = queues(selected = listOf(issuedSelection(null)))
        val base = CoreProjectionBase(tasks = listOf(task), selectedTaskId = task.id)
        val state = issuedState(queues).copy(
            projectionBase = base,
            visibleTasks = listOf(task),
            selectedTaskId = task.id,
        )

        assertNull(startPlan(state).commands.single().taskId)
    }

    @Test
    fun breakStartCarriesNoTaskEvenWithRetainedSelection() {
        val queues = queues(tasks = listOf(issuedUpsert()), selected = listOf(issuedSelection(task.id)))
        val state = issuedState(queues)

        val command = startPlan(state, TimerPhase.ShortBreak).commands.single()

        assertNull(command.taskId)
        assertEquals(DurationsMs().shortBreak, command.plannedDurationMs)
    }

    private fun issuedState(queues: PendingSyncQueues): TimerMutationState {
        val settings = TimerSettings()
        val local = LocalStateEntity(
            deviceId = DeviceId,
            deviceSequence = 9,
            hlcWallMs = HeadWallMs,
            hlcCounter = 4L,
            settingsJson = json.encodeToString(settings),
            safeCanonicalHeadWallMs = HeadWallMs,
            safeCanonicalHeadCounter = 0L,
        )
        return TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(durationsMs = DurationsMs()),
            queues = queues,
            neverSentProof = CoreNeverSentProof(),
            dependencies = emptyMap(),
            knownTasks = emptyMap(),
            visibleTasks = emptyList(),
            selectedTaskId = null,
        )
    }

    private fun startPlan(
        state: TimerMutationState,
        phase: String? = null,
    ): TimerCommandMutationPlan {
        val reservation = TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = 10L,
                    wallMs = HeadWallMs,
                    counter = 5L,
                    occurredAt = StartAt,
                ),
            ),
            uuids = listOf(UUID.fromString(StartCommandId)),
            lastUuidV7 = StartCommandId,
        )
        val input = TimerCommandMutationInput(state, CommandType.Start, phase, reservation, HeadWallMs)
        return (coordinator.command(input) as TimerMutationTransition.Planned).plan
    }

    private fun queues(
        tasks: List<TaskOperation> = emptyList(),
        durations: List<DurationOperation> = emptyList(),
        selected: List<SelectedTaskOperation> = emptyList(),
    ) = PendingSyncQueues(emptyList(), tasks, durations, emptyList(), selected)

    // Issued operations: sent and unacknowledged, so they lack never-sent
    // proof and the safe display drops each whole domain to its canonical base.
    private fun issuedFocusDuration() =
        DurationOperation("duration-issued", TimerPhase.Focus, FocusIntentMs, IssuedAt, HeadWallMs, 1L)

    private fun issuedUpsert() =
        TaskOperation("task-operation-issued", task.id, TaskOperationType.Upsert, task.title, IssuedAt, HeadWallMs, 2L)

    private fun issuedSelection(taskId: String?) =
        SelectedTaskOperation("selection-issued", taskId, IssuedAt, HeadWallMs, 3L)

    private companion object {
        const val DeviceId = "device-a"
        const val StartTimerId = "start-timer-a19"
        const val StartCommandId = "00000000-0000-7000-8000-000000000a19"
        const val IssuedAt = "2026-01-01T00:00:00Z"
        const val StartAt = "2026-01-01T00:00:01Z"
        const val FocusIntentMs = 1_800_000L
        const val CanonicalFocusMs = 1_500_000L
        val HeadWallMs = Instant.parse(IssuedAt).toEpochMilli()
    }
}
