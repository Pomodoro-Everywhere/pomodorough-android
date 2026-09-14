package me.egigoka.pomodorough.data

import java.io.File
import me.egigoka.pomodorough.core.SharedCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTaskRetargetTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }

    @Test
    fun retargetAssignsNewTaskWithFreshId() {
        val timer = timer(id = "timer-1", taskId = "task-a")
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )
        assertTrue(command != null)
        assertEquals(CommandType.Retarget, command!!.type)
        assertEquals("timer-1", command.timerId)
        assertEquals(TaskB, command.taskId)
        assertEquals(TimerPhase.Focus, command.phase)
        assertEquals(2, command.deviceSequence)
        assertNotEquals("start-1", command.id)
    }

    @Test
    fun retargetExplicitNullUnassigns() {
        val timer = timer(id = "timer-1", taskId = "task-a")
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = null,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )
        assertTrue(command != null)
        assertNull(command!!.taskId)
        assertEquals(CommandType.Retarget, command.type)
    }

    @Test
    fun retargetSameAssignmentIsIgnored() {
        val timer = timer(id = "timer-1", taskId = TaskB)
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )
        assertNull(command)
    }

    @Test
    fun retargetPausedTimerIsAllowed() {
        val timer = timer(id = "timer-1", taskId = "task-a", status = TimerStatus.Paused)
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )
        assertTrue(command != null)
        assertEquals(TaskB, command!!.taskId)
    }

    @Test
    fun retargetNonFocusTimerIsIgnored() {
        val timer = timer(id = "timer-1", taskId = null, phase = TimerPhase.ShortBreak)
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 0,
        )
        assertNull(command)
    }

    @Test
    fun retargetEmptyTaskIsRejected() {
        val timer = timer(id = "timer-1", taskId = "task-a")
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val command = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = "",
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )
        assertNull(command)
    }

    @Test
    fun retargetDoesNotRewriteStartPayload() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start)
        val timer = timer(id = "timer-1", taskId = "task-a")
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val retarget = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )!!
        assertNotEquals(start.id, retarget.id)
        assertEquals("task-a", start.taskId)
        assertEquals(TaskB, retarget.taskId)
        assertEquals(CommandType.Start, start.type)
        assertEquals(CommandType.Retarget, retarget.type)
    }

    @Test
    fun retargetProjectsThroughRealCore() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start, taskId = "task-a")
        val timer = timer(id = "timer-1", taskId = "task-a")
        val reservation = reservation(sequence = 2, wallMs = WallMs + 1_000)
        val retarget = TimerRetargetPolicy.plan(
            timerId = "timer-1",
            taskId = TaskB,
            current = timer,
            reservation = reservation,
            plannedDurationMs = 1_500_000,
            physicalNowMs = WallMs + 1_000,
            elapsedMs = 100_000,
        )!!
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        val base = emptyResponse()
        val result = dispatcher.rebaseV2(
            local = CoreProjectionPending(
                commands = listOf(DeviceOperation("device-1", start), DeviceOperation("device-1", retarget)),
            ),
            sent = CoreReconciliationSent(),
            neverSent = CoreNeverSentProof(commands = listOf(start.id, retarget.id)),
            response = base,
            dependencies = emptyList(),
        )
        assertEquals(TaskB, result.projection.canonicalTimer?.taskId)
        assertEquals(setOf(start.id, retarget.id), result.pending.commands.map { it.value.id }.toSet())
        assertEquals(result.pending.commands.map { it.value.id }.toSet(), result.projectionPending.commands.map { it.value.id }.toSet())
    }

    @Test
    fun acknowledgedRetargetLeavesPending() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start, taskId = "task-a")
        val retarget = command(id = "retarget-1", timerId = "timer-1", type = CommandType.Retarget, taskId = TaskB, sequence = 2, wallMs = WallMs + 1_000)
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        val response = emptyResponse().copy(
            acknowledgements = listOf(Acknowledgement(retarget.id, "applied")),
            canonicalTimer = timer(id = "timer-1", taskId = TaskB),
        )
        val result = dispatcher.rebaseV2(
            local = CoreProjectionPending(commands = listOf(DeviceOperation("device-1", start), DeviceOperation("device-1", retarget))),
            sent = CoreReconciliationSent(commands = listOf(retarget.id)),
            neverSent = CoreNeverSentProof(commands = listOf(start.id)),
            response = response,
            dependencies = emptyList(),
        )
        assertTrue(result.pending.commands.none { it.value.id == retarget.id })
        assertTrue(result.pending.commands.any { it.value.id == start.id })
    }

    @Test
    fun possiblyDeliveredRetargetIsExcludedFromProjection() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start, taskId = "task-a", wallMs = WallMs - 10_000)
        val retarget = command(id = "retarget-1", timerId = "timer-1", type = CommandType.Retarget, taskId = TaskB, sequence = 2, wallMs = WallMs - 5_000)
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        val response = emptyResponse()
        val result = dispatcher.rebaseV2(
            local = CoreProjectionPending(commands = listOf(DeviceOperation("device-1", start), DeviceOperation("device-1", retarget))),
            sent = CoreReconciliationSent(),
            neverSent = CoreNeverSentProof(),
            response = response,
            dependencies = emptyList(),
        )
        assertEquals(2, result.pending.commands.size)
        assertTrue(result.projectionPending.commands.isEmpty())
    }

    @Test
    fun v2NeverRebasesClocks() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start, taskId = "task-a", wallMs = 1_000)
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        val result = dispatcher.rebaseV2(
            local = CoreProjectionPending(commands = listOf(DeviceOperation("device-1", start))),
            sent = CoreReconciliationSent(),
            neverSent = CoreNeverSentProof(commands = listOf(start.id)),
            response = emptyResponse(),
            dependencies = emptyList(),
        )
        assertEquals(1_000L, result.pending.commands.single().value.hlcWallMs)
    }

    @Test
    fun neverSentProofRequiresLocalId() {
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        try {
            dispatcher.rebaseV2(
                local = CoreProjectionPending(),
                sent = CoreReconciliationSent(),
                neverSent = CoreNeverSentProof(commands = listOf("missing")),
                response = emptyResponse(),
                dependencies = emptyList(),
            )
            assertTrue("expected InvalidInput", false)
        } catch (error: CoreProjectionException.InvalidInput) {
            assertTrue(true)
        }
    }

    @Test
    fun neverSentProofRejectsSentId() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start)
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        try {
            dispatcher.rebaseV2(
                local = CoreProjectionPending(commands = listOf(DeviceOperation("device-1", start))),
                sent = CoreReconciliationSent(commands = listOf(start.id)),
                neverSent = CoreNeverSentProof(commands = listOf(start.id)),
                response = emptyResponse(),
                dependencies = emptyList(),
            )
            assertTrue("expected InvalidInput", false)
        } catch (error: CoreProjectionException.InvalidInput) {
            assertTrue(true)
        }
    }

    @Test
    fun retargetPersistenceFailureReportsAndNotices() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        val start = source.indexOf("fun commitRetarget(")
        assertTrue(start >= 0)
        val window = source.drop(start).take(1_600)
        assertTrue(window.contains("CancellationException"))
        assertTrue(window.contains("CrashReporter.report"))
        assertTrue(window.contains("mutationFailure"))
        assertTrue(window.contains("notice"))
    }

    @Test
    fun afterLocalMutationFailureReportsAndConflicts() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        val start = source.indexOf("fun afterLocalMutation(")
        assertTrue(start >= 0)
        val window = source.drop(start).take(1_200)
        assertTrue(window.contains("CancellationException"))
        assertTrue(window.contains("CrashReporter.report"))
        assertTrue(window.contains("conflict"))
    }

    @Test
    fun finishExpiredIrohTimerFailureReportsAndConflicts() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        val start = source.indexOf("fun finishExpiredIrohTimer(")
        assertTrue(start >= 0)
        val window = source.drop(start).take(1_600)
        assertTrue(window.contains("CancellationException"))
        assertTrue(window.contains("CrashReporter.report"))
        assertTrue(window.contains("conflict"))
    }

    @Test
    fun noStartRewriteOrOverlayRemains() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        assertTrue(!source.contains("retargetStartCommands"))
        assertTrue(!source.contains("applyTimerTaskRetarget"))
        assertTrue(!source.contains("pruneTimerTaskRetarget"))
        assertTrue(!source.contains("timerTaskRetarget"))
        val policy = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerTaskRetarget.kt",
        ).readText()
        assertTrue(!policy.contains("fun retargetStartCommands"))
        assertTrue(!policy.contains("fun applyTimerTaskRetarget"))
        assertTrue(!policy.contains("fun pruneTimerTaskRetarget"))
    }

    private fun reservation(sequence: Long, wallMs: Long) = TimerMutationReservation(
        stamps = listOf(
            SyncWireBounds.MutationStamp(
                deviceSequence = sequence,
                wallMs = wallMs,
                counter = 0,
                occurredAt = java.time.Instant.ofEpochMilli(wallMs).toString(),
            ),
        ),
        uuids = listOf(java.util.UUID.randomUUID()),
        lastUuidV7 = java.util.UUID.randomUUID().toString(),
    )

    private fun command(
        id: String,
        timerId: String,
        type: String,
        taskId: String? = "task-a",
        sequence: Long = 1,
        wallMs: Long = WallMs,
    ) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = timerId,
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = java.time.Instant.ofEpochMilli(wallMs).toString(),
        hlcWallMs = wallMs,
        hlcCounter = 0,
        observedElapsedMs = if (type == CommandType.Start) 0 else 100_000,
        taskId = taskId,
    )

    private fun timer(
        id: String,
        taskId: String?,
        status: String = TimerStatus.Running,
        phase: String = TimerPhase.Focus,
    ) = CanonicalTimer(
        id = id,
        phase = phase,
        status = status,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = java.time.Instant.ofEpochMilli(WallMs).toString(),
        taskId = taskId,
    )

    private fun emptyResponse() = SyncResponse(
        acknowledgements = emptyList(),
        revision = 7,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = java.time.Instant.ofEpochMilli(WallMs - 10_000).toString(),
        serverHlcWallMs = WallMs - 10_000,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )

    private companion object {
        const val WallMs = 1_767_225_600_000L
        const val ServerWallMs = 1_767_225_700_000L
        const val TaskB = "aaf83054-24b2-8c0e-901f-a974147bfe82"
    }
}
