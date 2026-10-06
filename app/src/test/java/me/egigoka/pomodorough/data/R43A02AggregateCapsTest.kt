package me.egigoka.pomodorough.data

import java.time.ZoneId
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R43-A02 baseline probe: aggregate caps 512 (sync) and 8192 (bootstrap).
 * Same scenarios run before and after the fix; production must match Core.
 */
class R43A02AggregateCapsTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch by lazy {
        { operation: String, input: String -> core.dispatch(operation, input) }
    }
    private val coordinator by lazy {
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
    private val planner by lazy { CoreBatchPlanDispatcher(dispatch) }

    @Test
    fun syncBatchCapsAggregateAt512() {
        val commands = (0 until 256).map { index -> command("probe-c-$index", index + 1) }
        val tasks = (0 until 256).map { index -> task("probe-t-$index", index) }
        val durations = listOf(duration("probe-d-0", 0))
        val queues = PendingSyncQueues(
            commands = commands,
            taskOperations = tasks,
            durationOperations = durations,
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = SyncAttemptIdentity(9, "probe-513"),
                snapshot = snapshot(queues, emptyMap()),
                sentPhysicalMs = WallMs,
                sentElapsedRealtimeMs = 10_000,
            ),
        )
        val total = attempt.request.commands.size +
            attempt.request.taskOperations.size +
            attempt.request.durationOperations.size +
            attempt.request.autoStartOperations.size +
            attempt.request.selectedTaskOperations.size
        assertTrue("sync batch must not exceed aggregate 512, was $total", total <= 512)
        assertEquals(512, total)
    }

    @Test
    fun syncBatchAcceptsExact512() {
        val commands = (0 until 256).map { index -> command("exact-c-$index", index + 1) }
        val tasks = (0 until 256).map { index -> task("exact-t-$index", index) }
        val queues = PendingSyncQueues(
            commands = commands,
            taskOperations = tasks,
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = SyncAttemptIdentity(9, "probe-512"),
                snapshot = snapshot(queues, emptyMap()),
                sentPhysicalMs = WallMs,
                sentElapsedRealtimeMs = 10_000,
            ),
        )
        val total = attempt.request.commands.size + attempt.request.taskOperations.size
        assertEquals(512, total)
    }

    @Test
    fun bootstrapRejects8193AgainstAggregate8192() {
        val commands = (0 until 4096).map { index -> command("boot-c-$index", index + 1) }
        val tasks = (0 until 4096).map { index -> task("boot-t-$index", index) }
        val durations = listOf(duration("boot-d-0", 0))
        val request = TimerSyncConstruction.bootstrapRequest(
            deviceId = DeviceId,
            revision = 1L,
            strategy = BootstrapStrategy.Merge,
            eligibleCommands = commands,
            queues = PendingSyncQueues(
                commands = commands,
                taskOperations = tasks,
                durationOperations = durations,
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
        )
        try {
            TimerSyncValidation.validateResolutionEnvelope(request, DeviceId)
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("bootstrap 8193 operations must be rejected against aggregate 8192")
    }

    @Test
    fun bootstrapAcceptsExact8192() {
        val commands = (0 until 4096).map { index -> command("ok-c-$index", index + 1) }
        val tasks = (0 until 4096).map { index -> task("ok-t-$index", index) }
        val request = TimerSyncConstruction.bootstrapRequest(
            deviceId = DeviceId,
            revision = 1L,
            strategy = BootstrapStrategy.Merge,
            eligibleCommands = commands,
            queues = PendingSyncQueues(
                commands = commands,
                taskOperations = tasks,
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
        )
        TimerSyncValidation.validateResolutionEnvelope(request, DeviceId)
    }

    @Test
    fun timerBarrierHoldsLaterCommands() {
        val parent = command("barrier-parent", 1, counter = 0)
        val child = command("barrier-child", 2, counter = 1)
        val later = command("barrier-later", 3, counter = 2)
        val queues = PendingSyncQueues(
            commands = listOf(parent, child, later),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val attempt = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = SyncAttemptIdentity(9, "probe-barrier"),
                snapshot = snapshot(queues, mapOf(child.id to parent.id)),
                sentPhysicalMs = WallMs,
                sentElapsedRealtimeMs = 10_000,
            ),
        )
        val ids = attempt.request.commands.map(TimerCommand::id)
        assertTrue("parent must be selected", parent.id in ids)
        assertTrue("held child must wait", child.id !in ids)
        assertTrue("later commands wait behind barrier", later.id !in ids)
    }

    @Test
    fun oversizedSavedClaimIsNotReplayed() {
        val commands = (0 until 4096).map { index -> command("saved-c-$index", index + 1) }
        val tasks = (0 until 4096).map { index -> task("saved-t-$index", index) }
        val durations = listOf(duration("saved-d-0", 0))
        val request = TimerSyncConstruction.bootstrapRequest(
            deviceId = DeviceId,
            revision = 1L,
            strategy = BootstrapStrategy.Merge,
            eligibleCommands = commands,
            queues = PendingSyncQueues(
                commands = commands,
                taskOperations = tasks,
                durationOperations = durations,
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
        )
        try {
            TimerSyncValidation.validateResolutionEnvelope(request, DeviceId)
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("oversized saved bootstrap claim (8193) must not replay")
    }

    @Test
    fun starvationFreeDrainingCoversEveryDomainAcrossBatches() {
        val commands = (0 until 300).map { index -> command("drain-c-$index", index + 1) }
        val tasks = (0 until 300).map { index -> task("drain-t-$index", index) }
        var queues = PendingSyncQueues(
            commands = commands,
            taskOperations = tasks,
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        var cursor = "commands"
        val seen = mutableSetOf<String>()
        var rounds = 0
        while ((queues.commands.isNotEmpty() || queues.taskOperations.isNotEmpty()) && rounds < 5) {
            val attempt = coordinator.prepareSyncAttempt(
                CentralizedSyncAttemptInput(
                    identity = SyncAttemptIdentity(9, "drain-$rounds"),
                    snapshot = snapshot(queues, emptyMap()),
                    sentPhysicalMs = WallMs,
                    sentElapsedRealtimeMs = 10_000,
                    nextDomain = cursor,
                ),
            )
            val batch = attempt.request.commands.map(TimerCommand::id) +
                attempt.request.taskOperations.map(TaskOperation::id)
            assertTrue("each batch must make progress", batch.isNotEmpty())
            assertTrue("batch must respect aggregate 512", batch.size <= 512)
            batch.forEach { id -> assertTrue("no duplicate selection: $id", seen.add(id)) }
            val sentCommands = attempt.request.commands.map(TimerCommand::id).toSet()
            val sentTasks = attempt.request.taskOperations.map(TaskOperation::id).toSet()
            queues = queues.copy(
                commands = queues.commands.filter { it.id !in sentCommands },
                taskOperations = queues.taskOperations.filter { it.id !in sentTasks },
            )
            cursor = attempt.nextDomain
            rounds += 1
        }
        assertTrue("all 600 operations must drain", queues.commands.isEmpty() && queues.taskOperations.isEmpty())
        assertEquals(600, seen.size)
    }

    @Test
    fun restartReplaysExactSavedClaimViaCore() {
        val commands = listOf(command("restart-c-0", 1), command("restart-c-1", 2))
        val tasks = listOf(task("restart-t-0", 0))
        val queues = PendingSyncQueues(
            commands = commands,
            taskOperations = tasks,
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val first = coordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = SyncAttemptIdentity(9, "restart-first"),
                snapshot = snapshot(queues, emptyMap()),
                sentPhysicalMs = WallMs,
                sentElapsedRealtimeMs = 10_000,
                nextDomain = "commands",
            ),
        )
        // Restart: the exact saved claim replays through Core with identical order.
        val replay = planner.checkSavedSync(first.request)
        assertEquals("replay_saved", replay.status)
        assertEquals(
            first.request.commands.map(TimerCommand::id),
            replay.selected.commands,
        )
        assertEquals(
            first.request.taskOperations.map(TaskOperation::id),
            replay.selected.taskOperations,
        )
        // A restarted oversized claim never replays: no partial IDs follow.
        val oversized = SyncRequest(
            deviceId = DeviceId,
            lastRevision = 7L,
            commands = (0 until 256).map { index -> command("over-c-$index", index + 1) },
            durationOperations = listOf(duration("over-d-0", 0)),
            taskOperations = (0 until 256).map { index -> task("over-t-$index", index) },
        )
        val blocked = planner.checkSavedSync(oversized)
        assertEquals("oversized_saved", blocked.status)
        assertTrue(blocked.selected.commands.isEmpty())
        assertTrue(blocked.selected.taskOperations.isEmpty())
        assertTrue(blocked.selected.durationOperations.isEmpty())
    }

    private fun snapshot(
        queues: PendingSyncQueues,
        dependencies: Map<String, String>,
    ) = CentralizedSyncSnapshot(
        local = LocalStateEntity(
            deviceId = DeviceId,
            deviceSequence = 9_999_999L,
            hlcWallMs = WallMs,
            hlcCounter = 50_000L,
            revision = 7L,
            settingsJson = "{}",
        ),
        queues = queues,
        neverSent = CoreNeverSentProof(),
        dependencies = dependencies,
        canonicalTimer = null,
        canonicalHistory = emptyList(),
        canonicalTasks = emptyList(),
        canonicalAutoStartBreaks = false,
        knownTasks = emptyMap(),
        settings = TimerSettings(),
        selectedPhaseGeneration = 4,
    )

    private fun command(id: String, sequence: Int, counter: Long = sequence.toLong()) = TimerCommand(
        id = id,
        deviceSequence = sequence.toLong(),
        timerId = "timer-$id",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = counter,
        observedElapsedMs = 0L,
    )

    private fun task(id: String, counter: Int) = TaskOperation(
        id = id,
        taskId = "00000000-0000-4000-8000-${counter.toString().padStart(12, '0')}",
        type = TaskOperationType.Upsert,
        title = id,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = counter.toLong(),
    )

    private fun duration(id: String, counter: Int) = DurationOperation(
        id = id,
        phase = TimerPhase.Focus,
        durationMs = 1_500_000L,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = counter.toLong(),
    )

    private companion object {
        const val DeviceId = "device-0001"
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
    }
}
