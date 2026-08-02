package me.egigoka.pomodorough.domain

import java.time.Instant
import java.time.ZoneId
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskReducerTest {
    @Test
    fun deterministicIdentityNormalizesNfcAndPrintableEdges() {
        val composed = TaskReducer.taskFromTitle("\u0000Cafe\u0301\u001f")
        val precomposed = TaskReducer.taskFromTitle("Café")
        val spaced = TaskReducer.taskFromTitle("\u00a0 Write release notes \u00a0")

        assertEquals("Café", composed?.title)
        assertEquals("aaf83054-24b2-8c0e-901f-a974147bfe82", composed?.id)
        assertEquals(precomposed, composed)
        assertEquals(" Write release notes ", spaced?.title)
        assertNull(TaskReducer.taskFromTitle("\u0000\u001f"))
    }

    @Test
    fun hlcOrderingAllowsDeleteAndSameIdentityRecreation() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val operations = listOf(
            operation("recreate", task.id, TaskOperationType.Upsert, task.title, wall = 3),
            operation("stale", task.id, TaskOperationType.Upsert, task.title, wall = 1),
            operation("delete", task.id, TaskOperationType.Delete, null, wall = 2),
        )

        assertEquals(listOf(task), TaskReducer.replay(emptyList(), operations))
        assertEquals(
            emptyList<Any>(),
            TaskReducer.replay(emptyList(), operations.filterNot { it.id == "recreate" }),
        )
    }

    @Test
    fun replayIsInputOrderIndependentAcrossEveryLwwTieBreaker() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val olderWall = operation(
            "operation-z-old-wall",
            task.id,
            TaskOperationType.Delete,
            null,
            wall = 1,
            counter = 9,
        )
        val olderCounter = operation(
            "operation-z-old-counter",
            task.id,
            TaskOperationType.Delete,
            null,
            wall = 2,
            counter = 0,
        )
        val olderId = operation(
            "operation-a",
            task.id,
            TaskOperationType.Delete,
            null,
            wall = 2,
            counter = 1,
        )
        val winner = operation(
            "operation-z",
            task.id,
            TaskOperationType.Upsert,
            task.title,
            wall = 2,
            counter = 1,
        )
        val operations = listOf(olderWall, olderCounter, olderId, winner)

        permutations(operations).forEach { input ->
            assertEquals(
                input.map(TaskOperation::id).toString(),
                listOf(task),
                TaskReducer.replay(emptyList(), input),
            )
        }
    }

    @Test
    fun exactTaskOperationReplayIsIdempotent() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val upsert = operation(
            "operation-upsert",
            task.id,
            TaskOperationType.Upsert,
            task.title,
            wall = 1,
        )
        val delete = operation(
            "operation-delete",
            task.id,
            TaskOperationType.Delete,
            null,
            wall = 2,
        )

        assertEquals(
            TaskReducer.replay(emptyList(), listOf(upsert, delete)),
            TaskReducer.replay(emptyList(), listOf(upsert, upsert, delete, delete)),
        )
        assertEquals(
            listOf(task),
            TaskReducer.replay(listOf(task), listOf(upsert, upsert)),
        )
    }

    @Test
    fun dailySummaryCountsCompletedFocusOnly() {
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val history = listOf(
            history("focus", task.id, TimerPhase.Focus, TimerStatus.Completed, 1_500_000),
            history("break", task.id, TimerPhase.ShortBreak, TimerStatus.Completed, 300_000),
            history("cancelled", task.id, TimerPhase.Focus, TimerStatus.Cancelled, 1_500_000),
            history("taskless", null, TimerPhase.Focus, TimerStatus.Completed, 1_500_000),
        )

        val summary = TaskReducer.summariesToday(
            tasks = listOf(task),
            history = history,
            now = Instant.parse("2026-07-21T12:00:00Z"),
            zoneId = ZoneId.of("UTC"),
        ).single()

        assertEquals(1, summary.finishedPomodoros)
        assertEquals(1_500_000, summary.timeSpentMs)
    }

    private fun operation(
        id: String,
        taskId: String,
        type: String,
        title: String?,
        wall: Long,
        counter: Long = 0,
    ) = TaskOperation(
        id = id,
        taskId = taskId,
        type = type,
        title = title,
        occurredAt = "2026-07-21T00:00:00Z",
        hlcWallMs = wall,
        hlcCounter = counter,
    )

    private fun history(
        id: String,
        taskId: String?,
        phase: String,
        status: String,
        duration: Long,
    ) = HistoryItem(
        id = id,
        timerId = id,
        phase = phase,
        status = status,
        plannedDurationMs = duration,
        completedAt = "2026-07-21T10:00:00Z",
        taskId = taskId,
    )

    private fun <T> permutations(values: List<T>): List<List<T>> = when (values.size) {
        0, 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index })
                .map { listOf(value) + it }
        }
    }
}
