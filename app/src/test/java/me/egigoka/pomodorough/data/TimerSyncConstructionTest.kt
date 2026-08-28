package me.egigoka.pomodorough.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSyncConstructionTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun syncRequestPreservesWireBytesAndOrderWhileEncodingTimerCommands() {
        val commands = listOf(command("command-b"), command("command-a"))
        val lateDuration = duration("duration-late", 20L)
        val earlyDuration = duration("duration-early", 10L)
        val taskB = task("task-b")
        val taskA = task("task-a")
        val lateAutoStart = autoStart("auto-late", 20L)
        val earlyAutoStart = autoStart("auto-early", 10L)
        val lateSelection = selection("selection-late", 20L)
        val earlySelection = selection("selection-early", 10L)
        val queues = PendingSyncQueues(
            commands = commands,
            taskOperations = listOf(taskB, taskA),
            durationOperations = listOf(lateDuration, earlyDuration),
            autoStartOperations = listOf(lateAutoStart, earlyAutoStart),
            selectedTaskOperations = listOf(lateSelection, earlySelection),
        )

        val request = TimerSyncConstruction.syncAttempt(
            identity = SyncAttemptIdentity(4L, "construction-attempt"),
            deviceId = "device",
            revision = 7L,
            eligibleCommands = commands,
            queues = queues,
            sentPhysicalMs = 100L,
            sentElapsedRealtimeMs = 90L,
            selectedPhase = TimerPhase.Focus,
            selectedPhaseGeneration = 3L,
        ).request
        val expected = SyncRequest(
            deviceId = "device",
            lastRevision = 7L,
            commands = commands.map { it.copy(physicalOccurredAt = null) },
            durationOperations = listOf(earlyDuration, lateDuration),
            taskOperations = listOf(taskB, taskA),
            autoStartOperations = listOf(earlyAutoStart, lateAutoStart),
            selectedTaskOperations = listOf(earlySelection, lateSelection),
        )

        assertEquals(expected, request)
        assertEquals(json.encodeToString(expected), json.encodeToString(request))
        assertNull(request.commands.first().physicalOccurredAt)
        assertSame(earlyDuration, request.durationOperations[0])
        assertSame(taskB, request.taskOperations[0])
        assertSame(earlyAutoStart, request.autoStartOperations[0])
        assertSame(earlySelection, request.selectedTaskOperations[0])
    }

    @Test
    fun bootstrapRequestPreservesPayloadOrderAndExcludesLocalPayloadForKeepRemote() {
        val commands = listOf(command("command-b"), command("command-a"))
        val task = task("task")
        val duration = duration("duration", 10L)
        val autoStart = autoStart("auto", 10L)
        val selection = selection("selection", 10L)
        val queues = PendingSyncQueues(
            commands = commands,
            taskOperations = listOf(task),
            durationOperations = listOf(duration),
            autoStartOperations = listOf(autoStart),
            selectedTaskOperations = listOf(selection),
        )

        val merge = TimerSyncConstruction.bootstrapRequest(
            deviceId = "device",
            revision = 7L,
            strategy = BootstrapStrategy.Merge,
            eligibleCommands = commands,
            queues = queues,
        )
        val expected = BootstrapResolutionRequest(
            requestId = merge.requestId,
            deviceId = "device",
            expectedRevision = 7L,
            strategy = BootstrapStrategy.Merge,
            commands = commands.map { it.copy(physicalOccurredAt = null) },
            taskOperations = listOf(task),
            durationOperations = listOf(duration),
            autoStartOperations = listOf(autoStart),
            selectedTaskOperations = listOf(selection),
        )

        assertEquals(expected, merge)
        assertEquals(json.encodeToString(expected), json.encodeToString(merge))
        assertSame(task, merge.taskOperations[0])
        assertSame(duration, merge.durationOperations[0])
        assertSame(autoStart, merge.autoStartOperations?.get(0))
        assertSame(selection, merge.selectedTaskOperations?.get(0))

        val keepRemote = TimerSyncConstruction.bootstrapRequest(
            deviceId = "device",
            revision = 7L,
            strategy = BootstrapStrategy.KeepRemote,
            eligibleCommands = commands,
            queues = queues,
        )
        assertTrue(keepRemote.commands.isEmpty())
        assertTrue(keepRemote.taskOperations.isEmpty())
        assertTrue(keepRemote.durationOperations.isEmpty())
        assertTrue(keepRemote.autoStartOperations.orEmpty().isEmpty())
        assertTrue(keepRemote.selectedTaskOperations.orEmpty().isEmpty())
    }

    @Test
    fun timerCommandRequestEncoderStripsOnlyPhysicalOccurrence() {
        val command = command("command")

        val encoded = TimerCommandRequestEncoder.encode(command)

        assertEquals(command.copy(physicalOccurredAt = null), encoded)
        assertNull(encoded.physicalOccurredAt)
        assertEquals("2026-08-25T10:00:00Z", command.physicalOccurredAt)
    }

    private fun command(id: String) = TimerCommand(
        id = id,
        deviceSequence = 1L,
        timerId = "timer-$id",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = "2026-08-25T10:00:00Z",
        hlcWallMs = 1L,
        hlcCounter = 0L,
        observedElapsedMs = 0L,
        physicalOccurredAt = "2026-08-25T10:00:00Z",
    )

    private fun duration(id: String, wallMs: Long) = DurationOperation(
        id = id,
        phase = TimerPhase.Focus,
        durationMs = 1_500_000L,
        occurredAt = "2026-08-25T10:00:00Z",
        hlcWallMs = wallMs,
        hlcCounter = 0L,
    )

    private fun task(id: String) = TaskOperation(
        id = id,
        taskId = "focus-$id",
        type = TaskOperationType.Upsert,
        title = id,
        occurredAt = "2026-08-25T10:00:00Z",
        hlcWallMs = 1L,
        hlcCounter = 0L,
    )

    private fun autoStart(id: String, wallMs: Long) = AutoStartOperation(
        id = id,
        deviceId = "device-$id",
        enabled = true,
        occurredAt = "2026-08-25T10:00:00Z",
        hlcWallMs = wallMs,
        hlcCounter = 0L,
    )

    private fun selection(id: String, wallMs: Long) = SelectedTaskOperation(
        id = id,
        taskId = "focus-$id",
        occurredAt = "2026-08-25T10:00:00Z",
        hlcWallMs = wallMs,
        hlcCounter = 0L,
    )
}
