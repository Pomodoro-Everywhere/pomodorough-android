package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class BatchSelectedIds(
    val commands: List<String> = emptyList(),
    val taskOperations: List<String> = emptyList(),
    val durationOperations: List<String> = emptyList(),
    val autoStartOperations: List<String> = emptyList(),
    val selectedTaskOperations: List<String> = emptyList(),
) {
    fun total(): Int =
        commands.size + taskOperations.size + durationOperations.size +
            autoStartOperations.size + selectedTaskOperations.size
}

internal data class CoreBatchPlan(
    val status: String,
    val selected: BatchSelectedIds,
    val nextDomain: String?,
    val heldTimerOperationId: String?,
    val total: Int,
)

@Serializable
private data class BatchPlanSelected(
    val commands: List<String>,
    val taskOperations: List<String>,
    val durationOperations: List<String>,
    val autoStartOperations: List<String>,
    val selectedTaskOperations: List<String>,
)

@Serializable
private data class BatchPlanCounts(
    val commands: Int,
    val taskOperations: Int,
    val durationOperations: Int,
    val autoStartOperations: Int,
    val selectedTaskOperations: Int,
)

@Serializable
private data class BatchPlanOutput(
    val status: String,
    val selected: BatchPlanSelected,
    val nextDomain: String? = null,
    val heldTimerOperationId: String? = null,
    val counts: BatchPlanCounts,
    val total: Int,
)

/**
 * Transport adapter for the pinned `sync.batchPlan.v1` Core operation.
 * All batch budgeting, ordering, dependency barriers, and fairness come from
 * Core; this adapter only builds descriptors and resolves selected IDs back to
 * exact records in the planner's returned order.
 */
internal class CoreBatchPlanDispatcher(
    private val dispatch: (String, String) -> JsonElement,
) {
    private val wireJson = Json { explicitNulls = false; encodeDefaults = true }
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun planSync(
        queues: PendingSyncQueues,
        deviceId: String,
        dependencies: Map<String, String>,
        nextDomain: String,
    ): CoreBatchPlan {
        requireValidCursor(nextDomain)
        val input = wireJson.encodeToString(
            buildNewInput("sync", SyncLimits, nextDomain, queues, deviceId, dependencies),
        )
        val output = decode(dispatch(Operation, input))
        return validateNew(output, queues, "sync")
    }

    fun planBootstrap(
        strategy: BootstrapStrategy,
        queues: PendingSyncQueues,
        deviceId: String,
        dependencies: Map<String, String>,
    ): CoreBatchPlan {
        val mode = bootstrapMode(strategy)
        val input = wireJson.encodeToString(
            buildNewInput(mode, BootstrapLimits, InitialCursor, queues, deviceId, dependencies),
        )
        val output = decode(dispatch(Operation, input))
        return validateNew(output, queues, mode)
    }

    fun checkSavedSync(request: SyncRequest): CoreBatchPlan {
        val ids = BatchSelectedIds(
            commands = request.commands.map(TimerCommand::id),
            taskOperations = request.taskOperations.map(TaskOperation::id),
            durationOperations = request.durationOperations.map(DurationOperation::id),
            autoStartOperations = request.autoStartOperations.map(AutoStartOperation::id),
            selectedTaskOperations = request.selectedTaskOperations.map(SelectedTaskOperation::id),
        )
        val output = decode(dispatch(Operation, savedInput("sync", SyncLimits, ids)))
        return validateSaved(output, ids, "sync")
    }

    fun checkSavedBootstrap(request: BootstrapResolutionRequest): CoreBatchPlan {
        val mode = bootstrapMode(request.strategy)
        val ids = BatchSelectedIds(
            commands = request.commands.map(TimerCommand::id),
            taskOperations = request.taskOperations.map(TaskOperation::id),
            durationOperations = request.durationOperations.map(DurationOperation::id),
            autoStartOperations = request.autoStartOperations.orEmpty().map(AutoStartOperation::id),
            selectedTaskOperations = request.selectedTaskOperations.orEmpty()
                .map(SelectedTaskOperation::id),
        )
        val output = decode(dispatch(Operation, savedInput(mode, BootstrapLimits, ids)))
        return validateSaved(output, ids, mode)
    }

    private fun buildNewInput(
        mode: String,
        limits: Pair<Int, Int>,
        nextDomain: String,
        queues: PendingSyncQueues,
        deviceId: String,
        dependencies: Map<String, String>,
    ) = buildJsonObject {
        put("kind", "new")
        put("mode", mode)
        put("limits", buildJsonObject {
            put("perDomain", limits.first)
            put("total", limits.second)
        })
        put("nextDomain", nextDomain)
        put("queues", batchQueues(queues, deviceId))
        put("timerDependencies", batchDependencies(dependencies))
    }

    private fun batchQueues(queues: PendingSyncQueues, deviceId: String) = buildJsonObject {
        put("commands", batchDescriptors(queues.commands, { deviceId }, TimerCommand::hlcWallMs,
            TimerCommand::hlcCounter, { it.deviceSequence }, TimerCommand::id))
        put("taskOperations", batchDescriptors(queues.taskOperations, { deviceId },
            TaskOperation::hlcWallMs, TaskOperation::hlcCounter, { null }, TaskOperation::id))
        put("durationOperations", batchDescriptors(queues.durationOperations, { deviceId },
            DurationOperation::hlcWallMs, DurationOperation::hlcCounter, { null },
            DurationOperation::id))
        put("autoStartOperations", batchDescriptors(queues.autoStartOperations,
            AutoStartOperation::deviceId, AutoStartOperation::hlcWallMs,
            AutoStartOperation::hlcCounter, { null }, AutoStartOperation::id))
        put("selectedTaskOperations", batchDescriptors(queues.selectedTaskOperations, { deviceId },
            SelectedTaskOperation::hlcWallMs, SelectedTaskOperation::hlcCounter, { null },
            SelectedTaskOperation::id))
    }

    private fun <T> batchDescriptors(
        values: List<T>,
        deviceId: (T) -> String,
        wallMs: (T) -> Long,
        counter: (T) -> Long,
        sequence: (T) -> Long?,
        id: (T) -> String,
    ) = buildJsonArray {
        values.forEach { value ->
            addJsonDescriptor(id(value), deviceId(value), wallMs(value), counter(value), sequence(value))
        }
    }

    private fun batchDependencies(dependencies: Map<String, String>) = buildJsonArray {
        dependencies.toSortedMap().forEach { (child, parent) ->
            add(buildJsonObject {
                put("operationId", child)
                put("dependsOnOperationId", parent)
            })
        }
    }

    private fun savedInput(mode: String, limits: Pair<Int, Int>, ids: BatchSelectedIds) =
        wireJson.encodeToString(buildJsonObject {
            put("kind", "saved")
            put("mode", mode)
            put("limits", buildJsonObject {
                put("perDomain", limits.first)
                put("total", limits.second)
            })
            put("queues", buildJsonObject {
                put("commands", buildJsonArray { ids.commands.forEach { add(JsonPrimitive(it)) } })
                put("taskOperations", buildJsonArray { ids.taskOperations.forEach { add(JsonPrimitive(it)) } })
                put("durationOperations", buildJsonArray {
                    ids.durationOperations.forEach { add(JsonPrimitive(it)) }
                })
                put("autoStartOperations", buildJsonArray {
                    ids.autoStartOperations.forEach { add(JsonPrimitive(it)) }
                })
                put("selectedTaskOperations", buildJsonArray {
                    ids.selectedTaskOperations.forEach { add(JsonPrimitive(it)) }
                })
            })
        })

    private fun decode(output: JsonElement): BatchPlanOutput = try {
        strictJson.decodeFromJsonElement(BatchPlanOutput.serializer(), output)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw CoreProjectionException.InvalidOutput("Could not decode Shared Core batch plan", error)
    }

    private fun validateNew(
        output: BatchPlanOutput,
        queues: PendingSyncQueues,
        mode: String,
    ): CoreBatchPlan {
        requireStatus(output.status)
        requireCounts(output, queues)
        requireSelectedSubset(output, queues)
        if (mode == "sync") {
            require(output.status == "planned" || output.status == "blocked_dependency") {
                "Shared Core returned invalid sync batch status"
            }
            require(output.nextDomain in Domains) {
                "Shared Core returned an invalid sync batch cursor"
            }
        } else {
            require(output.nextDomain in Domains) {
                "Shared Core returned an invalid bootstrap batch cursor"
            }
        }
        return toPlan(output)
    }

    private fun validateSaved(output: BatchPlanOutput, ids: BatchSelectedIds, mode: String) =
        toPlan(output).also {
            require(it.status == "replay_saved" || it.status == "oversized_saved") {
                "Shared Core returned an invalid saved $mode batch status"
            }
            require(it.nextDomain == null && it.heldTimerOperationId == null) {
                "Shared Core returned invalid saved batch metadata"
            }
            if (it.status == "replay_saved") require(it.selected == ids) {
                "Shared Core saved batch must replay the exact saved claim"
            } else require(it.selected.total() == 0) {
                "Shared Core oversized saved batch must select no operations"
            }
        }

    private fun requireCounts(output: BatchPlanOutput, queues: PendingSyncQueues) {
        require(output.counts.commands == queues.commands.size &&
            output.counts.taskOperations == queues.taskOperations.size &&
            output.counts.durationOperations == queues.durationOperations.size &&
            output.counts.autoStartOperations == queues.autoStartOperations.size &&
            output.counts.selectedTaskOperations == queues.selectedTaskOperations.size &&
            output.total == queues.commands.size + queues.taskOperations.size +
            queues.durationOperations.size + queues.autoStartOperations.size +
            queues.selectedTaskOperations.size
        ) { "Shared Core batch counts disagree with input queues" }
    }

    private fun requireSelectedSubset(output: BatchPlanOutput, queues: PendingSyncQueues) {
        requireContainsAll(queues.commands.map(TimerCommand::id), output.selected.commands)
        requireContainsAll(queues.taskOperations.map(TaskOperation::id), output.selected.taskOperations)
        requireContainsAll(
            queues.durationOperations.map(DurationOperation::id),
            output.selected.durationOperations,
        )
        requireContainsAll(
            queues.autoStartOperations.map(AutoStartOperation::id),
            output.selected.autoStartOperations,
        )
        requireContainsAll(
            queues.selectedTaskOperations.map(SelectedTaskOperation::id),
            output.selected.selectedTaskOperations,
        )
    }

    private fun requireContainsAll(complete: List<String>, selected: List<String>) {
        require(selected.toSet().size == selected.size && complete.toSet().containsAll(selected)) {
            "Shared Core selected an unknown batch operation"
        }
    }

    private fun toPlan(output: BatchPlanOutput) = CoreBatchPlan(
        status = output.status,
        selected = BatchSelectedIds(
            commands = output.selected.commands,
            taskOperations = output.selected.taskOperations,
            durationOperations = output.selected.durationOperations,
            autoStartOperations = output.selected.autoStartOperations,
            selectedTaskOperations = output.selected.selectedTaskOperations,
        ),
        nextDomain = output.nextDomain,
        heldTimerOperationId = output.heldTimerOperationId,
        total = output.total,
    )

    private fun requireStatus(status: String) {
        require(status in setOf("planned", "blocked_dependency", "oversized", "replay_saved", "oversized_saved")) {
            "Shared Core returned an invalid batch status"
        }
    }

    private fun requireValidCursor(nextDomain: String) {
        require(nextDomain in Domains) { "Sync batch cursor is invalid" }
    }

    private fun bootstrapMode(strategy: BootstrapStrategy): String = when (strategy) {
        BootstrapStrategy.Merge -> "merge"
        BootstrapStrategy.ReplaceRemote -> "replace_remote"
        BootstrapStrategy.KeepRemote -> "keep_remote"
    }

    private fun JsonArrayBuilder.addJsonDescriptor(
        id: String,
        deviceId: String,
        hlcWallMs: Long,
        hlcCounter: Long,
        deviceSequence: Long?,
    ) {
        add(buildJsonObject {
            put("id", id)
            put("deviceId", deviceId)
            put("hlcWallMs", hlcWallMs)
            put("hlcCounter", hlcCounter)
            deviceSequence?.let { put("deviceSequence", it) }
        })
    }

    companion object {
        const val Operation = "sync.batchPlan.v1"
        const val InitialCursor = "commands"
        val SyncLimits = 256 to 512
        val BootstrapLimits = 4096 to 8192
        val Domains = setOf(
            "commands",
            "taskOperations",
            "durationOperations",
            "autoStartOperations",
            "selectedTaskOperations",
        )
    }
}
