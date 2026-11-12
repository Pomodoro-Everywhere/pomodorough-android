package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * UX round 2, batch C: the bundled Core (0.55.0) records the time a session
 * really ran. An early Finish keeps that time in the history row and in the
 * Tasks total, a stored row without the field still loads, and an elapsed
 * value outside the plan fails closed at the projection boundary.
 *
 * Rows are read through their JSON form so that the test names no new field.
 */
class CoreHistoryElapsedTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }

    @Test
    fun earlyFinishRecordsTheRealElapsedTimeInHistoryAndTheTaskTotal() {
        val result = earlyFinish(CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) })

        assertEquals(TimerStatus.Completed, result.canonicalTimer?.status)
        assertEquals(TwentyMinutesMs, result.canonicalTimer?.elapsedAtAnchorMs)
        assertEquals(listOf(JsonPrimitive(TwentyMinutesMs)), wireHistory(result.history).map { it["elapsedMs"] })
        val summary = TaskReducer.summariesToday(listOf(task), result.history, FinishedAt, ZoneOffset.UTC).single()
        assertEquals(1, summary.finishedPomodoros)
        assertEquals(TwentyMinutesMs, summary.timeSpentMs)
    }

    @Test
    fun storedRowWithoutElapsedLoadsAndCoreAppliesItsRuleForOldRows() {
        val legacy = strictJson.decodeFromString<HistoryItem>(
            "{\"id\":\"history-1\",\"timerId\":\"timer-1\",\"phase\":\"focus\",\"status\":\"completed\"," +
                "\"plannedDurationMs\":$PlanMs,\"completedAt\":\"$StartedAt\",\"endedAt\":\"$StartedAt\"}",
        )
        val cancelled = strictJson.decodeFromString<HistoryItem>(
            "{\"id\":\"history-2\",\"timerId\":\"timer-2\",\"phase\":\"focus\",\"status\":\"cancelled\"," +
                "\"plannedDurationMs\":$PlanMs,\"endedAt\":\"$StartedAt\"}",
        )
        val dispatcher = CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }

        val result = dispatcher.apply(
            CoreProjectionBase(history = listOf(legacy, cancelled)), CoreProjectionPending(), FinishedAt,
        )

        // Rework2: Core fills elapsedMs on every projected row, so the views need no rule of their own.
        assertEquals(
            mapOf("history-1" to JsonPrimitive(PlanMs), "history-2" to JsonPrimitive(0L)),
            wireHistory(result.history).associate { it.getValue("id").toString().trim('"') to it["elapsedMs"] },
        )
    }

    @Test
    fun projectedRowWithoutElapsedFailsClosed() {
        val dispatcher = CoreProjectionDispatcher { operation, input ->
            withoutHistoryElapsed(core.dispatch(operation, input))
        }
        val error = assertThrows(CoreProjectionException.InvalidOutput::class.java) { earlyFinish(dispatcher) }
        assertEquals("Shared Core returned invalid history", error.message)
    }

    @Test
    fun elapsedOutsideThePlanFromCoreFailsClosed() {
        listOf(-1L, PlanMs + 1).forEach { elapsed ->
            val dispatcher = CoreProjectionDispatcher { operation, input ->
                withHistoryElapsed(core.dispatch(operation, input), elapsed)
            }
            val error = assertThrows(CoreProjectionException.InvalidOutput::class.java) { earlyFinish(dispatcher) }
            assertEquals("$elapsed", "Shared Core returned invalid history", error.message)
        }
    }

    private fun earlyFinish(dispatcher: CoreProjectionDispatcher): CoreProjectionResult {
        val start = command("command-1", 1, CommandType.Start, StartedAt.toString(), 0)
        val finish = command("command-2", 2, CommandType.Finish, FinishedAt.toString(), TwentyMinutesMs)
        val upsert = TaskOperation(
            "task-operation-1", task.id, TaskOperationType.Upsert, task.title, StartedAt.toString(), WallMs, 0,
        )
        return dispatcher.apply(
            base = CoreProjectionBase(),
            pending = CoreProjectionPending(
                commands = listOf(DeviceOperation(DeviceId, start), DeviceOperation(DeviceId, finish)),
                taskOperations = listOf(DeviceOperation(DeviceId, upsert)),
            ),
            now = FinishedAt,
        ).requireApplied(start).requireApplied(finish)
    }

    private fun command(id: String, sequence: Long, type: String, at: String, observed: Long) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = "timer-1",
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = PlanMs,
        occurredAt = at,
        hlcWallMs = WallMs + sequence,
        hlcCounter = 0,
        observedElapsedMs = observed,
        taskId = task.id,
        physicalOccurredAt = at,
    )

    private fun wireHistory(history: List<HistoryItem>): List<JsonObject> =
        Json.encodeToJsonElement(history).jsonArray.map { it.jsonObject }

    private fun withHistoryElapsed(output: JsonElement, elapsed: Long): JsonElement {
        val root = output.jsonObject
        val history = root.getValue("history").jsonArray.map { row ->
            JsonObject(row.jsonObject + ("elapsedMs" to JsonPrimitive(elapsed)))
        }
        return JsonObject(root + ("history" to JsonArray(history)))
    }

    private fun withoutHistoryElapsed(output: JsonElement): JsonElement {
        val root = output.jsonObject
        val history = root.getValue("history").jsonArray.map { row -> JsonObject(row.jsonObject - "elapsedMs") }
        return JsonObject(root + ("history" to JsonArray(history)))
    }

    private companion object {
        const val DeviceId = "device-1"
        const val PlanMs = 1_500_000L
        const val TwentyMinutesMs = 1_200_000L
        const val WallMs = 1_767_225_600_000L
        val StartedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val FinishedAt: Instant = Instant.parse("2026-01-01T00:20:00Z")
        val task = requireNotNull(TaskReducer.taskFromTitle("Write the report"))
        val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = false }
    }
}
