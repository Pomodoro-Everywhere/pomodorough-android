package me.egigoka.pomodorough.data

import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal data class DeviceOperation<T>(
    val deviceId: String,
    val value: T,
)

internal data class CoreProjectionBase(
    val canonicalTimer: CanonicalTimer? = null,
    val history: List<HistoryItem> = emptyList(),
    val tasks: List<FocusTask> = emptyList(),
    val durationsMs: DurationsMs = DurationsMs(),
    val autoStartBreaks: Boolean = false,
    val selectedTaskId: String? = null,
)

internal data class CoreProjectionPending(
    val commands: List<DeviceOperation<TimerCommand>> = emptyList(),
    val taskOperations: List<DeviceOperation<TaskOperation>> = emptyList(),
    val durationOperations: List<DeviceOperation<DurationOperation>> = emptyList(),
    val autoStartOperations: List<DeviceOperation<AutoStartOperation>> = emptyList(),
    val selectedTaskOperations: List<DeviceOperation<SelectedTaskOperation>> = emptyList(),
)

@Serializable
internal data class CoreTimerOutcome(
    val outcome: String,
    val reason: String,
)

@Serializable
internal data class CoreWinningOperationIds(
    val tasks: Map<String, String>,
    val durations: Map<String, String>,
    val autoStart: String?,
    val selectedTask: String?,
)

@Serializable
internal data class CoreProjectionResult(
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val durationsMs: DurationsMs,
    val autoStartBreaks: Boolean,
    val selectedTaskId: String?,
    val timerOutcomes: Map<String, CoreTimerOutcome>,
    val winningOperationIds: CoreWinningOperationIds,
) {
    fun requireApplied(command: TimerCommand): CoreProjectionResult {
        if (timerOutcomes[command.id]?.outcome != "applied") {
            throw CoreProjectionException.InvalidOutput("Shared Core rejected timer command ${command.id}")
        }
        return this
    }

    fun requireWinner(operation: TaskOperation): CoreProjectionResult {
        val projected = tasks.firstOrNull { it.id == operation.taskId }
        val expected = operation.title
            ?.takeIf { operation.type == TaskOperationType.Upsert }
            ?.let { FocusTask(operation.taskId, it) }
        if (winningOperationIds.tasks[operation.taskId] != operation.id || projected != expected) {
            throw CoreProjectionException.InvalidOutput("Shared Core returned invalid task winner")
        }
        return this
    }

    fun requireWinner(operation: DurationOperation): CoreProjectionResult {
        if (
            winningOperationIds.durations[operation.phase] != operation.id ||
            durationsMs.forPhase(operation.phase) != operation.durationMs
        ) {
            throw CoreProjectionException.InvalidOutput("Shared Core returned invalid duration winner")
        }
        return this
    }

    fun requireWinner(operation: AutoStartOperation): CoreProjectionResult {
        if (winningOperationIds.autoStart != operation.id || autoStartBreaks != operation.enabled) {
            throw CoreProjectionException.InvalidOutput("Shared Core returned invalid auto-start winner")
        }
        return this
    }

    fun requireWinner(operation: SelectedTaskOperation): CoreProjectionResult {
        if (
            winningOperationIds.selectedTask != operation.id ||
            selectedTaskId != operation.taskId
        ) {
            throw CoreProjectionException.InvalidOutput("Shared Core returned invalid selected-task winner")
        }
        return this
    }
}

internal sealed class CoreProjectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class InvalidInput(message: String, cause: Throwable? = null) :
        CoreProjectionException(message, cause)

    class InvalidOutput(message: String, cause: Throwable? = null) :
        CoreProjectionException(message, cause)
}

internal class CoreProjectionDispatcher(
    private val dispatch: (String, String) -> JsonElement,
) {
    private val wireJson = Json { explicitNulls = false }
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun apply(
        base: CoreProjectionBase,
        pending: CoreProjectionPending,
        now: Instant,
    ): CoreProjectionResult {
        val input = try {
            projectionInput(base, pending, now)
        } catch (error: Exception) {
            throw CoreProjectionException.InvalidInput("Could not serialize Shared Core projection", error)
        }
        val output = dispatch(ProjectionOperation, wireJson.encodeToString(input))
        val result = try {
            strictJson.decodeFromJsonElement(CoreProjectionResult.serializer(), output)
        } catch (error: Exception) {
            throw CoreProjectionException.InvalidOutput("Could not decode Shared Core projection", error)
        }
        validateResult(result, pending)
        return result
    }

    private fun projectionInput(
        base: CoreProjectionBase,
        pending: CoreProjectionPending,
        now: Instant,
    ): JsonObject {
        val coreTimer = base.canonicalTimer?.takeUnless { timer ->
            base.history.any { it.timerId == timer.id }
        }
        return buildJsonObject {
            put("base", buildJsonObject {
                put("canonicalTimer", coreTimer?.let(wireJson::encodeToJsonElement) ?: JsonNull)
                put("history", wireJson.encodeToJsonElement(base.history))
                put("tasks", wireJson.encodeToJsonElement(base.tasks))
                put("durationsMs", buildJsonObject {
                    put(TimerPhase.Focus, base.durationsMs.focus)
                    put(TimerPhase.ShortBreak, base.durationsMs.shortBreak)
                    put(TimerPhase.LongBreak, base.durationsMs.longBreak)
                })
                put("autoStartBreaks", base.autoStartBreaks)
                put("selectedTaskId", base.selectedTaskId?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("pending", buildJsonObject {
                put("commands", buildJsonArray {
                    pending.commands.forEach { add(operationJson(it)) }
                })
                put("taskOperations", buildJsonArray {
                    pending.taskOperations.forEach { add(operationJson(it)) }
                })
                put("durationOperations", buildJsonArray {
                    pending.durationOperations.forEach { add(operationJson(it)) }
                })
                put("autoStartOperations", buildJsonArray {
                    pending.autoStartOperations.forEach { add(operationJson(it)) }
                })
                put("selectedTaskOperations", buildJsonArray {
                    pending.selectedTaskOperations.forEach { add(operationJson(it)) }
                })
            })
            put("now", now.toString())
        }
    }

    private inline fun <reified T> operationJson(operation: DeviceOperation<T>): JsonObject {
        if (operation.deviceId.isBlank()) {
            throw CoreProjectionException.InvalidInput("Shared Core operation device ID is empty")
        }
        val encoded = wireJson.encodeToJsonElement(operation.value) as? JsonObject
            ?: throw CoreProjectionException.InvalidInput("Shared Core operation is not an object")
        return JsonObject(encoded + ("deviceId" to JsonPrimitive(operation.deviceId)))
    }

    private fun validateResult(result: CoreProjectionResult, pending: CoreProjectionPending) {
        validateTimer(result.canonicalTimer)
        if (result.history.map(HistoryItem::id).toSet().size != result.history.size ||
            result.history.map(HistoryItem::timerId).toSet().size != result.history.size ||
            result.history.any { !validHistory(it) }
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid history")
        if (result.tasks.map(FocusTask::id).toSet().size != result.tasks.size ||
            result.tasks.any { it.id.isBlank() || it.title.isBlank() || it.title.toByteArray(StandardCharsets.UTF_8).size > 512 }
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid tasks")
        if (!validDuration(result.durationsMs.focus) ||
            !validDuration(result.durationsMs.shortBreak) ||
            !validDuration(result.durationsMs.longBreak)
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid durations")
        if (result.selectedTaskId != null && result.tasks.none { it.id == result.selectedTaskId }) {
            throw CoreProjectionException.InvalidOutput("Shared Core selected unavailable task")
        }

        val commandIds = pending.commands.map { it.value.id }
        if (commandIds.toSet().size != commandIds.size || result.timerOutcomes.keys != commandIds.toSet() ||
            result.timerOutcomes.values.any { it.outcome !in OutcomeValues }
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid timer outcomes")
        validateWinners(result, pending)
    }

    private fun validateWinners(result: CoreProjectionResult, pending: CoreProjectionPending) {
        val taskGroups = pending.taskOperations.groupBy { it.value.taskId }
        val durationGroups = pending.durationOperations.groupBy { it.value.phase }
        if (result.winningOperationIds.tasks.keys != taskGroups.keys ||
            result.winningOperationIds.durations.keys != durationGroups.keys
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid winner keys")
        result.winningOperationIds.tasks.forEach { (taskId, operationId) ->
            val operation = taskGroups.getValue(taskId).firstOrNull { it.value.id == operationId }?.value
                ?: throw CoreProjectionException.InvalidOutput("Shared Core returned unknown task winner")
            val expected = operation.title
                ?.takeIf { operation.type == TaskOperationType.Upsert }
                ?.let { FocusTask(taskId, it) }
            if (result.tasks.firstOrNull { it.id == taskId } != expected) {
                throw CoreProjectionException.InvalidOutput("Shared Core returned inconsistent task winner")
            }
        }
        result.winningOperationIds.durations.forEach { (phase, operationId) ->
            val operation = durationGroups.getValue(phase).firstOrNull { it.value.id == operationId }?.value
                ?: throw CoreProjectionException.InvalidOutput("Shared Core returned unknown duration winner")
            if (result.durationsMs.forPhase(phase) != operation.durationMs) {
                throw CoreProjectionException.InvalidOutput("Shared Core returned inconsistent duration winner")
            }
        }
        val autoWinner = result.winningOperationIds.autoStart
        if (pending.autoStartOperations.isEmpty() != (autoWinner == null) || autoWinner != null &&
            pending.autoStartOperations.none { it.value.id == autoWinner && it.value.enabled == result.autoStartBreaks }
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid auto-start winner")
        val selectedWinner = result.winningOperationIds.selectedTask
        if (pending.selectedTaskOperations.isEmpty() != (selectedWinner == null) || selectedWinner != null &&
            pending.selectedTaskOperations.none { it.value.id == selectedWinner && it.value.taskId == result.selectedTaskId }
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid selected-task winner")
    }

    private fun validateTimer(timer: CanonicalTimer?) {
        if (timer == null) return
        if (timer.id.isBlank() || timer.phase !in TimerPhase.all || timer.status !in TimerStatuses ||
            !validDuration(timer.plannedDurationMs) || timer.elapsedAtAnchorMs !in 0..timer.plannedDurationMs ||
            runCatching { Instant.parse(timer.anchorAt) }.isFailure
        ) throw CoreProjectionException.InvalidOutput("Shared Core returned invalid timer")
    }

    private fun validHistory(item: HistoryItem): Boolean =
        item.id.isNotBlank() && item.timerId.isNotBlank() && item.phase in TimerPhase.all &&
            item.status in TerminalStatuses && validDuration(item.plannedDurationMs) &&
            listOfNotNull(item.completedAt, item.endedAt).all { runCatching { Instant.parse(it) }.isSuccess }

    private fun validDuration(value: Long): Boolean = value in CoreMinDurationMs..CoreMaxDurationMs

    private companion object {
        const val ProjectionOperation = "projection.apply.v2"
        const val CoreMinDurationMs = 60_000L
        const val CoreMaxDurationMs = 14_400_000L
        val OutcomeValues = setOf("applied", "ignored", "rejected")
        val TimerStatuses = setOf(
            TimerStatus.Running,
            TimerStatus.Paused,
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val TerminalStatuses = setOf(TimerStatus.Completed, TimerStatus.Cancelled, TimerStatus.Superseded)
    }
}
