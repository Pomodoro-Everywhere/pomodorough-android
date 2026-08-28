package me.egigoka.pomodorough.domain

import java.time.Instant
import java.time.ZoneId
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
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
    fun dailyFocusCountIgnoresPreviousDaysAndResetsCycleProgress() {
        val zone = ZoneId.of("UTC")
        val reference = Instant.parse("2026-07-22T12:00:00Z")
        val history = listOf(
            completedFocus("yesterday", "2026-07-21T23:59:00Z"),
            completedFocus("today-1", "2026-07-22T08:00:00Z"),
            completedFocus("today-2", "2026-07-22T09:00:00Z"),
            completedFocus("today-3", "2026-07-22T10:00:00Z"),
            completedFocus("today-4", "2026-07-22T11:00:00Z"),
            completedFocus("break", "2026-07-22T11:30:00Z").copy(phase = TimerPhase.ShortBreak),
        )

        val count = TimerPresentation.completedFocusCountForDay(history, reference, zone)

        assertEquals(4, count)
        assertEquals(4, TimerPresentation.longBreakProgress(count))
        assertEquals(1, TimerPresentation.longBreakProgress(count + 1))
        assertEquals(
            0,
            TimerPresentation.completedFocusCountForDay(
                history,
                Instant.parse("2026-07-23T12:00:00Z"),
                zone,
            ),
        )
    }

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
                    val projection = LegacyTimerReducer.replay(
                        initial,
                        initialHistory,
                        listOf(command(1, type, timerId = targetId, observedElapsedMs = 120_000)),
                    )
                    val case = "state=$initialStatus type=$type foreign=$foreignTimer"
                    val expectedStatus = when {
                        type == CommandType.Start -> TimerStatus.Running
                        initialStatus == null -> null
                        foreignTimer -> if (initialStatus == TimerStatus.Superseded) {
                            TimerStatus.Running
                        } else {
                            initialStatus
                        }
                        type == CommandType.Clear -> if (initialStatus == TimerStatus.Superseded) {
                            TimerStatus.Running
                        } else {
                            null
                        }
                        type == CommandType.Pause -> TimerStatus.Paused
                        type == CommandType.Resume -> TimerStatus.Running
                        type == CommandType.Finish -> TimerStatus.Completed
                        type == CommandType.Cancel -> TimerStatus.Cancelled
                        else -> initialStatus
                    }
                    val expectedId = when {
                        expectedStatus == null -> null
                        type == CommandType.Start -> targetId
                        initialStatus == null -> null
                        foreignTimer || type == CommandType.Clear && initialStatus == TimerStatus.Superseded ->
                            initial?.id
                        else -> targetId
                    }

                    assertEquals("$case timer status", expectedStatus, projection.timer?.status)
                    assertEquals("$case timer ID", expectedId, projection.timer?.id)
                }
            }
        }
    }

    @Test
    fun sameDeviceReplayUsesHybridClockOrder() {
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
        val expected = LegacyTimerReducer.replay(null, emptyList(), listOf(start, pause, resume))

        permutations(listOf(start, pause, resume)).forEach { input ->
            assertEquals(expected, LegacyTimerReducer.replay(null, emptyList(), input))
        }
        assertEquals(TimerStatus.Running, expected.timer?.status)
        assertEquals(0L, expected.timer?.elapsedAtAnchorMs)
        assertEquals(start.id, expected.timer?.lastIntent?.commandId)
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
            val once = LegacyTimerReducer.replay(initial, emptyList(), listOf(value))
            val twice = LegacyTimerReducer.replay(initial, emptyList(), listOf(value, value))

            assertEquals(type, once, twice)
        }
    }

    @Test
    fun latestStartReusesTimerIdentityAlreadyPresentInHistory() {
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

        val projection = LegacyTimerReducer.replay(
            null,
            listOf(completed),
            listOf(command(1, CommandType.Start)),
        )

        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(completed.timerId, projection.timer?.id)
        assertTrue(projection.history.isEmpty())
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

        val projection = LegacyTimerReducer.replay(current, listOf(superseded), listOf(resume))

        assertEquals("foreign-timer", projection.timer?.id)
        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(TimerPhase.ShortBreak, projection.timer?.phase)
        assertEquals(120_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals("task-1", projection.timer?.taskId)
        assertEquals(listOf("timer-1"), projection.history.map { it.timerId })
        assertEquals(listOf(TimerStatus.Superseded), projection.history.map { it.status })
    }

    @Test
    fun switchingHistoricalTargetPreservesTerminalCurrentAndHistoryIdentity() {
        val current = timer(TimerStatus.Completed).copy(id = "timer-current")
        val target = HistoryItem(
            id = "history-target",
            timerId = "timer-target",
            commandId = "old-cancel",
            phase = TimerPhase.Focus,
            status = TimerStatus.Cancelled,
            plannedDurationMs = 1_500_000,
            endedAt = "2026-01-01T00:25:00Z",
        )
        val finish = command(1, CommandType.Finish, timerId = target.timerId)

        val projection = LegacyTimerReducer.replay(current, listOf(target), listOf(finish))

        assertEquals("timer-target", projection.timer?.id)
        assertEquals(
            listOf(
                Triple("timer-target", TimerStatus.Completed, "history-target"),
                Triple("timer-current", TimerStatus.Completed, "timer-current"),
            ),
            projection.history.map { Triple(it.timerId, it.status, it.id) },
        )
    }

    @Test
    fun terminalHistoryMatchesCoreOrderAcrossTransitionsAndReplayModes() {
        val current = timer(TimerStatus.Running).copy(id = "timer-current")
        val tiedA = HistoryItem(
            id = "history-a",
            timerId = "timer-a",
            commandId = "cancel-a",
            phase = TimerPhase.Focus,
            status = TimerStatus.Cancelled,
            plannedDurationMs = 1_500_000,
            endedAt = "2026-01-01T00:10:00Z",
        )
        val tiedZ = HistoryItem(
            id = "history-z",
            timerId = "timer-z",
            commandId = "finish-z",
            phase = TimerPhase.Focus,
            status = TimerStatus.Completed,
            plannedDurationMs = 1_500_000,
            completedAt = "2026-01-01T00:10:00Z",
            endedAt = "2026-01-01T00:10:00Z",
        )
        val target = HistoryItem(
            id = "history-reactivated",
            timerId = "timer-reactivated",
            commandId = "cancel-reactivated",
            phase = TimerPhase.ShortBreak,
            status = TimerStatus.Cancelled,
            plannedDurationMs = 300_000,
            endedAt = "2026-01-01T00:09:00Z",
        )
        val old = HistoryItem(
            id = "history-old",
            timerId = "timer-old",
            commandId = "supersede-old",
            phase = TimerPhase.LongBreak,
            status = TimerStatus.Superseded,
            plannedDurationMs = 900_000,
            endedAt = "2026-01-01T00:01:00Z",
        )
        val canonicalHistory = listOf(old, tiedZ, target, tiedA)

        listOf(
            CommandType.Finish to TimerStatus.Completed,
            CommandType.Cancel to TimerStatus.Cancelled,
        ).forEach { (terminalType, terminalStatus) ->
            listOf(CommandType.Pause, CommandType.Resume).forEach { reactivationType ->
                val terminal = command(
                    sequence = 1,
                    type = terminalType,
                    timerId = current.id,
                    occurredAt = "2026-01-01T00:11:00Z",
                    observedElapsedMs = 120_000,
                )
                val reactivation = command(
                    sequence = 2,
                    type = reactivationType,
                    timerId = target.timerId,
                    occurredAt = "2026-01-01T00:12:00Z",
                    observedElapsedMs = 120_000,
                )
                val replacement = command(
                    sequence = 3,
                    type = CommandType.Start,
                    timerId = "timer-new",
                    occurredAt = "2026-01-01T00:13:00Z",
                )
                val expected = listOf(
                    HistoryItem(
                        id = target.id,
                        timerId = target.timerId,
                        commandId = replacement.id,
                        phase = target.phase,
                        status = TimerStatus.Superseded,
                        plannedDurationMs = target.plannedDurationMs,
                        endedAt = replacement.occurredAt,
                        pending = true,
                    ),
                    HistoryItem(
                        id = current.id,
                        timerId = current.id,
                        commandId = terminal.id,
                        phase = current.phase,
                        status = terminalStatus,
                        plannedDurationMs = current.plannedDurationMs,
                        completedAt = terminal.occurredAt.takeIf {
                            terminalStatus == TimerStatus.Completed
                        },
                        endedAt = terminal.occurredAt,
                        pending = true,
                    ),
                    tiedA,
                    tiedZ,
                    old,
                )
                val batch = LegacyTimerReducer.replay(
                    current,
                    canonicalHistory,
                    listOf(replacement, reactivation, terminal),
                )
                var incrementalHistory = canonicalHistory
                val arrivalOrder = listOf(terminal, reactivation, replacement)
                arrivalOrder.indices.forEach { lastIndex ->
                    val projection = LegacyTimerReducer.replay(
                        current,
                        canonicalHistory,
                        arrivalOrder.take(lastIndex + 1),
                    )
                    incrementalHistory = projection.history
                }
                val case = "$terminalType/$reactivationType"

                assertEquals("$case batch", expected, batch.history)
                assertEquals("$case incremental", expected, incrementalHistory)
            }
        }
    }

    @Test
    fun canonicalSyncKeepsOptimisticArrivalOrder() {
        val current = timer(TimerStatus.Running).copy(id = "timer-local")
        val tiedZ = completedFocus("timer-z", "2026-01-01T00:10:00Z").copy(id = "history-z")
        val tiedA = completedFocus("timer-a", "2026-01-01T00:10:00Z").copy(id = "history-a")
        val cancel = command(
            sequence = 1,
            type = CommandType.Cancel,
            timerId = current.id,
            occurredAt = "2026-01-01T00:11:00Z",
            observedElapsedMs = 120_000,
        )
        val optimistic = LegacyTimerReducer.replay(current, listOf(tiedZ, tiedA), listOf(cancel))
        val canonicalHistory = optimistic.history.map { it.copy(pending = false) }.reversed()

        val synced = LegacyTimerReducer.replay(optimistic.timer, canonicalHistory, emptyList())

        assertEquals(optimistic.history.map { it.copy(pending = false) }, synced.history)
    }

    @Test
    fun historicalIdentitySurvivesReactivationAndLaterSwitch() {
        val target = HistoryItem(
            id = "history-target",
            timerId = "timer-target",
            commandId = "old-cancel",
            phase = TimerPhase.Focus,
            status = TimerStatus.Cancelled,
            plannedDurationMs = 1_500_000,
            endedAt = "2026-01-01T00:25:00Z",
        )
        val pause = command(1, CommandType.Pause, timerId = target.timerId)
        val start = command(2, CommandType.Start, timerId = "timer-other")

        val projection = LegacyTimerReducer.replay(null, listOf(target), listOf(start, pause))

        assertEquals("timer-other", projection.timer?.id)
        assertEquals(1, projection.history.size)
        assertEquals("history-target", projection.history.single().id)
        assertEquals("timer-target", projection.history.single().timerId)
        assertEquals(TimerStatus.Superseded, projection.history.single().status)
    }

    @Test
    fun laterActionRestoresTimerClearedEarlierInReplay() {
        val commands = listOf(
            command(1, CommandType.Start),
            command(2, CommandType.Clear),
            command(3, CommandType.Pause, observedElapsedMs = 123_000),
        )

        val projection = LegacyTimerReducer.replay(null, emptyList(), commands)

        assertEquals(TimerStatus.Paused, projection.timer?.status)
        assertEquals(123_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals(commands.last().id, projection.timer?.lastIntent?.commandId)
        assertTrue(projection.history.isEmpty())
    }

    @Test
    fun replayOrdersPendingCommandsByHybridClock() {
        val start = command(sequence = 1, type = CommandType.Start, occurredAt = "2026-01-01T00:00:00Z")
        val pause = command(
            sequence = 2,
            type = CommandType.Pause,
            occurredAt = "2026-01-01T00:05:00Z",
            observedElapsedMs = 300_000,
        )

        val projection = LegacyTimerReducer.replay(null, emptyList(), listOf(pause, start))

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

        assertEquals(600_000, TimerPresentation.elapsedAt(timer, 1_767_225_700_000))
        assertEquals(540_000, TimerPresentation.elapsedAt(timer, 1_767_225_500_000))
    }

    @Test
    fun latestPauseCancelAndResumeOverrideDeadlineCompletion() {
        listOf(
            CommandType.Pause to TimerStatus.Paused,
            CommandType.Cancel to TimerStatus.Cancelled,
            CommandType.Resume to TimerStatus.Running,
        ).forEach { (type, expectedStatus) ->
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

            val projection = LegacyTimerReducer.replay(running, emptyList(), listOf(lateCommand))

            assertEquals(type, expectedStatus, projection.timer?.status)
            assertEquals(type, 40_000L, projection.timer?.elapsedAtAnchorMs)
            assertEquals(type, lateCommand.occurredAt, projection.timer?.anchorAt)
            assertEquals(type, lateCommand.id, projection.timer?.lastIntent?.commandId)
            if (type == CommandType.Cancel) {
                assertEquals(type, listOf(TimerStatus.Cancelled), projection.history.map { it.status })
            } else {
                assertTrue(type, projection.history.isEmpty())
            }
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

        val projection = LegacyTimerReducer.projectAt(running, emptyList(), 1_767_225_631_000)

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

        val projection = LegacyTimerReducer.replay(running, emptyList(), listOf(finish))
        val completion = projection.history.single()

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertEquals(finish.occurredAt, projection.timer?.anchorAt)
        assertEquals(finish.id, projection.timer?.lastIntent?.commandId)
        assertEquals(finish.id, completion.commandId)
        assertEquals(running.phase, completion.phase)
        assertEquals(finish.occurredAt, completion.completedAt)
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

        val projection = LegacyTimerReducer.replay(running, emptyList(), listOf(replacement))

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

        val projection = LegacyTimerReducer.replay(running, emptyList(), listOf(finish))

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

        val projection = LegacyTimerReducer.replay(superseded, emptyList(), listOf(resume))

        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(180_000L, projection.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun clearOnlyRemovesTerminalTimer() {
        val clear = command(sequence = 1, type = CommandType.Clear)

        assertNull(LegacyTimerReducer.replay(timer(TimerStatus.Completed), emptyList(), listOf(clear)).timer)
        assertNull(LegacyTimerReducer.replay(timer(TimerStatus.Cancelled), emptyList(), listOf(clear)).timer)
        assertNull(LegacyTimerReducer.replay(timer(TimerStatus.Superseded), emptyList(), listOf(clear)).timer)
        assertNull(LegacyTimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(clear)).timer)
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

        val projection = LegacyTimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(start))

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

        val projection = LegacyTimerReducer.replay(canonical, emptyList(), listOf(pause))

        assertSame(canonical, projection.timer)
    }

    @Test
    fun observedElapsedIsClampedForPauseAndCancel() {
        val pause = command(
            sequence = 1,
            type = CommandType.Pause,
            observedElapsedMs = 9_000_000,
        )
        val paused = LegacyTimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(pause)).timer
        val cancel = command(
            sequence = 2,
            type = CommandType.Cancel,
            observedElapsedMs = -1,
        )

        val cancelled = LegacyTimerReducer.replay(paused, emptyList(), listOf(cancel)).timer

        assertEquals(1_500_000L, paused?.elapsedAtAnchorMs)
        assertEquals(0L, cancelled?.elapsedAtAnchorMs)
    }

    @Test
    fun duplicateFinishCommandCreatesOneHistoryEntry() {
        val finish = command(sequence = 1, type = CommandType.Finish)

        val projection = LegacyTimerReducer.replay(
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

        assertEquals(42_000, TimerPresentation.elapsedAt(timer, Long.MAX_VALUE))
    }

    @Test
    fun pausedTimerDoesNotAccumulateWallTime() {
        val paused = timer(
            status = TimerStatus.Paused,
            elapsedMs = 120_000,
            anchorAt = "2020-01-01T00:00:00Z",
        )

        assertEquals(120_000, TimerPresentation.elapsedAt(paused, Long.MAX_VALUE))
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

        val projection = LegacyTimerReducer.replay(
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

    private fun completedFocus(id: String, completedAt: String) = HistoryItem(
        id = id,
        timerId = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = completedAt,
        endedAt = completedAt,
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
