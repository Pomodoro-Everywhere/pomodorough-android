package me.egigoka.pomodorough.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTaskRetargetTest {
    @Test
    fun startCommandRewriteRetargetsOnlyMatchingTimer() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start)
        val pause = command(id = "pause-1", timerId = "timer-1", type = CommandType.Pause)
        val other = command(id = "start-2", timerId = "timer-2", type = CommandType.Start)

        val rewritten = retargetStartCommands(listOf(start, pause, other), "timer-1", "task-b")

        assertEquals("task-b", rewritten[0].taskId)
        assertEquals(start.taskId, rewritten[1].taskId)
        assertEquals(other.taskId, rewritten[2].taskId)
        assertEquals("task-a", start.taskId)
    }

    @Test
    fun startCommandRewriteToUnassignedClearsTask() {
        val start = command(id = "start-1", timerId = "timer-1", type = CommandType.Start)

        val rewritten = retargetStartCommands(listOf(start), "timer-1", null)

        assertNull(rewritten.single().taskId)
    }

    @Test
    fun viewOverrideFollowsRetargetForTimerAndHistory() {
        val timer = timer(id = "timer-1", taskId = "task-a")
        val history = listOf(
            historyItem(id = "history-1", timerId = "timer-1", taskId = "task-a"),
            historyItem(id = "history-2", timerId = "timer-0", taskId = "task-a"),
        )

        val (retargetedTimer, retargetedHistory) =
            applyTimerTaskRetarget(timer, history, mapOf("timer-1" to "task-b"))

        assertEquals("task-b", retargetedTimer?.taskId)
        assertEquals("task-b", retargetedHistory[0].taskId)
        assertEquals("task-a", retargetedHistory[1].taskId)
    }

    @Test
    fun emptyRetargetLeavesViewUntouched() {
        val timer = timer(id = "timer-1", taskId = "task-a")
        val history = listOf(historyItem(id = "history-1", timerId = "timer-1", taskId = "task-a"))

        val (sameTimer, sameHistory) = applyTimerTaskRetarget(timer, history, emptyMap())

        assertEquals(timer, sameTimer)
        assertEquals(history, sameHistory)
    }

    @Test
    fun pruneKeepsOnlyLiveTimerAndHistoryEntries() {
        val timer = timer(id = "timer-1", taskId = "task-b")
        val history = listOf(historyItem(id = "history-1", timerId = "timer-1", taskId = "task-b"))
        val retarget = mapOf("timer-1" to "task-b", "timer-stale" to "task-c")

        val pruned = pruneTimerTaskRetarget(retarget, timer, history)

        assertEquals(mapOf("timer-1" to "task-b"), pruned)
    }

    @Test
    fun pruneKeepsHistoryEntryAfterTimerCompletes() {
        val history = listOf(historyItem(id = "history-1", timerId = "timer-1", taskId = "task-b"))

        val pruned = pruneTimerTaskRetarget(mapOf("timer-1" to "task-b"), null, history)

        assertTrue(pruned.containsKey("timer-1"))
    }

    private fun command(id: String, timerId: String, type: String) = TimerCommand(
        id = id,
        deviceSequence = 1,
        timerId = timerId,
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_000,
        hlcCounter = 1,
        observedElapsedMs = 0,
        taskId = "task-a",
    )

    private fun timer(id: String, taskId: String?) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
        taskId = taskId,
    )

    private fun historyItem(id: String, timerId: String, taskId: String?) = HistoryItem(
        id = id,
        timerId = timerId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        taskId = taskId,
    )
}
