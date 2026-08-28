package me.egigoka.pomodorough.data

import java.time.Instant
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

class TimerMutationCoordinatorTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val projectionDispatcher by lazy {
        CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }
    }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }
        TimerMutationCoordinator(
            json = json,
            projectionDispatcher = projectionDispatcher,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = java.time.ZoneId.of("UTC"),
            timerId = { "generated-break-timer" },
        )
    }

    @Test
    fun finishBuildsDependentProvisionalBreakWithReservedIdentityAndClockOrder() {
        val current = CanonicalTimer(
            id = "focus-timer",
            phase = TimerPhase.Focus,
            status = TimerStatus.Running,
            plannedDurationMs = 1_500_000,
            elapsedAtAnchorMs = 0,
            anchorAt = "2026-01-01T00:00:00Z",
        )
        val state = state(
            settings = TimerSettings(autoStartBreaks = true),
            projection = TimerProjection(current, emptyList()),
            projectionBase = CoreProjectionBase(canonicalTimer = current),
        ).let { value ->
            value.copy(local = value.local.copy(ownedTimerId = current.id))
        }
        val reservation = reservation(
            sequences = listOf(8L, 9L),
            counters = listOf(4L, 5L),
            ids = listOf(
                "00000000-0000-7000-8000-000000000001",
                "00000000-0000-7000-8000-000000000002",
            ),
            lastUuidV7 = "00000000-0000-7000-8000-000000000002",
            occurredAt = "2026-01-01T00:25:00Z",
        )

        val transition = coordinator.finish(
            TimerFinishMutationInput(
                state = state,
                current = current,
                completionRequest = CoreCommandRequestDecision(
                    eligible = true,
                    reserveGeneratedBreak = true,
                ),
                reservation = reservation,
                physicalNowMs = Instant.parse("2026-01-01T00:25:00Z").toEpochMilli(),
            ),
        ) as TimerMutationTransition.Planned
        val plan = transition.plan
        val finish = plan.commands[0]
        val generated = plan.commands[1]

        assertEquals(listOf(CommandType.Finish, CommandType.Start), plan.commands.map(TimerCommand::type))
        assertEquals(listOf(8L, 9L), plan.commands.map(TimerCommand::deviceSequence))
        assertEquals(listOf(4L, 5L), plan.commands.map(TimerCommand::hlcCounter))
        assertEquals(finish.id, plan.dependencies[generated.id])
        assertEquals("generated-break-timer", generated.timerId)
        assertEquals(TimerPhase.ShortBreak, generated.phase)
        assertEquals(TimerPhase.ShortBreak, plan.settings.selectedPhase)
        assertEquals("generated-break-timer", plan.local.ownedTimerId)
        assertEquals(9L, plan.local.deviceSequence)
        assertEquals(5L, plan.local.hlcCounter)
        assertEquals("generated-break-timer", plan.projection.canonicalTimer?.id)
        assertEquals(TimerStatus.Running, plan.projection.canonicalTimer?.status)
    }

    @Test
    fun deletingSelectedTaskBuildsTaskThenSelectionOperationsAndRetainsKnownIdentity() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val state = state(
            projectionBase = CoreProjectionBase(tasks = listOf(task), selectedTaskId = task.id),
            knownTasks = mapOf(task.id to task),
            visibleTasks = listOf(task),
            selectedTaskId = task.id,
        )
        val reservation = reservation(
            counters = listOf(7L, 8L),
            ids = listOf(
                "00000000-0000-7000-8000-000000000003",
                "00000000-0000-7000-8000-000000000004",
            ),
            lastUuidV7 = "00000000-0000-7000-8000-000000000004",
        )

        val transition = coordinator.task(
            TaskMutationInput(
                state = state,
                type = TaskOperationType.Delete,
                task = task,
                select = false,
                reservation = reservation,
            ),
        ) as TimerMutationTransition.Planned
        val plan = transition.plan

        assertEquals(TaskOperationType.Delete, plan.operation.type)
        assertEquals(
            "task-operation-00000000-0000-7000-8000-000000000003",
            plan.operation.id,
        )
        assertEquals(7L, plan.operation.hlcCounter)
        assertEquals("00000000-0000-7000-8000-000000000004", plan.selectedOperation?.id)
        assertNull(plan.selectedOperation?.taskId)
        assertEquals(8L, plan.selectedOperation?.hlcCounter)
        assertEquals(listOf(plan.operation), plan.taskOperations)
        assertEquals(listOf(plan.selectedOperation), plan.selectedTaskOperations)
        assertTrue(plan.projection.tasks.isEmpty())
        assertNull(plan.projection.selectedTaskId)
        assertNull(plan.local.selectedTaskId)
        assertEquals(8L, plan.local.hlcCounter)
        assertEquals(reservation.lastUuidV7, plan.local.lastUuidV7)
        assertEquals(task, plan.knownTasks[task.id])
        assertTrue(plan.local.knownTasksJson.contains("Deep work"))
    }

    @Test
    fun deletingSelectedTaskRejectsIncompleteReservationBeforeProjection() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val state = state(
            projectionBase = CoreProjectionBase(tasks = listOf(task), selectedTaskId = task.id),
            knownTasks = mapOf(task.id to task),
            visibleTasks = listOf(task),
            selectedTaskId = task.id,
        )
        val incompleteReservation = reservation(
            counters = listOf(7L),
            ids = listOf("00000000-0000-7000-8000-000000000003"),
            lastUuidV7 = "00000000-0000-7000-8000-000000000003",
        )

        val transition = coordinator.task(
            TaskMutationInput(
                state = state,
                type = TaskOperationType.Delete,
                task = task,
                select = false,
                reservation = incompleteReservation,
            ),
        )

        assertTrue(transition === TimerMutationTransition.Ignored)
    }

    @Test
    fun durationMutationReplacesSamePhasePendingOperationAndProjectsSettings() {
        val old = DurationOperation(
            id = "duration-operation-old",
            phase = TimerPhase.Focus,
            durationMs = 1_800_000,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            hlcCounter = 0,
        )
        val state = state(
            settings = TimerSettings().withDuration(TimerPhase.Focus, old.durationMs),
            queues = emptyQueues().copy(durationOperations = listOf(old)),
        )
        val reservation = reservation(
            counters = listOf(1L),
            ids = listOf("00000000-0000-7000-8000-000000000005"),
            lastUuidV7 = "00000000-0000-7000-8000-000000000005",
        )

        val transition = coordinator.duration(
            DurationMutationInput(state, TimerPhase.Focus, 1, reservation),
        ) as TimerMutationTransition.Planned
        val plan = transition.plan

        assertEquals(1_860_000L, plan.operation.durationMs)
        assertEquals(listOf(plan.operation), plan.operations)
        assertEquals(1_860_000L, plan.settings.durationMsFor(TimerPhase.Focus))
        assertEquals(plan.operation.id, plan.projection.winningOperationIds.durations[TimerPhase.Focus])
        assertTrue(plan.local.settingsJson.contains("1860000"))
    }

    @Test
    fun autoStartMutationCarriesDeviceIdentityAndAppendsQueue() {
        val old = AutoStartOperation(
            id = "auto-start-old",
            deviceId = "device-2",
            enabled = false,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            hlcCounter = 0,
        )
        val state = state(queues = emptyQueues().copy(autoStartOperations = listOf(old)))
        val reservation = reservation(
            counters = listOf(2L),
            ids = listOf("00000000-0000-7000-8000-000000000006"),
            lastUuidV7 = "00000000-0000-7000-8000-000000000006",
        )

        val transition = coordinator.autoStart(
            AutoStartMutationInput(state, true, reservation),
        ) as TimerMutationTransition.Planned
        val plan = transition.plan

        assertEquals("device-1", plan.operation.deviceId)
        assertEquals(listOf(old, plan.operation), plan.operations)
        assertTrue(plan.settings.autoStartBreaks)
        assertEquals(plan.operation.id, plan.projection.winningOperationIds.autoStart)
    }

    private fun state(
        settings: TimerSettings = TimerSettings(),
        projection: TimerProjection = TimerProjection(null, emptyList()),
        projectionBase: CoreProjectionBase = CoreProjectionBase(),
        queues: PendingSyncQueues = emptyQueues(),
        knownTasks: Map<String, FocusTask> = emptyMap(),
        visibleTasks: List<FocusTask> = emptyList(),
        selectedTaskId: String? = null,
    ) = TimerMutationState(
        local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 7,
            hlcWallMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            hlcCounter = 3,
            settingsJson = json.encodeToString(settings),
            selectedTaskId = selectedTaskId,
        ),
        settings = settings,
        projection = projection,
        projectionBase = projectionBase,
        queues = queues,
        dependencies = emptyMap(),
        knownTasks = knownTasks,
        visibleTasks = visibleTasks,
        selectedTaskId = selectedTaskId,
    )

    private fun reservation(
        sequences: List<Long?> = emptyList(),
        counters: List<Long>,
        ids: List<String>,
        lastUuidV7: String,
        occurredAt: String = "2026-01-01T00:01:00Z",
    ): TimerMutationReservation {
        val wallMs = Instant.parse(occurredAt).toEpochMilli()
        return TimerMutationReservation(
            stamps = counters.mapIndexed { index, counter ->
                SyncWireBounds.MutationStamp(
                    deviceSequence = sequences.getOrNull(index),
                    wallMs = wallMs,
                    counter = counter,
                    occurredAt = occurredAt,
                )
            },
            uuids = ids.map(UUID::fromString),
            lastUuidV7 = lastUuidV7,
        )
    }

    private fun emptyQueues() = PendingSyncQueues(
        commands = emptyList(),
        taskOperations = emptyList(),
        durationOperations = emptyList(),
        autoStartOperations = emptyList(),
        selectedTaskOperations = emptyList(),
    )
}
