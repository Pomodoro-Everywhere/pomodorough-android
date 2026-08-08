package me.egigoka.pomodorough.domain

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerReducerTest {
    @Test
    fun everyStateCommandAndTimerIdentityCombinationMatchesServerTransitionTable() {
        val states = listOf<String?>(
            null,
            TimerStatus.Running,
            TimerStatus.Paused,
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val commands = listOf(
            CommandType.Start,
            CommandType.Pause,
            CommandType.Resume,
            CommandType.Finish,
            CommandType.Cancel,
            CommandType.Clear,
        )

        states.forEach { initialStatus ->
            commands.forEach { type ->
                listOf(false, true).forEach { foreignTimer ->
                    val initial = initialStatus?.let {
                        if (it == TimerStatus.Superseded) {
                            timer(TimerStatus.Running).copy(id = "timer-current")
                        } else {
                            timer(it)
                        }
                    }
                    val initialHistory = if (initialStatus == TimerStatus.Superseded) {
                        listOf(
                            me.egigoka.pomodorough.data.HistoryItem(
                                id = "timer-1",
                                timerId = "timer-1",
                                commandId = "setup-replacement",
                                phase = TimerPhase.Focus,
                                status = TimerStatus.Superseded,
                                plannedDurationMs = 1_500_000,
                                endedAt = "2026-01-01T00:01:00Z",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                    val targetId = if (foreignTimer) "foreign-timer" else "timer-1"
                    val projection = TimerReducer.replay(
                        initial,
                        initialHistory,
                        listOf(command(1, type, timerId = targetId, observedElapsedMs = 120_000)),
                    )
                    val case = "state=$initialStatus type=$type foreign=$foreignTimer"
                    if (initialStatus == TimerStatus.Superseded) {
                        when {
                            type == CommandType.Start && foreignTimer -> {
                                assertEquals("$case timer status", TimerStatus.Running, projection.timer?.status)
                                assertEquals("$case timer ID", targetId, projection.timer?.id)
                                assertEquals(
                                    "$case history",
                                    listOf("timer-current", "timer-1"),
                                    projection.history.map { it.timerId },
                                )
                            }

                            type == CommandType.Resume && !foreignTimer -> {
                                assertEquals("$case timer status", TimerStatus.Running, projection.timer?.status)
                                assertEquals("$case timer ID", "timer-1", projection.timer?.id)
                                assertEquals(
                                    "$case history",
                                    listOf("timer-current"),
                                    projection.history.map { it.timerId },
                                )
                            }

                            else -> {
                                assertEquals("$case timer", initial, projection.timer)
                                assertEquals("$case history", initialHistory, projection.history)
                            }
                        }
                        return@forEach
                    }
                    val startApplies = type == CommandType.Start &&
                        (initial == null || foreignTimer)
                    val sameActive = !foreignTimer && initialStatus in activeStatuses
                    val expectedStatus = when {
                        startApplies -> TimerStatus.Running
                        type == CommandType.Pause && initialStatus == TimerStatus.Running && !foreignTimer ->
                            TimerStatus.Paused
                        type == CommandType.Resume &&
                            !foreignTimer && initialStatus in resumableStatuses -> TimerStatus.Running
                        type == CommandType.Finish && sameActive -> TimerStatus.Completed
                        type == CommandType.Cancel && sameActive -> TimerStatus.Cancelled
                        type == CommandType.Clear &&
                            !foreignTimer && initialStatus in terminalStatuses -> null
                        else -> initialStatus
                    }
                    val expectedId = when {
                        expectedStatus == null -> null
                        startApplies -> targetId
                        else -> initial?.id
                    }
                    val expectedHistoryStatus = when {
                        startApplies && initialStatus in activeStatuses -> TimerStatus.Superseded
                        type == CommandType.Finish && sameActive -> TimerStatus.Completed
                        type == CommandType.Cancel && sameActive -> TimerStatus.Cancelled
                        else -> null
                    }

                    assertEquals("$case timer status", expectedStatus, projection.timer?.status)
                    assertEquals("$case timer ID", expectedId, projection.timer?.id)
                    assertEquals(
                        "$case history status",
                        listOfNotNull(expectedHistoryStatus),
                        projection.history.map { it.status },
                    )
                    if (expectedHistoryStatus != null) {
                        assertEquals("$case history timer", initial?.id, projection.history.single().timerId)
                        assertEquals("$case history command", "command-1", projection.history.single().commandId)
                        assertTrue("$case history pending", projection.history.single().pending)
                    }
                }
            }
        }
    }

    @Test
    fun sameDeviceReplayUsesSequenceNotInputOrHybridClockOrder() {
        val start = command(1, CommandType.Start).copy(
            id = "start",
            hlcWallMs = 300,
            hlcCounter = 9,
        )
        val pause = command(2, CommandType.Pause, observedElapsedMs = 120_000).copy(
            id = "pause",
            hlcWallMs = 100,
            hlcCounter = 0,
        )
        val resume = command(3, CommandType.Resume, observedElapsedMs = 180_000).copy(
            id = "resume",
            hlcWallMs = 200,
            hlcCounter = 1,
        )
        val expected = TimerReducer.replay(null, emptyList(), listOf(start, pause, resume))

        permutations(listOf(start, pause, resume)).forEach { input ->
            assertEquals(expected, TimerReducer.replay(null, emptyList(), input))
        }
        assertEquals(TimerStatus.Running, expected.timer?.status)
        assertEquals(180_000L, expected.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun exactCommandReplayIsIdempotentForEveryCommandType() {
        val initialByCommand = mapOf(
            CommandType.Start to null,
            CommandType.Pause to timer(TimerStatus.Running),
            CommandType.Resume to timer(TimerStatus.Paused),
            CommandType.Finish to timer(TimerStatus.Running),
            CommandType.Cancel to timer(TimerStatus.Paused),
            CommandType.Clear to timer(TimerStatus.Completed),
        )

        initialByCommand.forEach { (type, initial) ->
            val value = command(1, type, observedElapsedMs = 120_000)
            val once = TimerReducer.replay(initial, emptyList(), listOf(value))
            val twice = TimerReducer.replay(initial, emptyList(), listOf(value, value))

            assertEquals(type, once, twice)
        }
    }

    @Test
    fun startCannotReuseTimerIdentityAlreadyPresentInHistory() {
        val completed = me.egigoka.pomodorough.data.HistoryItem(
            id = "timer-1",
            timerId = "timer-1",
            commandId = "finish",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 1_500_000,
            completedAt = "2026-01-01T00:25:00Z",
            endedAt = "2026-01-01T00:25:00Z",
        )

        val projection = TimerReducer.replay(
            null,
            listOf(completed),
            listOf(command(1, CommandType.Start)),
        )

        assertNull(projection.timer)
        assertEquals(listOf(completed), projection.history)
    }

    @Test
    fun resumeOfSupersededForeignTimerSupersedesCurrentTimer() {
        val current = timer(TimerStatus.Running)
        val superseded = me.egigoka.pomodorough.data.HistoryItem(
            id = "foreign-timer:start-current",
            timerId = "foreign-timer",
            commandId = "start-current",
            phase = TimerPhase.ShortBreak,
            status = TimerStatus.Superseded,
            plannedDurationMs = 300_000,
            endedAt = "2026-01-01T00:05:00Z",
            taskId = "task-1",
        )
        val resume = command(
            sequence = 1,
            type = CommandType.Resume,
            timerId = "foreign-timer",
            observedElapsedMs = 120_000,
        )

        val projection = TimerReducer.replay(current, listOf(superseded), listOf(resume))

        assertEquals("foreign-timer", projection.timer?.id)
        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(TimerPhase.ShortBreak, projection.timer?.phase)
        assertEquals(120_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals("task-1", projection.timer?.taskId)
        assertEquals(listOf("timer-1"), projection.history.map { it.timerId })
        assertEquals(listOf(TimerStatus.Superseded), projection.history.map { it.status })
    }

    @Test
    fun replayOrdersPendingCommandsByDeviceSequence() {
        val start = command(sequence = 1, type = CommandType.Start, occurredAt = "2026-01-01T00:00:00Z")
        val pause = command(
            sequence = 2,
            type = CommandType.Pause,
            occurredAt = "2026-01-01T00:05:00Z",
            observedElapsedMs = 300_000,
        )

        val projection = TimerReducer.replay(null, emptyList(), listOf(pause, start))

        assertEquals(TimerStatus.Paused, projection.timer?.status)
        assertEquals(300_000L, projection.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun elapsedAddsWallTimeAndClampsToDuration() {
        val timer = timer(
            status = TimerStatus.Running,
            durationMs = 600_000,
            elapsedMs = 540_000,
            anchorAt = "2026-01-01T00:00:00Z",
        )

        assertEquals(600_000, TimerReducer.elapsedAt(timer, 1_767_225_700_000))
        assertEquals(540_000, TimerReducer.elapsedAt(timer, 1_767_225_500_000))
    }

    @Test
    fun deadlineAutoCompletionPrecedesLatePauseCancelAndResume() {
        listOf(CommandType.Pause, CommandType.Cancel, CommandType.Resume).forEach { type ->
            val running = timer(
                status = TimerStatus.Running,
                durationMs = 60_000,
                elapsedMs = 30_000,
                anchorAt = "2026-01-01T00:00:00Z",
                taskId = "task-source",
            ).copy(phase = TimerPhase.ShortBreak)
            val lateCommand = command(
                sequence = 1,
                type = type,
                occurredAt = "2026-01-01T00:00:31Z",
                observedElapsedMs = 40_000,
            )

            val projection = TimerReducer.replay(running, emptyList(), listOf(lateCommand))
            val completion = projection.history.single()

            assertEquals(type, TimerStatus.Completed, projection.timer?.status)
            assertEquals(type, 60_000L, projection.timer?.elapsedAtAnchorMs)
            assertEquals(type, "2026-01-01T00:00:30Z", projection.timer?.anchorAt)
            assertNull(type, projection.timer?.lastIntent)
            assertEquals(type, running.id, completion.id)
            assertNull(type, completion.commandId)
            assertEquals(type, running.phase, completion.phase)
            assertEquals(type, running.plannedDurationMs, completion.plannedDurationMs)
            assertEquals(type, running.taskId, completion.taskId)
            assertEquals(type, "2026-01-01T00:00:30Z", completion.completedAt)
            assertEquals(type, completion.completedAt, completion.endedAt)
            assertTrue(type, !completion.pending)
        }
    }

    @Test
    fun projectAtCompletesExpiredTimerWithoutCreatingSharedCommand() {
        val running = timer(
            status = TimerStatus.Running,
            durationMs = 60_000,
            elapsedMs = 30_000,
            anchorAt = "2026-01-01T00:00:00Z",
        )

        val projection = TimerReducer.projectAt(running, emptyList(), 1_767_225_631_000)

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertNull(projection.timer?.lastIntent)
        assertNull(projection.history.single().commandId)
        assertEquals("2026-01-01T00:00:30Z", projection.history.single().endedAt)
    }

    @Test
    fun lateFinishClaimsDeadlineCompletionWithoutMovingCompletionTime() {
        val running = timer(
            status = TimerStatus.Running,
            durationMs = 60_000,
            elapsedMs = 30_000,
            anchorAt = "2026-01-01T00:00:00Z",
        ).copy(phase = TimerPhase.LongBreak)
        val finish = command(
            sequence = 1,
            type = CommandType.Finish,
            occurredAt = "2026-01-01T00:00:31Z",
            observedElapsedMs = 60_000,
        )

        val projection = TimerReducer.replay(running, emptyList(), listOf(finish))
        val completion = projection.history.single()

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertEquals("2026-01-01T00:00:30Z", projection.timer?.anchorAt)
        assertEquals(finish.id, projection.timer?.lastIntent?.commandId)
        assertEquals(finish.id, completion.commandId)
        assertEquals(running.phase, completion.phase)
        assertEquals("2026-01-01T00:00:30Z", completion.completedAt)
        assertEquals(completion.completedAt, completion.endedAt)
        assertTrue(completion.pending)
    }

    @Test
    fun startAfterDeadlineKeepsAutomaticCompletionAndStartsReplacement() {
        val running = timer(
            status = TimerStatus.Running,
            durationMs = 60_000,
            elapsedMs = 30_000,
            anchorAt = "2026-01-01T00:00:00Z",
        )
        val replacement = command(
            sequence = 1,
            type = CommandType.Start,
            timerId = "timer-replacement",
            occurredAt = "2026-01-01T00:00:31Z",
        )

        val projection = TimerReducer.replay(running, emptyList(), listOf(replacement))

        assertEquals(replacement.timerId, projection.timer?.id)
        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(listOf(TimerStatus.Completed), projection.history.map { it.status })
        assertEquals(running.id, projection.history.single().timerId)
        assertNull(projection.history.single().commandId)
    }

    @Test
    fun finishCreatesQueuedCompletion() {
        val running = timer(status = TimerStatus.Running, taskId = "task-1")
        val finish = command(
            sequence = 1,
            type = CommandType.Finish,
            occurredAt = "2026-01-01T00:25:00Z",
            observedElapsedMs = 1_500_000,
        )

        val projection = TimerReducer.replay(running, emptyList(), listOf(finish))

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertEquals(1_500_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals(1, projection.history.size)
        assertEquals("timer-1", projection.history.single().id)
        assertEquals("command-1", projection.history.single().commandId)
        assertTrue(projection.history.single().pending)
        assertEquals("task-1", projection.history.single().taskId)
    }

    @Test
    fun supersededTimerCanResume() {
        val superseded = timer(status = TimerStatus.Superseded, elapsedMs = 120_000)
        val resume = command(
            sequence = 1,
            type = CommandType.Resume,
            observedElapsedMs = 180_000,
        )

        val projection = TimerReducer.replay(superseded, emptyList(), listOf(resume))

        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(180_000L, projection.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun clearOnlyRemovesTerminalTimer() {
        val clear = command(sequence = 1, type = CommandType.Clear)

        assertNull(TimerReducer.replay(timer(TimerStatus.Completed), emptyList(), listOf(clear)).timer)
        assertNull(TimerReducer.replay(timer(TimerStatus.Cancelled), emptyList(), listOf(clear)).timer)
        assertEquals(
            TimerStatus.Superseded,
            TimerReducer.replay(timer(TimerStatus.Superseded), emptyList(), listOf(clear)).timer?.status,
        )
        assertEquals(
            TimerStatus.Running,
            TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(clear)).timer?.status,
        )
    }

    @Test
    fun startReplacesCurrentTimer() {
        val start = command(
            sequence = 1,
            type = CommandType.Start,
            timerId = "replacement",
            phase = TimerPhase.ShortBreak,
            plannedDurationMs = 300_000,
        )

        val projection = TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(start))

        assertEquals("replacement", projection.timer?.id)
        assertEquals(TimerPhase.ShortBreak, projection.timer?.phase)
        assertEquals(300_000L, projection.timer?.plannedDurationMs)
        assertEquals(TimerStatus.Running, projection.timer?.status)
    }

    @Test
    fun mismatchedTimerCommandDoesNotChangeProjection() {
        val canonical = timer(TimerStatus.Running)
        val pause = command(
            sequence = 1,
            type = CommandType.Pause,
            timerId = "different-timer",
            observedElapsedMs = 60_000,
        )

        val projection = TimerReducer.replay(canonical, emptyList(), listOf(pause))

        assertSame(canonical, projection.timer)
    }

    @Test
    fun observedElapsedIsClampedForPauseAndCancel() {
        val pause = command(
            sequence = 1,
            type = CommandType.Pause,
            observedElapsedMs = 9_000_000,
        )
        val paused = TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(pause)).timer
        val cancel = command(
            sequence = 2,
            type = CommandType.Cancel,
            observedElapsedMs = -1,
        )

        val cancelled = TimerReducer.replay(paused, emptyList(), listOf(cancel)).timer

        assertEquals(1_500_000L, paused?.elapsedAtAnchorMs)
        assertEquals(0L, cancelled?.elapsedAtAnchorMs)
    }

    @Test
    fun duplicateFinishCommandCreatesOneHistoryEntry() {
        val finish = command(sequence = 1, type = CommandType.Finish)

        val projection = TimerReducer.replay(
            timer(TimerStatus.Running),
            emptyList(),
            listOf(finish, finish.copy(deviceSequence = 2)),
        )

        assertEquals(1, projection.history.size)
        assertEquals("command-1", projection.history.single().commandId)
    }

    @Test
    fun invalidAnchorKeepsStoredElapsed() {
        val timer = timer(
            status = TimerStatus.Running,
            elapsedMs = 42_000,
            anchorAt = "not-an-instant",
        )

        assertEquals(42_000, TimerReducer.elapsedAt(timer, Long.MAX_VALUE))
    }

    @Test
    fun pausedTimerDoesNotAccumulateWallTime() {
        val paused = timer(
            status = TimerStatus.Paused,
            elapsedMs = 120_000,
            anchorAt = "2020-01-01T00:00:00Z",
        )

        assertEquals(120_000, TimerReducer.elapsedAt(paused, Long.MAX_VALUE))
    }

    @Test
    fun unknownCommandLeavesStateAndHistoryUnchanged() {
        val canonical = timer(TimerStatus.Paused)
        val history = listOf(
            me.egigoka.pomodorough.data.HistoryItem(
                id = "history-1",
                timerId = "old-timer",
                phase = TimerPhase.Focus,
                status = TimerStatus.Completed,
                plannedDurationMs = 1_500_000,
            ),
        )

        val projection = TimerReducer.replay(
            canonical,
            history,
            listOf(command(sequence = 1, type = "unsupported")),
        )

        assertSame(canonical, projection.timer)
        assertEquals(history, projection.history)
    }

    private fun timer(
        status: String,
        durationMs: Long = 1_500_000,
        elapsedMs: Long = 0,
        anchorAt: String = "2026-01-01T00:00:00Z",
        taskId: String? = null,
    ) = CanonicalTimer(
        id = "timer-1",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = durationMs,
        elapsedAtAnchorMs = elapsedMs,
        anchorAt = anchorAt,
        taskId = taskId,
    )

    private fun command(
        sequence: Long,
        type: String,
        timerId: String = "timer-1",
        phase: String = TimerPhase.Focus,
        plannedDurationMs: Long = 1_500_000,
        occurredAt: String = "2026-01-01T00:10:00Z",
        observedElapsedMs: Long = 0,
    ) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = timerId,
        type = type,
        phase = phase,
        plannedDurationMs = plannedDurationMs,
        occurredAt = occurredAt,
        hlcWallMs = 1_767_225_600_000 + sequence,
        hlcCounter = 0,
        observedElapsedMs = observedElapsedMs,
    )

    private fun <T> permutations(values: List<T>): List<List<T>> = when (values.size) {
        0, 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index })
                .map { listOf(value) + it }
        }
    }

    private companion object {
        val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
        val resumableStatuses = setOf(TimerStatus.Paused, TimerStatus.Superseded)
        val terminalStatuses = setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
        )
    }
}
