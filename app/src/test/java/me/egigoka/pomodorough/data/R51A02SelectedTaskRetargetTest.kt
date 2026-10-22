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
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R51-A02: combined selection plus retarget must publish the newly planned
 * selection. Issued selections poison whole-domain safe filtering; the
 * compound planner pins the new operation through an authoritative Core
 * decision. Real Core returns only; raw inputs are re-dispatched for proof.
 */
class R51A02SelectedTaskRetargetTest {
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

    private val taskA = requireNotNull(TaskReducer.taskFromTitle("Alpha deep work"))
    private val taskB = requireNotNull(TaskReducer.taskFromTitle("Beta deep work"))

    @Test
    fun issuedEarlierSelectionCombinedPublishesNewSelection() {
        captured.clear()
        val issuedA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c01", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(issuedA),
            proofSelected = emptyList(),
            baseSelected = null,
            head = Head,
        )
        val plan = combinedPlan(state, taskB.id)
        assertEquals(taskB.id, plan.projection.selectedTaskId)
        assertEquals(taskB.id, plan.projection.canonicalTimer?.taskId)
        assertEquals(taskB.id, plan.selectionOperation.taskId)
        assertEquals(taskB.id, plan.local.selectedTaskId)
        assertCompleteReturnEqualsRaw(plan.projection)
        assertPendingSelectionContainsOnly(listOf(plan.selectionOperation.id))
        assertRetainedForRetry(plan, listOf(issuedA.id, plan.selectionOperation.id))
    }

    @Test
    fun neverSentEarlierSelectionCombinedKeepsBothPending() {
        captured.clear()
        val sentA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c02", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(sentA),
            proofSelected = listOf(sentA.id),
            baseSelected = taskA.id,
            head = Head,
        )
        val plan = combinedPlan(state, taskB.id)
        assertEquals(taskB.id, plan.projection.selectedTaskId)
        assertEquals(taskB.id, plan.projection.canonicalTimer?.taskId)
        assertCompleteReturnEqualsRaw(plan.projection)
        val pending = pendingSelectedIds()
        assertTrue(pending.contains(plan.selectionOperation.id))
        assertTrue(pending.contains(sentA.id))
    }

    @Test
    fun canonicalNonNullBaseCombinedStillRetargets() {
        captured.clear()
        val issuedA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c03", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(issuedA),
            proofSelected = emptyList(),
            baseSelected = taskA.id,
            head = Head,
        )
        val plan = combinedPlan(state, taskB.id)
        assertEquals(taskB.id, plan.projection.selectedTaskId)
        assertEquals(taskB.id, plan.projection.canonicalTimer?.taskId)
        assertCompleteReturnEqualsRaw(plan.projection)
    }

    @Test
    fun legacyWithoutHeadCombinedPublishesNewSelection() {
        captured.clear()
        val issuedA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c04", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(issuedA),
            proofSelected = emptyList(),
            baseSelected = null,
            head = null,
        )
        val plan = combinedPlan(state, taskB.id)
        assertEquals(taskB.id, plan.projection.selectedTaskId)
        assertEquals(taskB.id, plan.projection.canonicalTimer?.taskId)
        assertCompleteReturnEqualsRaw(plan.projection)
    }

    @Test
    fun sameTimerLineagePreservedCrossTimerExcluded() {
        captured.clear()
        val sameState = combinedState(
            selectedOps = listOf(selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c05", taskA.id, 1L)),
            proofSelected = emptyList(),
            baseSelected = null,
            head = Head,
        )
        val samePlan = combinedPlan(sameState, taskB.id)
        assertTrue(pendingCommandIds().contains(IssuedStartId))
        assertEquals(taskB.id, samePlan.projection.canonicalTimer?.taskId)

        captured.clear()
        val crossStart = issuedStart(id = "01a1569b-9800-7498-ac5e-56a3d6140d02").copy(timerId = OtherTimerId)
        val crossState = combinedState(
            selectedOps = listOf(selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c06", taskA.id, 1L)),
            proofSelected = emptyList(),
            baseSelected = null,
            head = Head,
            commands = listOf(issuedStart(), crossStart),
        )
        val crossPlan = combinedPlan(crossState, taskB.id)
        assertTrue(pendingCommandIds().contains(IssuedStartId))
        assertTrue(!pendingCommandIds().contains(crossStart.id))
        assertEquals(taskB.id, crossPlan.projection.canonicalTimer?.taskId)
    }

    @Test
    fun outwardExternalIdentityPriorValidationIgnores() {
        val issuedA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c07", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(issuedA),
            proofSelected = emptyList(),
            baseSelected = null,
            head = Head,
        )
        assertTrue(combinedPlanOrNull(state, "missing-task-id") == null)
        assertTrue(combinedPlanOrNull(state, "") == null)
        val sameState = state.copy(selectedTaskId = taskB.id)
        assertTrue(combinedPlanOrNull(sameState, taskB.id) == null)
        val timerSame = state.projection.timer?.copy(taskId = taskB.id)
        val retargetSame = state.copy(projection = TimerProjection(timerSame, emptyList()))
        assertTrue(combinedPlanOrNull(retargetSame, taskB.id) == null)
    }

    @Test
    fun clearSelectionCombinedPublishesNullWithTimerNull() {
        captured.clear()
        val sentA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c08", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(sentA),
            proofSelected = listOf(sentA.id),
            baseSelected = taskA.id,
            head = Head,
        )
        val plan = combinedPlan(state, null)
        assertNull(plan.projection.selectedTaskId)
        assertNull(plan.projection.canonicalTimer?.taskId)
        assertNull(plan.selectionOperation.taskId)
        assertCompleteReturnEqualsRaw(plan.projection)
    }

    @Test
    fun reloadFromPersistedQueuesKeepsNewOperationForRetry() {
        captured.clear()
        val issuedA = selectionOp("01a1569b-9800-7498-ac5e-56a3d6140c09", taskA.id, 1L)
        val state = combinedState(
            selectedOps = listOf(issuedA),
            proofSelected = emptyList(),
            baseSelected = null,
            head = Head,
        )
        val plan = combinedPlan(state, taskB.id)
        val reloaded = state.copy(
            local = plan.local,
            queues = state.queues.copy(selectedTaskOperations = plan.selectedTaskOperations),
            neverSentProof = state.neverSentProof.copy(
                selectedTaskOperations = state.neverSentProof.selectedTaskOperations + plan.selectionOperation.id,
            ),
        )
        assertEquals(listOf(issuedA.id, plan.selectionOperation.id), reloaded.queues.selectedTaskOperations.map(SelectedTaskOperation::id))
        assertEquals(listOf(plan.selectionOperation.id), reloaded.neverSentProof.selectedTaskOperations)
        assertEquals(taskB.id, reloaded.local.selectedTaskId)
        assertEquals(taskB.id, plan.projection.selectedTaskId)
    }

    private fun issuedStart(taskId: String? = taskA.id, id: String = IssuedStartId) = TimerCommand(
        id = id,
        deviceSequence = 1L,
        timerId = RunningTimerId,
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = At,
        hlcWallMs = HeadWallMs,
        hlcCounter = 2L,
        observedElapsedMs = 0,
        taskId = taskId,
    )

    private fun combinedState(
        selectedOps: List<SelectedTaskOperation>,
        proofSelected: List<String>,
        baseSelected: String?,
        head: Pair<Long, Long>?,
        commands: List<TimerCommand> = listOf(issuedStart()),
    ): TimerMutationState {
        val settings = TimerSettings()
        val timer = CanonicalTimer(
            id = RunningTimerId,
            phase = TimerPhase.Focus,
            status = TimerStatus.Running,
            plannedDurationMs = 1_500_000,
            elapsedAtAnchorMs = 0,
            anchorAt = At,
            taskId = taskA.id,
        )
        val local = LocalStateEntity(
            deviceId = DeviceId,
            deviceSequence = 2L,
            hlcWallMs = HeadWallMs,
            hlcCounter = 3L,
            settingsJson = json.encodeToString(settings),
            selectedTaskId = taskA.id,
            safeCanonicalHeadWallMs = head?.first,
            safeCanonicalHeadCounter = head?.second,
        )
        return TimerMutationState(
            local = local,
            settings = settings,
            projection = TimerProjection(timer, emptyList()),
            projectionBase = CoreProjectionBase(tasks = listOf(taskA, taskB), selectedTaskId = baseSelected),
            queues = PendingSyncQueues(
                commands = commands,
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = selectedOps,
            ),
            neverSentProof = CoreNeverSentProof(selectedTaskOperations = proofSelected),
            dependencies = emptyMap(),
            knownTasks = mapOf(taskA.id to taskA, taskB.id to taskB),
            visibleTasks = listOf(taskA, taskB),
            selectedTaskId = taskA.id,
        )
    }

    private fun combinedPlan(state: TimerMutationState, taskId: String?): SelectedTaskRetargetMutationPlan {
        val reservation = compoundReservation()
        val transition = coordinator.selectedTaskWithRetarget(
            state, taskId, RunningTimerId, reservation, NowMs,
        ) as TimerMutationTransition.Planned
        return transition.plan
    }

    private fun combinedPlanOrNull(state: TimerMutationState, taskId: String?): SelectedTaskRetargetMutationPlan? {
        val reservation = compoundReservation()
        val transition = coordinator.selectedTaskWithRetarget(
            state, taskId, RunningTimerId, reservation, NowMs,
        )
        return (transition as? TimerMutationTransition.Planned)?.plan
    }

    private fun compoundReservation(): TimerMutationReservation {
        val first = SyncWireBounds.MutationStamp(null, HeadWallMs, 4L, At)
        val second = SyncWireBounds.MutationStamp(3L, HeadWallMs, 5L, At)
        return TimerMutationReservation(
            stamps = listOf(first, second),
            uuids = listOf(UUID.fromString(NewSelId), UUID.fromString(NewRetId)),
            lastUuidV7 = NewRetId,
        )
    }

    private fun selectionOp(id: String, taskId: String?, counter: Long) = SelectedTaskOperation(
        id = id,
        taskId = taskId,
        occurredAt = At,
        hlcWallMs = HeadWallMs,
        hlcCounter = counter,
    )

    private fun assertCompleteReturnEqualsRaw(expected: CoreProjectionResult) {
        val index = captured.indexOfLast { it.first == "projection.apply.v2" }
        assertTrue(index >= 0)
        val raw = core.dispatch(captured[index].first, captured[index].second)
        assertEquals(expected, json.decodeFromJsonElement(CoreProjectionResult.serializer(), raw))
    }

    private fun pendingSelectedIds(): List<String> {
        val input = Json.parseToJsonElement(captured.last { it.first == "projection.apply.v2" }.second)
        return input.jsonObject.getValue("pending").jsonObject
            .getValue("selectedTaskOperations").jsonArray.map { it.jsonObject.getValue("id").toString().trim('"') }
    }

    private fun pendingCommandIds(): List<String> {
        val input = Json.parseToJsonElement(captured.last { it.first == "projection.apply.v2" }.second)
        return input.jsonObject.getValue("pending").jsonObject
            .getValue("commands").jsonArray.map { it.jsonObject.getValue("id").toString().trim('"') }
    }

    private fun assertPendingSelectionContainsOnly(ids: List<String>) {
        assertEquals(ids, pendingSelectedIds())
    }

    private fun assertRetainedForRetry(plan: SelectedTaskRetargetMutationPlan, ids: List<String>) {
        assertEquals(ids, plan.selectedTaskOperations.map(SelectedTaskOperation::id))
    }

    private companion object {
        const val DeviceId = "r51-a02-device"
        const val RunningTimerId = "43ba46ff-1d67-47bd-ab4e-14409d7e8347"
        const val OtherTimerId = "42b0d36d-5d3a-4843-bef6-47ad6d79c2df"
        const val NewTimerId = "42b0d36d-5d3a-4843-bef6-47ad6d79c2df"
        const val NewSelId = "01a1569b-9800-7498-ac5e-56a3d6140c10"
        const val NewRetId = "01a1569b-9800-7498-ac5e-56a3d6140d10"
        const val IssuedStartId = "01a1569b-9800-7498-ac5e-56a3d6140d00"
        const val HeadWallMs = 1792454400000L
        const val NowMs = 1792454401000L
        const val At = "2026-10-20T00:00:00Z"
        val Head = HeadWallMs to 0L
    }
}
