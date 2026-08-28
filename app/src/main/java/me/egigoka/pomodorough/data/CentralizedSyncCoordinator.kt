package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.local.LocalStateEntity

internal data class CentralizedSyncSnapshot(
    val local: LocalStateEntity,
    val queues: PendingSyncQueues,
    val dependencies: Map<String, String>,
    val canonicalTimer: CanonicalTimer?,
    val canonicalHistory: List<HistoryItem>,
    val canonicalTasks: List<FocusTask>,
    val canonicalAutoStartBreaks: Boolean,
    val knownTasks: Map<String, FocusTask>,
    val settings: TimerSettings,
    val selectedPhaseGeneration: Long,
)

internal data class CentralizedCanonicalState(
    val timer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val knownTasks: Map<String, FocusTask>,
)

internal data class CentralizedReconciledPending(
    val local: LocalStateEntity,
    val queues: PendingSyncQueues,
    val dependencies: Map<String, String>,
    val core: CoreReconciliationResult,
)

internal data class CentralizedProjectedState(
    val queues: PendingSyncQueues,
    val settings: TimerSettings,
    val projection: CoreProjectionResult,
)

internal data class GeneratedTimerResolution(
    val released: List<TimerCommand>,
    val discarded: List<TimerCommand>,
    val discardedSourceTimerIds: Set<String>,
)

internal sealed interface CentralizedConflictTransition {
    data object Keep : CentralizedConflictTransition
    data class Replace(val conflict: String?) : CentralizedConflictTransition
}

internal data class CentralizedSyncApplication(
    val local: LocalStateEntity,
    val pending: CentralizedReconciledPending,
    val canonical: CentralizedCanonicalState,
    val projected: CentralizedProjectedState,
    val generatedCommands: GeneratedTimerResolution,
    val conflict: CentralizedConflictTransition,
)

internal data class CentralizedSyncAttemptInput(
    val identity: SyncAttemptIdentity,
    val snapshot: CentralizedSyncSnapshot,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
)

internal data class CentralizedSyncApplicationInput(
    val snapshot: CentralizedSyncSnapshot,
    val attempt: SyncAttempt,
    val response: SyncResponse,
    val sampledLocal: LocalStateEntity,
    val localizedTimer: CanonicalTimer?,
    val localizedHistory: List<HistoryItem>,
    val projectionNow: Instant,
)

internal data class CentralizedBootstrapPlanningInput(
    val localOwnerId: String?,
    val currentUserId: String,
    val localHistory: List<HistoryItem>,
    val remoteHistory: List<HistoryItem>,
    val hasLocalState: Boolean,
    val hasRemoteState: Boolean,
)

internal data class CentralizedBootstrapPreparationInput(
    val snapshot: CentralizedSyncSnapshot,
    val bootstrap: SyncResponse,
    val strategy: BootstrapStrategy,
    val sampledLocal: LocalStateEntity,
    val projectionNow: Instant,
)

internal sealed interface CentralizedBootstrapPreparationTransition {
    data class Invalid(val error: Throwable) : CentralizedBootstrapPreparationTransition
    data class Planned(
        val request: BootstrapResolutionRequest,
        val pending: CentralizedReconciledPending,
        val projection: CoreProjectionResult,
    ) : CentralizedBootstrapPreparationTransition
}

internal data class CentralizedBootstrapInstallationInput(
    val snapshot: CentralizedSyncSnapshot,
    val profile: User,
    val response: SyncResponse,
    val clearLocal: Boolean,
    val sampledLocal: LocalStateEntity,
    val localizedTimer: CanonicalTimer?,
    val localizedHistory: List<HistoryItem>,
    val projectionNow: Instant,
)

internal data class CentralizedBootstrapResolutionInput(
    val snapshot: CentralizedSyncSnapshot,
    val profile: User,
    val request: BootstrapResolutionRequest,
    val response: SyncResponse,
    val acknowledgementResponse: SyncResponse,
    val sampledLocal: LocalStateEntity,
    val localizedTimer: CanonicalTimer?,
    val localizedHistory: List<HistoryItem>,
    val projectionNow: Instant,
)

internal class CentralizedSyncCoordinator(
    private val json: Json,
    private val bootstrapDispatcher: CoreBootstrapDispatcher,
    private val reconciliationDispatcher: CoreReconciliationDispatcher,
    private val projectionDispatcher: CoreProjectionDispatcher,
    private val completionDispatcher: CoreCompletionDispatcher,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun prepareSyncAttempt(input: CentralizedSyncAttemptInput): SyncAttempt {
        val snapshot = input.snapshot
        TimerSyncValidation.validatePendingQueues(snapshot.queues, snapshot.local.deviceId)
        return TimerSyncConstruction.syncAttempt(
            identity = input.identity,
            deviceId = snapshot.local.deviceId,
            revision = snapshot.local.revision,
            eligibleCommands = eligibleCommands(snapshot.queues, snapshot.dependencies),
            queues = snapshot.queues,
            sentPhysicalMs = input.sentPhysicalMs,
            sentElapsedRealtimeMs = input.sentElapsedRealtimeMs,
            selectedPhase = snapshot.settings.selectedPhase,
            selectedPhaseGeneration = snapshot.selectedPhaseGeneration,
        )
    }

    fun applySync(input: CentralizedSyncApplicationInput): CentralizedSyncApplication {
        val snapshot = input.snapshot
        val response = input.response
        val reconciled = reconcileSyncAttempt(input)
        val generated = generatedResolution(reconciled.core, snapshot)
        val canonical = canonicalState(
            input.localizedTimer,
            input.localizedHistory,
            response.tasks,
            snapshot.knownTasks,
            clearLocal = false,
        )
        val (correctedPending, projected) = projectSyncPending(
            snapshot,
            input.attempt,
            response,
            reconciled,
            canonical,
            input.projectionNow,
        )
        val local = synchronizedLocal(
            base = correctedPending.local,
            snapshot = snapshot,
            response = response,
            canonical = canonical,
            projected = projected,
            generated = generated,
            clearOwnedTimer = false,
        )
        return CentralizedSyncApplication(
            local = local,
            pending = correctedPending,
            canonical = canonical,
            projected = projected,
            generatedCommands = generated,
            conflict = conflictTransition(
                response,
                correctedPending.queues,
                snapshot.dependencies.values.toSet(),
            ),
        )
    }

    fun bootstrapPlan(input: CentralizedBootstrapPlanningInput): CoreBootstrapPlan =
        bootstrapDispatcher.plan(
            localOwnerId = input.localOwnerId,
            currentUserId = input.currentUserId,
            localHistory = input.localHistory,
            remoteHistory = input.remoteHistory,
            hasLocalState = input.hasLocalState,
            hasRemoteState = input.hasRemoteState,
        )

    fun canonicalBootstrapResponse(
        latestBootstrap: SyncResponse?,
        response: SyncResponse,
    ): SyncResponse = latestBootstrap
        ?.takeIf { it.revision > response.revision }
        ?.copy(
            acknowledgements = response.acknowledgements,
            taskAcknowledgements = response.taskAcknowledgements,
            durationAcknowledgements = response.durationAcknowledgements,
            autoStartAcknowledgements = response.autoStartAcknowledgements,
            selectedTaskAcknowledgements = response.selectedTaskAcknowledgements,
        )
        ?: response

    fun prepareBootstrapResolution(
        input: CentralizedBootstrapPreparationInput,
    ): CentralizedBootstrapPreparationTransition {
        val snapshot = input.snapshot
        val discardLocal = input.strategy == BootstrapStrategy.KeepRemote
        val resolutionQueues = if (discardLocal) {
            PendingSyncQueues(
                commands = emptyList(),
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            )
        } else {
            snapshot.queues
        }
        val resolutionDependencies = if (discardLocal) emptyMap() else snapshot.dependencies
        bootstrapPreflightError(input, resolutionQueues, resolutionDependencies)?.let {
            return CentralizedBootstrapPreparationTransition.Invalid(it)
        }
        val reconciled = reconcile(
            response = input.bootstrap,
            queues = resolutionQueues,
            sent = CoreReconciliationSent(),
            dependencies = resolutionDependencies,
            sampledLocal = input.sampledLocal,
        )
        val projection = projectionDispatcher.apply(
            snapshot.projectionBase(),
            reconciled.queues.toCorePending(snapshot.local.deviceId),
            input.projectionNow,
        )
        val request = TimerSyncConstruction.bootstrapRequest(
            deviceId = snapshot.local.deviceId,
            revision = input.bootstrap.revision,
            strategy = input.strategy,
            eligibleCommands = eligibleCommands(reconciled.queues, reconciled.dependencies),
            queues = reconciled.queues,
        )
        val error = runCatching {
            TimerSyncValidation.validateResolutionEnvelope(request, snapshot.local.deviceId)
        }.exceptionOrNull()
        return if (error == null) {
            CentralizedBootstrapPreparationTransition.Planned(request, reconciled, projection)
        } else {
            CentralizedBootstrapPreparationTransition.Invalid(error)
        }
    }

    private fun bootstrapPreflightError(
        input: CentralizedBootstrapPreparationInput,
        queues: PendingSyncQueues,
        dependencies: Map<String, String>,
    ): Throwable? {
        val request = TimerSyncConstruction.bootstrapRequest(
            deviceId = input.snapshot.local.deviceId,
            revision = input.bootstrap.revision,
            strategy = input.strategy,
            eligibleCommands = eligibleCommands(queues, dependencies),
            queues = queues,
        )
        return runCatching {
            TimerSyncValidation.validateResolutionCollectionSizes(request)
        }.exceptionOrNull()
    }

    fun applyBootstrapInstallation(
        input: CentralizedBootstrapInstallationInput,
    ): CentralizedSyncApplication {
        val snapshot = input.snapshot
        val reconciled = reconcileBootstrapInstallation(input)
        val canonical = canonicalState(
            input.localizedTimer,
            input.localizedHistory,
            input.response.tasks,
            snapshot.knownTasks,
            input.clearLocal,
        )
        val projected = projectBootstrapPending(
            snapshot,
            input.response,
            reconciled,
            canonical,
            input.projectionNow,
        )
        val local = synchronizedLocal(
            base = reconciled.local,
            snapshot = snapshot,
            response = input.response,
            canonical = canonical,
            projected = projected,
            generated = EmptyGeneratedResolution,
            clearOwnedTimer = input.clearLocal,
            profile = input.profile,
        )
        return CentralizedSyncApplication(
            local,
            reconciled,
            canonical,
            projected,
            EmptyGeneratedResolution,
            conflictTransition(input.response, reconciled.queues),
        )
    }

    fun applyBootstrapResolution(
        input: CentralizedBootstrapResolutionInput,
    ): CentralizedSyncApplication {
        val snapshot = input.snapshot
        val keepRemote = input.request.strategy == BootstrapStrategy.KeepRemote
        val queues = resolutionQueues(snapshot.queues, keepRemote)
        val dependencies = if (keepRemote) emptyMap() else snapshot.dependencies
        val reconciled = reconcile(
            response = input.response,
            queues = queues,
            sent = input.request.toCoreSent(),
            dependencies = dependencies,
            sampledLocal = input.sampledLocal,
        )
        val generated = generatedResolution(reconciled.core, snapshot)
        val canonical = canonicalState(
            input.localizedTimer,
            input.localizedHistory,
            input.response.tasks,
            snapshot.knownTasks,
            keepRemote,
        )
        val projected = projectBootstrapResolution(
            snapshot,
            input.request,
            input.acknowledgementResponse,
            input.response,
            reconciled,
            canonical,
            input.projectionNow,
        )
        val local = synchronizedLocal(
            base = reconciled.local,
            snapshot = snapshot,
            response = input.response,
            canonical = canonical,
            projected = projected,
            generated = generated,
            clearOwnedTimer = keepRemote,
            profile = input.profile,
        )
        return CentralizedSyncApplication(
            local,
            reconciled,
            canonical,
            projected,
            generated,
            bootstrapResolutionConflict(input, reconciled, dependencies),
        )
    }

    private fun bootstrapResolutionConflict(
        input: CentralizedBootstrapResolutionInput,
        reconciled: CentralizedReconciledPending,
        dependencies: Map<String, String>,
    ) = conflictTransition(
        input.acknowledgementResponse,
        reconciled.queues,
        dependencies.values.toSet(),
    )

    fun eligibleCommands(snapshot: CentralizedSyncSnapshot): List<TimerCommand> =
        eligibleCommands(snapshot.queues, snapshot.dependencies)

    fun queuesEmpty(queues: PendingSyncQueues): Boolean =
        queues.commands.isEmpty() &&
            queues.taskOperations.isEmpty() &&
            queues.durationOperations.isEmpty() &&
            queues.autoStartOperations.isEmpty() &&
            queues.selectedTaskOperations.isEmpty()

    private fun reconcile(
        response: SyncResponse,
        queues: PendingSyncQueues,
        sent: CoreReconciliationSent,
        dependencies: Map<String, String>,
        sampledLocal: LocalStateEntity,
    ): CentralizedReconciledPending {
        val core = reconciliationDispatcher.rebase(
            local = queues.toCorePending(sampledLocal.deviceId),
            sent = sent,
            response = response,
            dependencies = timerDependencies(queues.commands, dependencies),
        )
        val reconciledQueues = core.pending.toPendingQueues()
        val reconciledDependencies = core.dependencies.associate {
            it.operationId to it.dependsOnOperationId
        }
        return CentralizedReconciledPending(
            local = localWithReconciledClock(sampledLocal, core.pending),
            queues = reconciledQueues,
            dependencies = reconciledDependencies,
            core = core,
        )
    }

    private fun reconcileSyncAttempt(
        input: CentralizedSyncApplicationInput,
    ): CentralizedReconciledPending {
        val snapshot = input.snapshot
        val response = input.response
        if (response.revision < snapshot.local.revision) {
            throw SyncProtocolException(
                "Sync revision regressed from ${snapshot.local.revision} to ${response.revision}",
            )
        }
        val sentIds = TimerSyncConstruction.sentIds(input.attempt.request)
        validateAcknowledgements(sentIds, response)
        val queues = TimerSyncConstruction.mergedQueues(snapshot.queues, input.attempt.request)
        return reconcile(
            response = response,
            queues = queues,
            sent = sentIds.toCoreSent(),
            dependencies = snapshot.dependencies,
            sampledLocal = input.sampledLocal,
        )
    }

    private fun reconcileBootstrapInstallation(
        input: CentralizedBootstrapInstallationInput,
    ): CentralizedReconciledPending {
        val snapshot = input.snapshot
        if (!input.clearLocal && input.response.revision < snapshot.local.revision) {
            throw SyncProtocolException(
                "Bootstrap revision regressed from ${snapshot.local.revision} to ${input.response.revision}",
            )
        }
        return reconcile(
            response = input.response,
            queues = if (input.clearLocal) emptyQueues() else snapshot.queues,
            sent = CoreReconciliationSent(),
            dependencies = if (input.clearLocal) emptyMap() else snapshot.dependencies,
            sampledLocal = input.sampledLocal,
        )
    }

    private fun projectSyncPending(
        snapshot: CentralizedSyncSnapshot,
        attempt: SyncAttempt,
        response: SyncResponse,
        reconciled: CentralizedReconciledPending,
        canonical: CentralizedCanonicalState,
        now: Instant,
    ): Pair<CentralizedReconciledPending, CentralizedProjectedState> {
        val reconciledSettings = snapshot.settings
            .withDurations(reconciled.core.projection.durationsMs)
            .copy(autoStartBreaks = reconciled.core.projection.autoStartBreaks)
        val provisional = projectCanonicalPending(
            snapshot.local.deviceId,
            canonical,
            response,
            reconciled.queues,
            now,
        )
        val phase = reconciledSelectedPhase(
            snapshot = snapshot,
            currentPhase = reconciledSettings.selectedPhase,
            selectedPhaseAtSend = attempt.selectedPhaseAtSend,
            selectedPhaseGenerationAtSend = attempt.selectedPhaseGenerationAtSend,
            sentCommands = attempt.request.commands,
            acknowledgementResponse = response,
            canonicalResponse = response,
            nextProjection = TimerProjection(provisional.canonicalTimer, provisional.history),
        )
        val queues = reconciled.queues
        val projection = provisional
        val settings = snapshot.settings.withDurations(projection.durationsMs).copy(
            autoStartBreaks = projection.autoStartBreaks,
            selectedPhase = phase,
        )
        return reconciled.copy(queues = queues) to
            CentralizedProjectedState(queues, settings, projection)
    }

    private fun projectBootstrapPending(
        snapshot: CentralizedSyncSnapshot,
        response: SyncResponse,
        reconciled: CentralizedReconciledPending,
        canonical: CentralizedCanonicalState,
        now: Instant,
    ): CentralizedProjectedState {
        val projection = projectCanonicalPending(
            snapshot.local.deviceId,
            canonical,
            response,
            reconciled.queues,
            now,
        )
        val settings = snapshot.settings.withDurations(projection.durationsMs).copy(
            autoStartBreaks = projection.autoStartBreaks,
        )
        return CentralizedProjectedState(reconciled.queues, settings, projection)
    }

    private fun projectBootstrapResolution(
        snapshot: CentralizedSyncSnapshot,
        request: BootstrapResolutionRequest,
        acknowledgementResponse: SyncResponse,
        response: SyncResponse,
        reconciled: CentralizedReconciledPending,
        canonical: CentralizedCanonicalState,
        now: Instant,
    ): CentralizedProjectedState {
        val projection = projectCanonicalPending(
            snapshot.local.deviceId,
            canonical,
            response,
            reconciled.queues,
            now,
        )
        val phase = reconciledSelectedPhase(
            snapshot = snapshot,
            currentPhase = snapshot.settings.selectedPhase,
            selectedPhaseAtSend = null,
            selectedPhaseGenerationAtSend = null,
            sentCommands = request.commands,
            acknowledgementResponse = acknowledgementResponse,
            canonicalResponse = response,
            nextProjection = TimerProjection(projection.canonicalTimer, projection.history),
        )
        val settings = snapshot.settings.withDurations(projection.durationsMs).copy(
            autoStartBreaks = projection.autoStartBreaks,
            selectedPhase = phase,
        )
        return CentralizedProjectedState(reconciled.queues, settings, projection)
    }

    private fun projectCanonicalPending(
        deviceId: String,
        canonical: CentralizedCanonicalState,
        response: SyncResponse,
        queues: PendingSyncQueues,
        now: Instant,
    ): CoreProjectionResult {
        val base = CoreProjectionBase(
            canonicalTimer = canonical.timer,
            history = canonical.history,
            tasks = canonical.tasks,
            durationsMs = response.durationsMs,
            autoStartBreaks = response.autoStartBreaks,
            selectedTaskId = response.selectedTaskId,
        )
        return projectionDispatcher.apply(
            base,
            queues.toCorePending(deviceId),
            now,
        )
    }

    private fun synchronizedLocal(
        base: LocalStateEntity,
        snapshot: CentralizedSyncSnapshot,
        response: SyncResponse,
        canonical: CentralizedCanonicalState,
        projected: CentralizedProjectedState,
        generated: GeneratedTimerResolution,
        clearOwnedTimer: Boolean,
        profile: User? = null,
    ): LocalStateEntity {
        val projection = TimerProjection(
            projected.projection.canonicalTimer,
            projected.projection.history,
        )
        return base.copy(
            revision = response.revision,
            canonicalTimerJson = canonical.timer?.let { json.encodeToString(it) },
            historyJson = json.encodeToString(canonical.history),
            tasksJson = json.encodeToString(canonical.tasks),
            knownTasksJson = json.encodeToString(canonical.knownTasks.values.sortedBy(FocusTask::id)),
            selectedTaskId = projected.projection.selectedTaskId,
            settingsJson = json.encodeToString(projected.settings),
            userJson = profile?.let { json.encodeToString(it) } ?: base.userJson,
            ownerUserId = profile?.id ?: base.ownerUserId,
            canonicalAutoStartBreaks = response.autoStartBreaks,
            ownedTimerId = when {
                clearOwnedTimer -> null
                profile != null && generated === EmptyGeneratedResolution -> snapshot.local.ownedTimerId
                    ?.takeIf {
                        projection.timer?.status in ActiveStatuses && projection.timer?.id == it
                    }
                else -> resolvedOwnedTimerId(snapshot.local.ownedTimerId, projection, generated)
            },
        )
    }

    private fun canonicalState(
        timer: CanonicalTimer?,
        history: List<HistoryItem>,
        tasks: List<FocusTask>,
        knownTasks: Map<String, FocusTask>,
        clearLocal: Boolean,
    ): CentralizedCanonicalState = CentralizedCanonicalState(
        timer = timer,
        history = history,
        tasks = tasks,
        knownTasks = if (clearLocal) {
            tasks.associateBy(FocusTask::id)
        } else {
            (knownTasks.values + tasks).associateBy(FocusTask::id)
        },
    )

    private fun generatedResolution(
        reconciliation: CoreReconciliationResult,
        snapshot: CentralizedSyncSnapshot,
    ): GeneratedTimerResolution {
        val promoted = reconciliation.pending.commands.asSequence()
            .map { it.value }
            .filter { it.id in reconciliation.promotedTimerOperationIds }
            .toList()
        val discarded = snapshot.queues.commands.filter {
            it.id in reconciliation.droppedTimerOperationIds
        }
        val pendingById = snapshot.queues.commands.associateBy(TimerCommand::id)
        val sourceTimerIds = discarded.mapNotNullTo(mutableSetOf()) { command ->
            snapshot.dependencies[command.id]?.let(pendingById::get)?.timerId
        }
        return GeneratedTimerResolution(promoted, discarded, sourceTimerIds)
    }

    private fun resolvedOwnedTimerId(
        currentOwnedTimerId: String?,
        nextProjection: TimerProjection,
        resolution: GeneratedTimerResolution,
    ): String? {
        val activeTimerId = nextProjection.timer
            ?.takeIf { it.status in ActiveStatuses }
            ?.id
            ?: return null
        return activeTimerId.takeIf {
            it == currentOwnedTimerId || it in resolution.discardedSourceTimerIds
        }
    }

    private fun reconciledSelectedPhase(
        snapshot: CentralizedSyncSnapshot,
        currentPhase: String,
        selectedPhaseAtSend: String?,
        selectedPhaseGenerationAtSend: Long?,
        sentCommands: List<TimerCommand>,
        acknowledgementResponse: SyncResponse,
        canonicalResponse: SyncResponse,
        nextProjection: TimerProjection,
    ): String {
        if (selectedPhaseAtSend != null && (
                currentPhase != selectedPhaseAtSend ||
                    selectedPhaseGenerationAtSend != snapshot.selectedPhaseGeneration
                )
        ) return currentPhase
        val acknowledgements = acknowledgementResponse.acknowledgements
            .associateBy(Acknowledgement::commandId)
        return sentCommands.asSequence()
            .filter { it.type == CommandType.Finish }
            .sortedWith(compareBy(TimerCommand::deviceSequence, TimerCommand::id))
            .fold(currentPhase) { phase, finish ->
                phaseAfterFinish(
                    snapshot,
                    phase,
                    finish,
                    acknowledgements,
                    acknowledgementResponse,
                    canonicalResponse,
                    nextProjection,
                )
            }
    }

    private fun phaseAfterFinish(
        snapshot: CentralizedSyncSnapshot,
        selectedPhase: String,
        finish: TimerCommand,
        acknowledgements: Map<String, Acknowledgement>,
        acknowledgementResponse: SyncResponse,
        canonicalResponse: SyncResponse,
        nextProjection: TimerProjection,
    ): String {
        val acknowledgement = acknowledgements[finish.id] ?: return selectedPhase
        val canonicallyCompleted = finishIsCanonicallyCompleted(acknowledgementResponse, finish)
        if (acknowledgement.outcome != "applied" && !canonicallyCompleted) {
            return canonicalResponse.canonicalTimer
                ?.takeIf { it.id == finish.timerId }
                ?.phase
                ?: finish.phase
        }
        return when {
            canonicallyCompleted -> completionDispatcher.finishApplied(
                CoreFinishAppliedInput(
                    commandId = finish.id,
                    timerId = finish.timerId,
                    phase = finish.phase,
                    occurredAt = finish.occurredAt,
                    history = completionHistory(canonicalResponse, finish),
                    autoStartBreaks = snapshot.settings.autoStartBreaks,
                    localDeviceId = snapshot.local.deviceId,
                    ownedTimerId = snapshot.local.ownedTimerId,
                    reference = completionReference(canonicalResponse, finish),
                    zoneId = zoneId,
                ),
            ).selectedPhase
            nextProjection.timer?.lastIntent?.commandId == finish.id -> nextProjection.timer.phase
            else -> selectedPhase
        }
    }

    private fun completionHistory(
        response: SyncResponse,
        finish: TimerCommand,
    ): List<HistoryItem> {
        if (response.history.any { it.timerId == finish.timerId && it.status == TimerStatus.Completed }) {
            return response.history
        }
        val timer = response.canonicalTimer?.takeIf {
            it.id == finish.timerId && it.status == TimerStatus.Completed
        } ?: return response.history
        return response.history + HistoryItem(
            id = "canonical-completion:${timer.id}",
            timerId = timer.id,
            commandId = finish.id,
            phase = timer.phase,
            status = timer.status,
            plannedDurationMs = timer.plannedDurationMs,
            completedAt = timer.anchorAt,
            endedAt = timer.anchorAt,
            taskId = timer.taskId,
        )
    }

    private fun finishIsCanonicallyCompleted(
        response: SyncResponse,
        finish: TimerCommand,
    ): Boolean = response.history.any {
        it.timerId == finish.timerId && it.status == TimerStatus.Completed
    } || response.canonicalTimer?.let {
        it.id == finish.timerId && it.status == TimerStatus.Completed
    } == true

    private fun completionReference(response: SyncResponse, source: TimerCommand): Instant {
        val sourceTimestamp = response.history.firstOrNull {
            it.timerId == source.timerId && it.status == TimerStatus.Completed &&
                (it.commandId == source.id || it.commandId == null)
        }?.let { it.completedAt ?: it.endedAt }
            ?: response.canonicalTimer?.takeIf {
                it.id == source.timerId && it.status == TimerStatus.Completed
            }?.anchorAt
            ?: source.physicalOccurredAt
            ?: source.occurredAt
        return runCatching { Instant.parse(sourceTimestamp) }
            .getOrElse { Instant.parse(source.occurredAt) }
    }

    private fun timerDependencies(
        commands: List<TimerCommand>,
        dependencies: Map<String, String>,
    ): List<CoreTimerDependency> {
        val commandsById = commands.associateBy(TimerCommand::id)
        return dependencies.entries.sortedBy { it.key }.map { (operationId, parentId) ->
            val operation = commandsById[operationId]
                ?: throw SyncProtocolException("Pending timer dependency child is missing")
            val parent = commandsById[parentId]
                ?: throw SyncProtocolException("Pending timer dependency parent is missing")
            val generatedBreak = operation.type == CommandType.Start &&
                operation.phase in setOf(TimerPhase.ShortBreak, TimerPhase.LongBreak) &&
                parent.type == CommandType.Finish && parent.phase == TimerPhase.Focus
            if (!generatedBreak) {
                CoreTimerDependency(operationId, parentId)
            } else {
                val source = Instant.parse(parent.physicalOccurredAt ?: parent.occurredAt)
                val localDate = source.atZone(zoneId).toLocalDate()
                val dayStart = localDate.atStartOfDay(zoneId).toInstant()
                val dayEnd = localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                CoreTimerDependency(
                    operationId = operationId,
                    dependsOnOperationId = parentId,
                    generatedBreak = true,
                    sourceDayStart = dayStart.toString(),
                    sourceDayEnd = dayEnd.toString(),
                )
            }
        }
    }

    private fun localWithReconciledClock(
        base: LocalStateEntity,
        pending: CoreProjectionPending,
    ): LocalStateEntity {
        val retainedClock = buildList {
            add(base.hlcWallMs to base.hlcCounter)
            pending.commands.forEach { add(it.value.hlcWallMs to it.value.hlcCounter) }
            pending.taskOperations.forEach { add(it.value.hlcWallMs to it.value.hlcCounter) }
            pending.durationOperations.forEach { add(it.value.hlcWallMs to it.value.hlcCounter) }
            pending.autoStartOperations.forEach { add(it.value.hlcWallMs to it.value.hlcCounter) }
            pending.selectedTaskOperations.forEach { add(it.value.hlcWallMs to it.value.hlcCounter) }
        }.maxWith(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))
        return base.copy(hlcWallMs = retainedClock.first, hlcCounter = retainedClock.second)
    }

    private fun validateAcknowledgements(sent: SentSyncIds, response: SyncResponse) {
        TimerSyncValidation.validateAcknowledgementSet(
            sent.commands,
            response.acknowledgements.map(Acknowledgement::commandId),
            "command",
        )
        TimerSyncValidation.validateAcknowledgementSet(
            sent.taskOperations,
            response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
            "task",
        )
        TimerSyncValidation.validateAcknowledgementSet(
            sent.durationOperations,
            response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
            "duration",
        )
        TimerSyncValidation.validateAcknowledgementSet(
            sent.autoStartOperations,
            response.autoStartAcknowledgements.map(AutoStartAcknowledgement::operationId),
            "auto-start",
        )
        TimerSyncValidation.validateAcknowledgementSet(
            sent.selectedTaskOperations,
            response.selectedTaskAcknowledgements.map(SelectedTaskAcknowledgement::operationId),
            "selected-task",
        )
    }

    private fun conflictTransition(
        response: SyncResponse,
        queues: PendingSyncQueues,
        internallyResolvedCommandIds: Set<String> = emptySet(),
    ): CentralizedConflictTransition {
        val outcome = syncOutcomeConflict(response, internallyResolvedCommandIds)
        return if (outcome != null || queuesEmpty(queues)) {
            CentralizedConflictTransition.Replace(outcome)
        } else {
            CentralizedConflictTransition.Keep
        }
    }

    private fun syncOutcomeConflict(
        response: SyncResponse,
        internallyResolvedCommandIds: Set<String>,
    ): String? {
        val outcomes = buildList {
            response.acknowledgements.filter {
                it.outcome != "applied" &&
                    (it.outcome != "ignored" || it.commandId !in internallyResolvedCommandIds)
            }
                .forEach { add(Triple("Command", it.outcome, it.reason)) }
            response.taskAcknowledgements.filter { it.outcome != "applied" }
                .forEach { add(Triple("Task", it.outcome, it.reason)) }
            response.durationAcknowledgements.filter { it.outcome != "applied" }
                .forEach { add(Triple("Duration", it.outcome, it.reason)) }
            response.autoStartAcknowledgements.filter { it.outcome != "applied" }
                .forEach { add(Triple("Auto-start", it.outcome, it.reason)) }
            response.selectedTaskAcknowledgements.filter { it.outcome != "applied" }
                .forEach { add(Triple("Selected task", it.outcome, it.reason)) }
        }
        return when (outcomes.size) {
            0 -> null
            1 -> outcomes.single().let { (kind, outcome, reason) ->
                reason.ifBlank { "$kind outcome: $outcome" }
            }
            else -> outcomes.joinToString("\n") { (kind, outcome, reason) ->
                "$kind: ${reason.ifBlank { outcome }}"
            }
        }
    }

    private fun eligibleCommands(
        queues: PendingSyncQueues,
        dependencies: Map<String, String>,
    ): List<TimerCommand> = queues.commands.filter { it.id !in dependencies }

    internal fun resolutionQueues(
        queues: PendingSyncQueues,
        keepRemote: Boolean,
    ): PendingSyncQueues = if (keepRemote) emptyQueues() else queues

    private fun emptyQueues() = PendingSyncQueues(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    private fun PendingSyncQueues.toCorePending(deviceId: String) = CoreProjectionPending(
        commands = commands.map { DeviceOperation(deviceId, it) },
        taskOperations = taskOperations.map { DeviceOperation(deviceId, it) },
        durationOperations = durationOperations.map { DeviceOperation(deviceId, it) },
        autoStartOperations = autoStartOperations.map { DeviceOperation(it.deviceId, it) },
        selectedTaskOperations = selectedTaskOperations.map { DeviceOperation(deviceId, it) },
    )

    private fun CoreProjectionPending.toPendingQueues() = PendingSyncQueues(
        commands.map { it.value },
        taskOperations.map { it.value },
        durationOperations.map { it.value },
        autoStartOperations.map { it.value },
        selectedTaskOperations.map { it.value },
    )

    private fun SentSyncIds.toCoreSent() = CoreReconciliationSent(
        commands.toList(),
        taskOperations.toList(),
        durationOperations.toList(),
        autoStartOperations.toList(),
        selectedTaskOperations.toList(),
    )

    private fun BootstrapResolutionRequest.toCoreSent() = CoreReconciliationSent(
        commands.map(TimerCommand::id),
        taskOperations.map(TaskOperation::id),
        durationOperations.map(DurationOperation::id),
        autoStartOperations.orEmpty().map(AutoStartOperation::id),
        selectedTaskOperations.orEmpty().map(SelectedTaskOperation::id),
    )

    private fun CentralizedSyncSnapshot.projectionBase() = CoreProjectionBase(
        canonicalTimer = canonicalTimer,
        history = canonicalHistory,
        tasks = canonicalTasks,
        durationsMs = settings.effectiveDurationsMs(),
        autoStartBreaks = canonicalAutoStartBreaks,
        selectedTaskId = local.selectedTaskId,
    )

    private companion object {
        val ActiveStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
        val EmptyGeneratedResolution = GeneratedTimerResolution(emptyList(), emptyList(), emptySet())
    }
}
