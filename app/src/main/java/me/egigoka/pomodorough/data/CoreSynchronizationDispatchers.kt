package me.egigoka.pomodorough.data

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal sealed interface CoreBootstrapPlan {
    data object NormalSync : CoreBootstrapPlan

    data class Automatic(val strategy: BootstrapStrategy) : CoreBootstrapPlan

    data class Choose(
        val localHistoryCount: Int,
        val remoteHistoryCount: Int,
    ) : CoreBootstrapPlan
}

@Serializable
private data class CoreBootstrapPlanOutput(
    val mode: String,
    val strategy: BootstrapStrategy? = null,
    val reason: String? = null,
    val localHistoryCount: Int? = null,
    val remoteHistoryCount: Int? = null,
)

internal class CoreBootstrapDispatcher(
    private val dispatch: (String, String) -> JsonElement,
) {
    private val wireJson = Json { explicitNulls = false }
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun plan(
        localOwnerId: String?,
        currentUserId: String,
        localHistory: List<HistoryItem>,
        remoteHistory: List<HistoryItem>,
        hasLocalState: Boolean,
        hasRemoteState: Boolean,
    ): CoreBootstrapPlan {
        validateIdentity(localOwnerId, currentUserId)
        val input = CoreBootstrapPlanInput(
            localOwnerId, currentUserId, localHistory, remoteHistory,
            hasLocalState, hasRemoteState,
        )
        val decoded = decodePlan(dispatch(BootstrapOperation, wireJson.encodeToString(input)))
        return planFromOutput(decoded)
    }

    private fun validateIdentity(localOwnerId: String?, currentUserId: String) {
        if (currentUserId.isBlank() || localOwnerId?.isBlank() == true) {
            throw CoreProjectionException.InvalidInput("Shared Core bootstrap identity is invalid")
        }
    }

    private fun decodePlan(output: JsonElement): CoreBootstrapPlanOutput = try {
        strictJson.decodeFromJsonElement(CoreBootstrapPlanOutput.serializer(), output)
    } catch (error: Exception) {
        throw CoreProjectionException.InvalidOutput("Could not decode Shared Core bootstrap plan", error)
    }

    private fun planFromOutput(output: CoreBootstrapPlanOutput): CoreBootstrapPlan = when (output.mode) {
        "normal_sync" -> normalSyncPlan(output)
        "auto" -> automaticPlan(output)
        "choose" -> choicePlan(output)
        else -> invalidBootstrapOutput()
    }

    private fun normalSyncPlan(output: CoreBootstrapPlanOutput): CoreBootstrapPlan {
        if (output.strategy != null || output.reason.isNullOrBlank() ||
            output.localHistoryCount != null || output.remoteHistoryCount != null
        ) invalidBootstrapOutput()
        return CoreBootstrapPlan.NormalSync
    }

    private fun automaticPlan(output: CoreBootstrapPlanOutput): CoreBootstrapPlan {
        val strategy = output.strategy ?: invalidBootstrapOutput()
        if (output.reason.isNullOrBlank() || output.localHistoryCount != null ||
            output.remoteHistoryCount != null
        ) invalidBootstrapOutput()
        return CoreBootstrapPlan.Automatic(strategy)
    }

    private fun choicePlan(output: CoreBootstrapPlanOutput): CoreBootstrapPlan {
        val localCount = output.localHistoryCount ?: invalidBootstrapOutput()
        val remoteCount = output.remoteHistoryCount ?: invalidBootstrapOutput()
        if (output.strategy != null || output.reason != null || localCount < 0 || remoteCount < 0
        ) invalidBootstrapOutput()
        return CoreBootstrapPlan.Choose(localCount, remoteCount)
    }

    private fun invalidBootstrapOutput(): Nothing =
        throw CoreProjectionException.InvalidOutput("Shared Core returned invalid bootstrap plan")

    @Serializable
    private data class CoreBootstrapPlanInput(
        val localOwnerId: String?,
        val currentUserId: String,
        val localHistory: List<HistoryItem>,
        val remoteHistory: List<HistoryItem>,
        val hasLocalState: Boolean,
        val hasRemoteState: Boolean,
    )

    private companion object {
        const val BootstrapOperation = "bootstrap.plan.v1"
    }
}

@Serializable
internal data class CoreTimerDependency(
    val operationId: String,
    val dependsOnOperationId: String,
    val generatedBreak: Boolean = false,
    val sourceDayStart: String? = null,
    val sourceDayEnd: String? = null,
)

internal data class CoreReconciliationSent(
    val commands: List<String> = emptyList(),
    val taskOperations: List<String> = emptyList(),
    val durationOperations: List<String> = emptyList(),
    val autoStartOperations: List<String> = emptyList(),
    val selectedTaskOperations: List<String> = emptyList(),
)

internal data class CoreReconciliationResult(
    val revision: Long,
    val pending: CoreProjectionPending,
    val dependencies: List<CoreTimerDependency>,
    val promotedTimerOperationIds: Set<String>,
    val droppedTimerOperationIds: Set<String>,
    val droppedTimerIds: Set<String>,
    val base: CoreProjectionBase,
    val projection: CoreProjectionResult,
)

internal class CoreReconciliationDispatcher(
    private val dispatch: (String, String) -> JsonElement,
    private val projectionDispatcher: CoreProjectionDispatcher = CoreProjectionDispatcher(dispatch),
) {
    private val wireJson = Json { explicitNulls = true; encodeDefaults = true }
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun rebase(
        local: CoreProjectionPending,
        sent: CoreReconciliationSent,
        response: SyncResponse,
        dependencies: List<CoreTimerDependency>,
    ): CoreReconciliationResult {
        validateInput(local, sent, dependencies)
        val normalizedResponse = CoreCanonicalResponse.from(response)
        val input = CoreReconciliationInput(
            local = CoreLocalQueues.from(local),
            sent = CoreSentQueues.from(sent),
            response = normalizedResponse,
            timerDependencies = dependencies,
        )
        val output = dispatch(ReconciliationOperation, wireJson.encodeToString(input))
        val decoded = try {
            strictJson.decodeFromJsonElement(CoreReconciliationOutput.serializer(), output)
        } catch (error: Exception) {
            throw CoreProjectionException.InvalidOutput("Could not decode Shared Core reconciliation", error)
        }
        return validateOutput(decoded, local, sent, normalizedResponse, dependencies)
    }

    private fun validateInput(
        local: CoreProjectionPending,
        sent: CoreReconciliationSent,
        dependencies: List<CoreTimerDependency>,
    ) {
        fun <T> validateDomain(values: List<DeviceOperation<T>>, ids: (T) -> String) {
            val operationIds = values.map { operation ->
                if (operation.deviceId.isBlank()) invalidInput()
                ids(operation.value)
            }
            if (operationIds.any(String::isBlank) || operationIds.toSet().size != operationIds.size) {
                invalidInput()
            }
        }
        validateDomain(local.commands) { it.id }
        validateDomain(local.taskOperations) { it.id }
        validateDomain(local.durationOperations) { it.id }
        validateDomain(local.autoStartOperations) { it.id }
        validateDomain(local.selectedTaskOperations) { it.id }
        fun validateSent(ids: List<String>, localIds: Set<String>) {
            if (ids.any(String::isBlank) || ids.toSet().size != ids.size || !localIds.containsAll(ids)) {
                invalidInput()
            }
        }
        validateSent(sent.commands, local.commands.map { it.value.id }.toSet())
        validateSent(sent.taskOperations, local.taskOperations.map { it.value.id }.toSet())
        validateSent(sent.durationOperations, local.durationOperations.map { it.value.id }.toSet())
        validateSent(sent.autoStartOperations, local.autoStartOperations.map { it.value.id }.toSet())
        validateSent(sent.selectedTaskOperations, local.selectedTaskOperations.map { it.value.id }.toSet())
        val commandIds = local.commands.map { it.value.id }.toSet()
        if (dependencies.map(CoreTimerDependency::operationId).toSet().size != dependencies.size ||
            dependencies.any { dependency ->
                dependency.operationId !in commandIds || dependency.dependsOnOperationId !in commandIds ||
                    dependency.operationId == dependency.dependsOnOperationId ||
                    dependency.generatedBreak != (
                        dependency.sourceDayStart != null && dependency.sourceDayEnd != null
                    ) || listOfNotNull(dependency.sourceDayStart, dependency.sourceDayEnd).any {
                        runCatching { Instant.parse(it) }.isFailure
                    }
            }
        ) invalidInput()
    }

    private fun validateOutput(
        output: CoreReconciliationOutput,
        local: CoreProjectionPending,
        sent: CoreReconciliationSent,
        response: CoreCanonicalResponse,
        inputDependencies: List<CoreTimerDependency>,
    ): CoreReconciliationResult {
        if (output.revision != response.revision) invalidOutput()
        validateResponseAcknowledgements(sent, response)
        val pending = validatePendingQueues(output, local, sent)
        val decisions = validateTimerDecisions(output, local, response, inputDependencies)
        val dependencies = validateOutputDependencies(
            output, pending.commands, inputDependencies, decisions.promoted,
        )
        val base = validateOutputBase(output, response)
        val projection = validateOutputProjection(output, response, base, pending)
        return CoreReconciliationResult(
            revision = output.revision,
            pending = pending,
            dependencies = dependencies,
            promotedTimerOperationIds = decisions.promoted,
            droppedTimerOperationIds = decisions.dropped,
            droppedTimerIds = decisions.droppedTimerIds,
            base = base,
            projection = projection,
        )
    }

    private fun validateResponseAcknowledgements(
        sent: CoreReconciliationSent,
        response: CoreCanonicalResponse,
    ) {
        validateAcknowledgements(sent.commands, response.acknowledgements.map(Acknowledgement::commandId))
        validateAcknowledgements(
            sent.taskOperations, response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
        )
        validateAcknowledgements(
            sent.durationOperations, response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
        )
        validateAcknowledgements(
            sent.autoStartOperations, response.autoStartAcknowledgements.map(AutoStartAcknowledgement::operationId),
        )
        validateAcknowledgements(
            sent.selectedTaskOperations,
            response.selectedTaskAcknowledgements.map(SelectedTaskAcknowledgement::operationId),
        )
    }

    private fun validatePendingQueues(
        output: CoreReconciliationOutput,
        local: CoreProjectionPending,
        sent: CoreReconciliationSent,
    ) = CoreProjectionPending(
        commands = validateCommands(output.pending, local.commands, sent.commands, output.droppedTimerOperationIds),
        taskOperations = validateTasks(output.pendingTaskOperations, local.taskOperations, sent.taskOperations),
        durationOperations = validateDurations(
            output.pendingDurationOperations, local.durationOperations, sent.durationOperations,
        ),
        autoStartOperations = validateAutoStart(
            output.pendingAutoStartOperations, local.autoStartOperations, sent.autoStartOperations,
        ),
        selectedTaskOperations = validateSelectedTasks(
            output.pendingSelectedTaskOperations, local.selectedTaskOperations, sent.selectedTaskOperations,
        ),
    )

    private data class TimerDecisionSets(
        val promoted: Set<String>,
        val dropped: Set<String>,
        val droppedTimerIds: Set<String>,
    )

    private fun validateTimerDecisions(
        output: CoreReconciliationOutput,
        local: CoreProjectionPending,
        response: CoreCanonicalResponse,
        dependencies: List<CoreTimerDependency>,
    ): TimerDecisionSets {
        val localIds = local.commands.map { it.value.id }.toSet()
        val acknowledgedIds = response.acknowledgements.map(Acknowledgement::commandId).toSet()
        val dropped = output.droppedTimerOperationIds.requireUniqueNonBlank()
        val promoted = output.promotedTimerOperationIds.requireUniqueNonBlank()
        val droppedTimerIds = output.droppedTimerIds.requireUniqueNonBlank()
        val childIds = dependencies.map(CoreTimerDependency::operationId).toSet()
        val generatedTimerIds = generatedTimerIds(dependencies, local)
        val requiredDroppedTimerIds = generatedTimerIds(dependencies.filter { it.operationId in dropped }, local)
        if (!dropped.all { it in localIds - acknowledgedIds } ||
            !promoted.all { it in localIds - acknowledgedIds - dropped } ||
            !childIds.containsAll(dropped) || !childIds.containsAll(promoted) ||
            dropped.intersect(promoted).isNotEmpty() ||
            !generatedTimerIds.containsAll(droppedTimerIds) ||
            !droppedTimerIds.containsAll(requiredDroppedTimerIds)
        ) invalidOutput()
        return TimerDecisionSets(promoted, dropped, droppedTimerIds)
    }

    private fun generatedTimerIds(
        dependencies: List<CoreTimerDependency>,
        local: CoreProjectionPending,
    ): Set<String> = dependencies.asSequence()
        .filter(CoreTimerDependency::generatedBreak)
        .map { dependency ->
            local.commands.singleOrNull { it.value.id == dependency.operationId }
                ?.value?.timerId ?: invalidOutput()
        }
        .toSet()

    private fun validateOutputDependencies(
        output: CoreReconciliationOutput,
        pendingCommands: List<DeviceOperation<TimerCommand>>,
        inputDependencies: List<CoreTimerDependency>,
        promoted: Set<String>,
    ): List<CoreTimerDependency> {
        val retainedIds = pendingCommands.map { it.value.id }.toSet()
        val inputByChild = inputDependencies.associateBy(CoreTimerDependency::operationId)
        if (inputByChild.size != inputDependencies.size) invalidOutput()
        val outputChildren = mutableSetOf<String>()
        output.pendingTimerDependencies.forEach { dependency ->
            validateOutputDependency(dependency, retainedIds, inputByChild, outputChildren)
        }
        val expected = inputDependencies.filter { dependency ->
            dependency.operationId in retainedIds &&
                dependency.dependsOnOperationId in retainedIds && dependency.operationId !in promoted
        }.toSet()
        if (output.pendingTimerDependencies.toSet() != expected) invalidOutput()
        return output.pendingTimerDependencies
    }

    private fun validateOutputDependency(
        dependency: CoreTimerDependency,
        retainedIds: Set<String>,
        inputByChild: Map<String, CoreTimerDependency>,
        outputChildren: MutableSet<String>,
    ) {
        if (dependency.operationId.isBlank() || dependency.dependsOnOperationId.isBlank() ||
            dependency.operationId == dependency.dependsOnOperationId ||
            dependency.operationId !in retainedIds || dependency.dependsOnOperationId !in retainedIds ||
            !outputChildren.add(dependency.operationId) || inputByChild[dependency.operationId] != dependency ||
            dependency.generatedBreak != (dependency.sourceDayStart != null && dependency.sourceDayEnd != null) ||
            listOfNotNull(dependency.sourceDayStart, dependency.sourceDayEnd).any {
                runCatching { Instant.parse(it) }.isFailure
            }
        ) invalidOutput()
    }

    private fun validateOutputBase(
        output: CoreReconciliationOutput,
        response: CoreCanonicalResponse,
    ): CoreProjectionBase {
        val expected = response.projectionBase()
        val actual = CoreProjectionBase(
            output.baseTimer, output.baseHistory, output.baseTasks, output.baseDurationsMs.toModel(),
            output.baseAutoStartBreaks, output.baseSelectedTaskId,
        )
        if (actual != expected) invalidOutput()
        return actual
    }

    private fun validateOutputProjection(
        output: CoreReconciliationOutput,
        response: CoreCanonicalResponse,
        base: CoreProjectionBase,
        pending: CoreProjectionPending,
    ): CoreProjectionResult {
        val projection = projectionDispatcher.apply(
            base, pending, runCatching { Instant.parse(response.serverTime) }.getOrElse { invalidOutput() },
        )
        if (projection.canonicalTimer != output.timer || projection.history != output.history ||
            projection.tasks != output.tasks || projection.durationsMs != output.durationsMs.toModel() ||
            projection.autoStartBreaks != output.autoStartBreaks ||
            projection.selectedTaskId != output.selectedTaskId
        ) invalidOutput()
        return projection
    }

    private fun validateCommands(
        output: List<CoreWireTimerCommand>,
        local: List<DeviceOperation<TimerCommand>>,
        sent: List<String>,
        dropped: List<String>,
    ): List<DeviceOperation<TimerCommand>> {
        val originals = local.associateBy { it.value.id }
        val expected = originals.keys - sent.toSet() - dropped.toSet()
        if (output.map { it.id }.toSet() != expected || output.map { it.id }.toSet().size != output.size) {
            invalidOutput()
        }
        return output.map { wire ->
            val original = originals[wire.id] ?: invalidOutput()
            val value = wire.toModel(original.value.physicalOccurredAt)
            if (wire.deviceId != original.deviceId || value.copy(
                    phase = original.value.phase,
                    plannedDurationMs = original.value.plannedDurationMs,
                    occurredAt = original.value.occurredAt,
                    hlcWallMs = original.value.hlcWallMs,
                    hlcCounter = original.value.hlcCounter,
                    observedElapsedMs = original.value.observedElapsedMs,
                ) != original.value
            ) invalidOutput()
            DeviceOperation(wire.deviceId, value)
        }
    }

    private fun validateTasks(
        output: List<CoreWireTaskOperation>,
        local: List<DeviceOperation<TaskOperation>>,
        sent: List<String>,
    ): List<DeviceOperation<TaskOperation>> = validateOperations(
        output,
        local,
        sent,
        CoreWireTaskOperation::id,
        CoreWireTaskOperation::deviceId,
        CoreWireTaskOperation::toModel,
    ) { next, original ->
        next.copy(
            occurredAt = original.occurredAt,
            hlcWallMs = original.hlcWallMs,
            hlcCounter = original.hlcCounter,
        ) == original
    }

    private fun validateDurations(
        output: List<CoreWireDurationOperation>,
        local: List<DeviceOperation<DurationOperation>>,
        sent: List<String>,
    ): List<DeviceOperation<DurationOperation>> = validateOperations(
        output,
        local,
        sent,
        CoreWireDurationOperation::id,
        CoreWireDurationOperation::deviceId,
        CoreWireDurationOperation::toModel,
    ) { next, original ->
        next.copy(
            occurredAt = original.occurredAt,
            hlcWallMs = original.hlcWallMs,
            hlcCounter = original.hlcCounter,
        ) == original
    }

    private fun validateAutoStart(
        output: List<CoreWireAutoStartOperation>,
        local: List<DeviceOperation<AutoStartOperation>>,
        sent: List<String>,
    ): List<DeviceOperation<AutoStartOperation>> = validateOperations(
        output,
        local,
        sent,
        CoreWireAutoStartOperation::id,
        CoreWireAutoStartOperation::deviceId,
        CoreWireAutoStartOperation::toModel,
    ) { next, original ->
        next.copy(
            occurredAt = original.occurredAt,
            hlcWallMs = original.hlcWallMs,
            hlcCounter = original.hlcCounter,
        ) == original
    }

    private fun validateSelectedTasks(
        output: List<CoreWireSelectedTaskOperation>,
        local: List<DeviceOperation<SelectedTaskOperation>>,
        sent: List<String>,
    ): List<DeviceOperation<SelectedTaskOperation>> = validateOperations(
        output,
        local,
        sent,
        CoreWireSelectedTaskOperation::id,
        CoreWireSelectedTaskOperation::deviceId,
        CoreWireSelectedTaskOperation::toModel,
    ) { next, original ->
        next.copy(
            occurredAt = original.occurredAt,
            hlcWallMs = original.hlcWallMs,
            hlcCounter = original.hlcCounter,
        ) == original
    }

    private fun <Wire, Model> validateOperations(
        output: List<Wire>,
        local: List<DeviceOperation<Model>>,
        sent: List<String>,
        id: (Wire) -> String,
        deviceId: (Wire) -> String,
        toModel: (Wire) -> Model,
        unchangedExceptClock: (Model, Model) -> Boolean,
    ): List<DeviceOperation<Model>> {
        val originals = local.associateBy { operationId(it.value) }
        val outputIds = output.map(id)
        if (outputIds.toSet().size != outputIds.size || outputIds.toSet() != originals.keys - sent.toSet()) {
            invalidOutput()
        }
        return output.map { wire ->
            val operationId = id(wire)
            val original = originals[operationId] ?: invalidOutput()
            val value = toModel(wire)
            if (deviceId(wire) != original.deviceId || !unchangedExceptClock(value, original.value)) {
                invalidOutput()
            }
            DeviceOperation(deviceId(wire), value)
        }
    }

    private fun operationId(value: Any?): String = when (value) {
        is TaskOperation -> value.id
        is DurationOperation -> value.id
        is AutoStartOperation -> value.id
        is SelectedTaskOperation -> value.id
        else -> invalidInput()
    }

    private fun validateAcknowledgements(sent: List<String>, acknowledged: List<String>) {
        if (acknowledged.toSet().size != acknowledged.size || acknowledged.toSet() != sent.toSet()) {
            invalidOutput()
        }
    }

    private fun List<String>.requireUniqueNonBlank(): Set<String> {
        if (any(String::isBlank) || toSet().size != size) invalidOutput()
        return toSet()
    }

    private fun invalidInput(): Nothing =
        throw CoreProjectionException.InvalidInput("Shared Core reconciliation input is invalid")

    private fun invalidOutput(): Nothing =
        throw CoreProjectionException.InvalidOutput("Shared Core returned invalid reconciliation")

    private companion object {
        const val ReconciliationOperation = "reconcile.rebase.v1"
    }
}

@Serializable
private data class CoreIdentified(val id: String)

@Serializable
private data class CoreSentQueues(
    val commands: List<CoreIdentified>,
    val taskOperations: List<CoreIdentified>,
    val durationOperations: List<CoreIdentified>,
    val autoStartOperations: List<CoreIdentified>,
    val selectedTaskOperations: List<CoreIdentified>,
) {
    companion object {
        fun from(value: CoreReconciliationSent) = CoreSentQueues(
            value.commands.map(::CoreIdentified),
            value.taskOperations.map(::CoreIdentified),
            value.durationOperations.map(::CoreIdentified),
            value.autoStartOperations.map(::CoreIdentified),
            value.selectedTaskOperations.map(::CoreIdentified),
        )
    }
}

@Serializable
private data class CoreLocalQueues(
    val commands: List<CoreWireTimerCommand>,
    val taskOperations: List<CoreWireTaskOperation>,
    val durationOperations: List<CoreWireDurationOperation>,
    val autoStartOperations: List<CoreWireAutoStartOperation>,
    val selectedTaskOperations: List<CoreWireSelectedTaskOperation>,
) {
    companion object {
        fun from(value: CoreProjectionPending) = CoreLocalQueues(
            value.commands.map(CoreWireTimerCommand::from),
            value.taskOperations.map(CoreWireTaskOperation::from),
            value.durationOperations.map(CoreWireDurationOperation::from),
            value.autoStartOperations.map(CoreWireAutoStartOperation::from),
            value.selectedTaskOperations.map(CoreWireSelectedTaskOperation::from),
        )
    }
}

@Serializable
private data class CoreCanonicalResponse(
    val acknowledgements: List<Acknowledgement>,
    val taskAcknowledgements: List<TaskAcknowledgement>,
    val durationAcknowledgements: List<DurationAcknowledgement>,
    val autoStartAcknowledgements: List<AutoStartAcknowledgement>,
    val selectedTaskAcknowledgements: List<SelectedTaskAcknowledgement>,
    val revision: Long,
    val canonicalTimer: CoreWireCanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val durationsMs: CoreWireDurationsMs,
    val autoStartBreaks: Boolean,
    val selectedTaskId: String?,
    val serverTime: String,
    val serverHlcWallMs: Long,
    val serverHlcCounter: Long,
) {
    fun projectionBase() = CoreProjectionBase(
        canonicalTimer = canonicalTimer?.toModel(),
        history = history,
        tasks = tasks,
        durationsMs = durationsMs.toModel(),
        autoStartBreaks = autoStartBreaks,
        selectedTaskId = selectedTaskId,
    )

    companion object {
        fun from(value: SyncResponse): CoreCanonicalResponse {
            val normalizedTimer = value.canonicalTimer?.takeUnless { timer ->
                value.history.any { it.timerId == timer.id }
            }
            return CoreCanonicalResponse(
                value.acknowledgements,
                value.taskAcknowledgements,
                value.durationAcknowledgements,
                value.autoStartAcknowledgements,
                value.selectedTaskAcknowledgements,
                value.revision,
                normalizedTimer?.let(CoreWireCanonicalTimer::from),
                value.history,
                value.tasks,
                CoreWireDurationsMs.from(value.durationsMs),
                value.autoStartBreaks,
                value.selectedTaskId,
                value.serverTime,
                value.serverHlcWallMs,
                value.serverHlcCounter,
            )
        }
    }
}

@Serializable
private data class CoreWireCanonicalTimer(
    val id: String,
    val taskId: String? = null,
    val phase: String,
    val status: String,
    val plannedDurationMs: Long,
    val elapsedAtAnchorMs: Long,
    val anchorAt: String,
    val startedByDeviceId: String = "",
    val lastIntent: CoreWireTimerIntent? = null,
) {
    fun toModel() = CanonicalTimer(
        id = id,
        phase = phase,
        status = status,
        plannedDurationMs = plannedDurationMs,
        elapsedAtAnchorMs = elapsedAtAnchorMs,
        anchorAt = anchorAt,
        taskId = taskId,
        startedByDeviceId = startedByDeviceId.ifEmpty { null },
        lastIntent = lastIntent?.toModel(),
    )

    companion object {
        fun from(value: CanonicalTimer) = CoreWireCanonicalTimer(
            id = value.id,
            taskId = value.taskId,
            phase = value.phase,
            status = value.status,
            plannedDurationMs = value.plannedDurationMs,
            elapsedAtAnchorMs = value.elapsedAtAnchorMs,
            anchorAt = value.anchorAt,
            startedByDeviceId = value.startedByDeviceId.orEmpty(),
            lastIntent = value.lastIntent?.let(CoreWireTimerIntent::from),
        )
    }
}

@Serializable
private data class CoreWireTimerIntent(
    @SerialName("type") val type: String,
    val commandId: String,
    val occurredAt: String,
) {
    fun toModel() = TimerIntent(type, commandId, occurredAt)

    companion object {
        fun from(value: TimerIntent) = CoreWireTimerIntent(value.type, value.commandId, value.occurredAt)
    }
}

@Serializable
private data class CoreReconciliationInput(
    val local: CoreLocalQueues,
    val sent: CoreSentQueues,
    val response: CoreCanonicalResponse,
    val timerDependencies: List<CoreTimerDependency>,
)

@Serializable
private data class CoreReconciliationOutput(
    val revision: Long,
    val pending: List<CoreWireTimerCommand>,
    val pendingTaskOperations: List<CoreWireTaskOperation>,
    val pendingDurationOperations: List<CoreWireDurationOperation>,
    val pendingAutoStartOperations: List<CoreWireAutoStartOperation>,
    val pendingSelectedTaskOperations: List<CoreWireSelectedTaskOperation>,
    val pendingTimerDependencies: List<CoreTimerDependency>,
    val promotedTimerOperationIds: List<String>,
    val droppedTimerOperationIds: List<String>,
    val droppedTimerIds: List<String>,
    val baseTimer: CanonicalTimer?,
    val baseHistory: List<HistoryItem>,
    val baseTasks: List<FocusTask>,
    val baseDurationsMs: CoreWireDurationsMs,
    val baseAutoStartBreaks: Boolean,
    val baseSelectedTaskId: String?,
    val timer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val durationsMs: CoreWireDurationsMs,
    val autoStartBreaks: Boolean,
    val selectedTaskId: String?,
)

@Serializable
private data class CoreWireDurationsMs(
    val focus: Long,
    @SerialName("short_break") val shortBreak: Long,
    @SerialName("long_break") val longBreak: Long,
) {
    fun toModel() = DurationsMs(focus, shortBreak, longBreak)

    companion object {
        fun from(value: DurationsMs) = CoreWireDurationsMs(value.focus, value.shortBreak, value.longBreak)
    }
}

@Serializable
private data class CoreWireTimerCommand(
    val id: String,
    val deviceId: String,
    val deviceSequence: Long,
    val timerId: String,
    val taskId: String? = null,
    @SerialName("type") val type: String,
    val phase: String,
    val plannedDurationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val observedElapsedMs: Long,
) {
    fun toModel(physicalOccurredAt: String?) = TimerCommand(
        id, deviceSequence, timerId, type, phase, plannedDurationMs, occurredAt,
        hlcWallMs, hlcCounter, observedElapsedMs, taskId, physicalOccurredAt,
    )

    companion object {
        fun from(value: DeviceOperation<TimerCommand>) = value.value.let { operation ->
            CoreWireTimerCommand(
                operation.id, value.deviceId, operation.deviceSequence, operation.timerId,
                operation.taskId, operation.type, operation.phase, operation.plannedDurationMs,
                operation.occurredAt, operation.hlcWallMs, operation.hlcCounter,
                operation.observedElapsedMs,
            )
        }
    }
}

@Serializable
private data class CoreWireTaskOperation(
    val id: String,
    val deviceId: String,
    val taskId: String,
    @SerialName("type") val type: String,
    val title: String,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = TaskOperation(
        id, taskId, type, title.takeUnless { type == TaskOperationType.Delete && it.isEmpty() },
        occurredAt, hlcWallMs, hlcCounter,
    )

    companion object {
        fun from(value: DeviceOperation<TaskOperation>) = value.value.let { operation ->
            CoreWireTaskOperation(
                operation.id, value.deviceId, operation.taskId, operation.type,
                operation.title.orEmpty(), operation.occurredAt, operation.hlcWallMs, operation.hlcCounter,
            )
        }
    }
}

@Serializable
private data class CoreWireDurationOperation(
    val id: String,
    val deviceId: String,
    val phase: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = DurationOperation(id, phase, durationMs, occurredAt, hlcWallMs, hlcCounter)

    companion object {
        fun from(value: DeviceOperation<DurationOperation>) = value.value.let { operation ->
            CoreWireDurationOperation(
                operation.id, value.deviceId, operation.phase, operation.durationMs,
                operation.occurredAt, operation.hlcWallMs, operation.hlcCounter,
            )
        }
    }
}

@Serializable
private data class CoreWireAutoStartOperation(
    val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = AutoStartOperation(id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter)

    companion object {
        fun from(value: DeviceOperation<AutoStartOperation>) = value.value.let { operation ->
            CoreWireAutoStartOperation(
                operation.id, value.deviceId, operation.enabled, operation.occurredAt,
                operation.hlcWallMs, operation.hlcCounter,
            )
        }
    }
}

@Serializable
private data class CoreWireSelectedTaskOperation(
    val id: String,
    val deviceId: String,
    val taskId: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = SelectedTaskOperation(id, taskId, occurredAt, hlcWallMs, hlcCounter)

    companion object {
        fun from(value: DeviceOperation<SelectedTaskOperation>) = value.value.let { operation ->
            CoreWireSelectedTaskOperation(
                operation.id, value.deviceId, operation.taskId, operation.occurredAt,
                operation.hlcWallMs, operation.hlcCounter,
            )
        }
    }
}
