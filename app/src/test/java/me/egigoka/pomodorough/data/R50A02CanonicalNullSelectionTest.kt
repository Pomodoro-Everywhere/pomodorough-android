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
 * R50-A02: canonical null selection must not fall back to optimistic local.
 * A covering head makes null authoritative (No task). Legacy rows without
 * a head keep the optimistic local for full-Core equality. Start reads the
 * retained selection intent (R54-A19); issued selections stay out of the safe
 * display and stay durable for exact retry.
 */
class R50A02CanonicalNullSelectionTest {
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
            timerId = { "start-timer-1" },
        )
    }

    @Test
    fun canonicalNullBaseDiffersFromOptimisticFallback() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val nullBase = CoreProjectionBase(tasks = listOf(task), selectedTaskId = null)
        val optimisticBase = CoreProjectionBase(tasks = listOf(task), selectedTaskId = task.id)
        val nullResult = projection.apply(nullBase, CoreProjectionPending(), Instant.parse(ServerTime))
        val optimisticResult = projection.apply(optimisticBase, CoreProjectionPending(), Instant.parse(ServerTime))
        assertNull(nullResult.selectedTaskId)
        assertEquals(task.id, optimisticResult.selectedTaskId)
        assertTrue(nullResult != optimisticResult)
    }

    @Test
    fun canonicalNullSafeStartRunsWithoutTask() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val state = startState(task, safeBase = null, optimisticLocal = task.id, safeSelected = null)
        assertNull(startTaskId(state))
    }

    @Test
    fun canonicalNonNullSafeStartKeepsTask() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val state = startState(task, safeBase = task.id, optimisticLocal = task.id, safeSelected = task.id)
        assertEquals(task.id, startTaskId(state))
    }

    @Test
    fun legacyWithoutHeadFallsBackToLocal() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        // The base selects a different task, so only the legacy local
        // branch can start with the device's own selection.
        val baseTask = requireNotNull(TaskReducer.taskFromTitle("Inbox"))
        val settings = TimerSettings()
        val local = LocalStateEntity(
            deviceId = DeviceId,
            deviceSequence = 9,
            hlcWallMs = OpWallMs,
            hlcCounter = 0L,
            settingsJson = json.encodeToString(settings),
            selectedTaskId = task.id,
        )
        val state = TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(tasks = listOf(task, baseTask), selectedTaskId = baseTask.id),
            queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            dependencies = emptyMap(),
            knownTasks = mapOf(task.id to task, baseTask.id to baseTask),
            visibleTasks = listOf(task, baseTask),
            selectedTaskId = baseTask.id,
        )
        assertEquals(task.id, startTaskId(state))
    }

    @Test
    fun issuedSelectionExcludedFromDisplayButStartsWithTask() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val issued = SelectedTaskOperation("selection-issued-1", task.id, NewAt, NewWallMs, 0L)
        val queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), listOf(issued))
        val safe = SafeProjectionPolicy.safeQueues(queues, CoreNeverSentProof(), Head)
        assertTrue(safe.selectedTaskOperations.isEmpty())
        assertEquals(listOf(issued), queues.selectedTaskOperations)
        val state = startState(task, safeBase = null, optimisticLocal = task.id, safeSelected = null)
            .copy(queues = queues)
        // R54-A19: Core's androidCoordinator profile plans Start from every
        // retained queue, so the sent-but-unacknowledged selection still
        // shapes Start even though the safe display omits it.
        assertEquals(task.id, startTaskId(state))
    }

    @Test
    fun safeNeverSentSelectionStartCarriesTask() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Deep work"))
        val safeOp = SelectedTaskOperation("selection-safe-1", task.id, NewAt, NewWallMs, 0L)
        val queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), listOf(safeOp))
        val proof = CoreNeverSentProof(selectedTaskOperations = listOf(safeOp.id))
        val safe = SafeProjectionPolicy.safeQueues(queues, proof, Head)
        assertEquals(listOf(safeOp), safe.selectedTaskOperations)
        val state = startState(task, safeBase = null, optimisticLocal = task.id, safeSelected = task.id)
            .copy(queues = queues, neverSentProof = proof)
        assertEquals(task.id, startTaskId(state))
    }

    private fun startState(task: FocusTask, safeBase: String?, optimisticLocal: String?, safeSelected: String?): TimerMutationState {
        val settings = TimerSettings()
        val local = LocalStateEntity(
            deviceId = DeviceId,
            deviceSequence = 9,
            hlcWallMs = NewWallMs,
            hlcCounter = 0L,
            settingsJson = json.encodeToString(settings),
            selectedTaskId = optimisticLocal,
            safeBaseSelectedTaskId = safeBase,
            safeCanonicalHeadWallMs = Head.first,
            safeCanonicalHeadCounter = Head.second,
        )
        return TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(tasks = listOf(task), selectedTaskId = safeBase),
            queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            dependencies = emptyMap(),
            knownTasks = mapOf(task.id to task),
            visibleTasks = listOf(task),
            selectedTaskId = safeSelected,
        )
    }

    private fun startTaskId(state: TimerMutationState): String? {
        val wallMs = Instant.parse(StartAt).toEpochMilli()
        val reservation = TimerMutationReservation(
            stamps = listOf(
                SyncWireBounds.MutationStamp(
                    deviceSequence = 10L,
                    wallMs = wallMs,
                    counter = 0L,
                    occurredAt = StartAt,
                ),
            ),
            uuids = listOf(UUID.fromString("00000000-0000-7000-8000-000000000021")),
            lastUuidV7 = "00000000-0000-7000-8000-000000000021",
        )
        val input = TimerCommandMutationInput(state, CommandType.Start, null, reservation, wallMs)
        val transition = coordinator.command(input) as TimerMutationTransition.Planned
        return transition.plan.commands.single().taskId
    }

    private companion object {
        const val DeviceId = "device-1"
        const val ServerTime = "2026-07-20T00:08:00Z"
        const val StartAt = "2026-07-20T00:09:00Z"
        const val NewAt = "2026-07-20T00:07:00Z"
        const val OpWallMs = 1_784_505_840_000L
        const val NewWallMs = 1_784_506_020_000L
        val Head = 1_784_505_960_000L to 0L
    }
}
