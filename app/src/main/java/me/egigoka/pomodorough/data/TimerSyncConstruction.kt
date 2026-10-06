package me.egigoka.pomodorough.data

import java.util.UUID

internal data class SyncAttempt(
    val identity: SyncAttemptIdentity,
    val request: SyncRequest,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
    val selectedPhaseAtSend: String,
    val selectedPhaseGenerationAtSend: Long,
    val nextDomain: String = "commands",
) {
    val accountGeneration: Long get() = identity.accountGeneration
}

internal data class SyncAttemptIdentity(
    val accountGeneration: Long,
    val attemptId: String,
)

internal data class BootstrapResolutionAttempt(
    val accountGeneration: Long,
    val request: BootstrapResolutionRequest,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
)

internal data class ServerClockSample(
    val offsetMs: Long,
    val uncertaintyMs: Long,
    val serverTimeMs: Long,
    val midpointPhysicalMs: Long,
    val midpointElapsedRealtimeMs: Long,
)

internal data class RequestTiming(
    val uncertaintyMs: Long,
    val midpointPhysicalMs: Long,
    val midpointElapsedRealtimeMs: Long,
)

internal data class PendingSyncQueues(
    val commands: List<TimerCommand>,
    val taskOperations: List<TaskOperation>,
    val durationOperations: List<DurationOperation>,
    val autoStartOperations: List<AutoStartOperation>,
    val selectedTaskOperations: List<SelectedTaskOperation>,
)

internal data class SentSyncIds(
    val commands: Set<String>,
    val taskOperations: Set<String>,
    val durationOperations: Set<String>,
    val autoStartOperations: Set<String>,
    val selectedTaskOperations: Set<String>,
)

internal object TimerCommandRequestEncoder {
    fun encode(command: TimerCommand): TimerCommand {
        return command.copy(physicalOccurredAt = null)
    }
}

internal object TimerSyncConstruction {
    fun syncAttempt(
        identity: SyncAttemptIdentity,
        deviceId: String,
        revision: Long,
        queues: PendingSyncQueues,
        plan: CoreBatchPlan,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        selectedPhase: String,
        selectedPhaseGeneration: Long,
    ): SyncAttempt = SyncAttempt(
        identity = identity,
        request = syncRequest(deviceId, revision, queues, plan),
        sentPhysicalMs = sentPhysicalMs,
        sentElapsedRealtimeMs = sentElapsedRealtimeMs,
        selectedPhaseAtSend = selectedPhase,
        selectedPhaseGenerationAtSend = selectedPhaseGeneration,
        nextDomain = requireNotNull(plan.nextDomain) { "Core batch plan has no cursor" },
    )

    fun bootstrapRequest(
        deviceId: String,
        revision: Long,
        strategy: BootstrapStrategy,
        eligibleCommands: List<TimerCommand>,
        queues: PendingSyncQueues,
    ): BootstrapResolutionRequest {
        val includeLocal = strategy != BootstrapStrategy.KeepRemote
        return BootstrapResolutionRequest(
            requestId = "bootstrap-${UUID.randomUUID()}",
            deviceId = deviceId,
            expectedRevision = revision,
            strategy = strategy,
            commands = eligibleCommands.takeIf { includeLocal }.orEmpty()
                .map(TimerCommandRequestEncoder::encode),
            taskOperations = queues.taskOperations.takeIf { includeLocal }.orEmpty(),
            durationOperations = queues.durationOperations.takeIf { includeLocal }.orEmpty(),
            autoStartOperations = queues.autoStartOperations.takeIf { includeLocal }.orEmpty(),
            selectedTaskOperations = queues.selectedTaskOperations.takeIf { includeLocal }.orEmpty(),
        )
    }

    fun bootstrapRequestFromPlan(
        deviceId: String,
        revision: Long,
        strategy: BootstrapStrategy,
        queues: PendingSyncQueues,
        plan: CoreBatchPlan,
    ): BootstrapResolutionRequest {
        require(plan.status == "planned") { "Core bootstrap batch is not planned" }
        val includeLocal = strategy != BootstrapStrategy.KeepRemote
        if (!includeLocal) {
            require(plan.selected.total() == 0) { "Core keep-remote batch must be empty" }
        }
        return BootstrapResolutionRequest(
            requestId = "bootstrap-${UUID.randomUUID()}",
            deviceId = deviceId,
            expectedRevision = revision,
            strategy = strategy,
            commands = resolveCommands(queues.commands, plan.selected.commands)
                .takeIf { includeLocal }.orEmpty().map(TimerCommandRequestEncoder::encode),
            taskOperations = resolveById(
                queues.taskOperations,
                plan.selected.taskOperations,
                TaskOperation::id,
            ).takeIf { includeLocal }.orEmpty(),
            durationOperations = resolveById(
                queues.durationOperations,
                plan.selected.durationOperations,
                DurationOperation::id,
            ).takeIf { includeLocal }.orEmpty(),
            autoStartOperations = resolveById(
                queues.autoStartOperations,
                plan.selected.autoStartOperations,
                AutoStartOperation::id,
            ).takeIf { includeLocal }.orEmpty(),
            selectedTaskOperations = resolveById(
                queues.selectedTaskOperations,
                plan.selected.selectedTaskOperations,
                SelectedTaskOperation::id,
            ).takeIf { includeLocal }.orEmpty(),
        )
    }
    fun sentIds(request: SyncRequest): SentSyncIds = SentSyncIds(
        commands = request.commands.map(TimerCommand::id).toSet(),
        taskOperations = request.taskOperations.map(TaskOperation::id).toSet(),
        durationOperations = request.durationOperations.map(DurationOperation::id).toSet(),
        autoStartOperations = request.autoStartOperations.map(AutoStartOperation::id).toSet(),
        selectedTaskOperations = request.selectedTaskOperations.map(SelectedTaskOperation::id).toSet(),
    )

    fun mergedQueues(current: PendingSyncQueues, sent: SyncRequest): PendingSyncQueues =
        PendingSyncQueues(
            commands = merge(current.commands, sent.commands, TimerCommand::id),
            taskOperations = merge(current.taskOperations, sent.taskOperations, TaskOperation::id),
            durationOperations = merge(
                current.durationOperations,
                sent.durationOperations,
                DurationOperation::id,
            ),
            autoStartOperations = merge(
                current.autoStartOperations,
                sent.autoStartOperations,
                AutoStartOperation::id,
            ),
            selectedTaskOperations = merge(
                current.selectedTaskOperations,
                sent.selectedTaskOperations,
                SelectedTaskOperation::id,
            ),
        )

    private fun syncRequest(
        deviceId: String,
        revision: Long,
        queues: PendingSyncQueues,
        plan: CoreBatchPlan,
    ) = SyncRequest(
        deviceId = deviceId,
        lastRevision = revision,
        commands = resolveCommands(queues.commands, plan.selected.commands)
            .map(TimerCommandRequestEncoder::encode),
        durationOperations = resolveById(
            queues.durationOperations,
            plan.selected.durationOperations,
            DurationOperation::id,
        ),
        taskOperations = resolveById(
            queues.taskOperations,
            plan.selected.taskOperations,
            TaskOperation::id,
        ),
        autoStartOperations = resolveById(
            queues.autoStartOperations,
            plan.selected.autoStartOperations,
            AutoStartOperation::id,
        ),
        selectedTaskOperations = resolveById(
            queues.selectedTaskOperations,
            plan.selected.selectedTaskOperations,
            SelectedTaskOperation::id,
        ),
    )

    private fun resolveCommands(
        complete: List<TimerCommand>,
        selected: List<String>,
    ): List<TimerCommand> {
        val byId = complete.associateBy(TimerCommand::id)
        return selected.map { id ->
            requireNotNull(byId[id]) { "Core selected an unknown timer command" }
        }
    }

    private fun <T> resolveById(
        complete: List<T>,
        selected: List<String>,
        id: (T) -> String,
    ): List<T> {
        val byId = complete.associateBy(id)
        return selected.map { value ->
            requireNotNull(byId[value]) { "Core selected an unknown batch operation" }
        }
    }

    private fun <T> merge(current: List<T>, sent: List<T>, id: (T) -> String): List<T> =
        (sent + current).associateBy(id).values.toList()
}
