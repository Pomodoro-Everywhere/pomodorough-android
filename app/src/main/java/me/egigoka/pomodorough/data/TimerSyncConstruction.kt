package me.egigoka.pomodorough.data

import java.util.UUID
import me.egigoka.pomodorough.domain.SettingsReducer

internal data class SyncAttempt(
    val identity: SyncAttemptIdentity,
    val request: SyncRequest,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
    val selectedPhaseAtSend: String,
    val selectedPhaseGenerationAtSend: Long,
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
        eligibleCommands: List<TimerCommand>,
        queues: PendingSyncQueues,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        selectedPhase: String,
        selectedPhaseGeneration: Long,
    ): SyncAttempt = SyncAttempt(
        identity = identity,
        request = syncRequest(deviceId, revision, eligibleCommands, queues),
        sentPhysicalMs = sentPhysicalMs,
        sentElapsedRealtimeMs = sentElapsedRealtimeMs,
        selectedPhaseAtSend = selectedPhase,
        selectedPhaseGenerationAtSend = selectedPhaseGeneration,
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
        eligibleCommands: List<TimerCommand>,
        queues: PendingSyncQueues,
    ) = SyncRequest(
        deviceId = deviceId,
        lastRevision = revision,
        commands = eligibleCommands.take(MaxOperationsPerSync)
            .map(TimerCommandRequestEncoder::encode),
        durationOperations = queues.durationOperations.sortedWith(durationComparator)
            .take(MaxOperationsPerSync),
        taskOperations = queues.taskOperations.take(MaxOperationsPerSync),
        autoStartOperations = queues.autoStartOperations.sortedWith(autoStartComparator)
            .take(MaxOperationsPerSync),
        selectedTaskOperations = queues.selectedTaskOperations.sortedWith(selectedTaskComparator)
            .take(MaxOperationsPerSync),
    )

    private fun <T> merge(current: List<T>, sent: List<T>, id: (T) -> String): List<T> =
        (sent + current).associateBy(id).values.toList()

    private const val MaxOperationsPerSync = 256
    private val durationComparator = SettingsReducer.durationComparator
    private val autoStartComparator = SettingsReducer.autoStartComparator
    private val selectedTaskComparator = compareBy<SelectedTaskOperation>(
        SelectedTaskOperation::hlcWallMs,
        SelectedTaskOperation::hlcCounter,
        SelectedTaskOperation::id,
    )
}
