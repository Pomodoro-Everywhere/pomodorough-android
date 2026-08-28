package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.domain.TimerPresentation

internal data class TimerMutationReservation(
    val stamps: List<SyncWireBounds.MutationStamp>,
    val uuids: List<UUID>,
    val lastUuidV7: String,
)

internal data class TimerMutationState(
    val local: LocalStateEntity,
    val settings: TimerSettings,
    val projection: TimerProjection,
    val projectionBase: CoreProjectionBase,
    val queues: PendingSyncQueues,
    val dependencies: Map<String, String>,
    val knownTasks: Map<String, FocusTask>,
    val visibleTasks: List<FocusTask>,
    val selectedTaskId: String?,
)

internal sealed interface TimerMutationTransition<out T> {
    data object Ignored : TimerMutationTransition<Nothing>
    data class Planned<T>(val plan: T) : TimerMutationTransition<T>
}

internal data class TimerCommandMutationInput(
    val state: TimerMutationState,
    val type: String,
    val startingPhase: String?,
    val reservation: TimerMutationReservation,
    val physicalNowMs: Long,
)

internal data class TimerCancelMutationInput(
    val state: TimerMutationState,
    val current: CanonicalTimer,
    val types: List<String>,
    val reservation: TimerMutationReservation,
    val physicalNowMs: Long,
)

internal data class TimerFinishMutationInput(
    val state: TimerMutationState,
    val current: CanonicalTimer,
    val completionRequest: CoreCommandRequestDecision,
    val reservation: TimerMutationReservation,
    val physicalNowMs: Long,
)

internal data class DurationMutationInput(
    val state: TimerMutationState,
    val phase: String,
    val delta: Int,
    val reservation: TimerMutationReservation,
)

internal data class AutoStartMutationInput(
    val state: TimerMutationState,
    val enabled: Boolean,
    val reservation: TimerMutationReservation,
)

internal data class SelectedTaskMutationInput(
    val state: TimerMutationState,
    val taskId: String?,
    val reservation: TimerMutationReservation,
)

internal data class TaskMutationInput(
    val state: TimerMutationState,
    val type: String,
    val task: FocusTask,
    val select: Boolean,
    val reservation: TimerMutationReservation,
)

internal data class TimerCommandMutationPlan(
    val commands: List<TimerCommand>,
    val dependencies: Map<String, String>,
    val local: LocalStateEntity,
    val settings: TimerSettings,
    val projection: CoreProjectionResult,
)

internal data class DurationMutationPlan(
    val operation: DurationOperation,
    val local: LocalStateEntity,
    val settings: TimerSettings,
    val operations: List<DurationOperation>,
    val projection: CoreProjectionResult,
)

internal data class AutoStartMutationPlan(
    val operation: AutoStartOperation,
    val local: LocalStateEntity,
    val settings: TimerSettings,
    val operations: List<AutoStartOperation>,
    val projection: CoreProjectionResult,
)

internal data class SelectedTaskMutationPlan(
    val operation: SelectedTaskOperation,
    val local: LocalStateEntity,
    val operations: List<SelectedTaskOperation>,
    val projection: CoreProjectionResult,
)

internal data class TaskMutationPlan(
    val operation: TaskOperation,
    val selectedOperation: SelectedTaskOperation?,
    val local: LocalStateEntity,
    val knownTasks: Map<String, FocusTask>,
    val taskOperations: List<TaskOperation>,
    val selectedTaskOperations: List<SelectedTaskOperation>,
    val projection: CoreProjectionResult,
)

private data class TimerCommandTarget(
    val timerId: String,
    val phase: String,
    val durationMs: Long,
    val starting: Boolean,
)

private data class ReservedTaskMutation(
    val operation: TaskOperation,
    val selectedOperation: SelectedTaskOperation?,
    val taskOperations: List<TaskOperation>,
    val selectedTaskOperations: List<SelectedTaskOperation>,
    val finalStamp: SyncWireBounds.MutationStamp,
)

internal class TimerMutationCoordinator(
    private val json: Json,
    private val projectionDispatcher: CoreProjectionDispatcher,
    private val completionDispatcher: CoreCompletionDispatcher,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val timerId: () -> String = { UUID.randomUUID().toString() },
) {
    fun acceptsCommand(state: TimerMutationState, type: String): Boolean =
        validTransition(type, state.projection.timer)

    fun cancelAndClearTypes(current: CanonicalTimer): List<String> = when {
        validTransition(CommandType.Cancel, current) ->
            listOf(CommandType.Cancel, CommandType.Clear)
        validTransition(CommandType.Clear, current) -> listOf(CommandType.Clear)
        else -> emptyList()
    }

    fun acceptsDuration(state: TimerMutationState, phase: String, delta: Int): Boolean {
        return changedDurationMs(state.settings, phase, delta) != null
    }

    fun command(
        input: TimerCommandMutationInput,
    ): TimerMutationTransition<TimerCommandMutationPlan> {
        val state = input.state
        val target = commandTarget(input) ?: return TimerMutationTransition.Ignored
        val stamp = input.reservation.stamps.single()
        val command = reservedCommand(input, target, stamp)
        val dependency = if (target.starting) null else dependencyForTimer(state, command.timerId)
        val dependencies = dependency?.let { mapOf(command.id to it) }.orEmpty()
        val queues = state.queues.copy(commands = state.queues.commands + command)
        val projected = project(state, queues, Instant.parse(command.occurredAt)).requireApplied(command)
        val nextLocal = state.local.copy(
            deviceSequence = command.deviceSequence,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            ownedTimerId = target.timerId.takeIf { target.starting } ?: state.local.ownedTimerId,
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            TimerCommandMutationPlan(
                listOf(command),
                dependencies,
                nextLocal,
                state.settings,
                projected,
            ),
        )
    }

    fun cancel(
        input: TimerCancelMutationInput,
    ): TimerMutationTransition<TimerCommandMutationPlan> {
        if (input.types.isEmpty() || input.types.size != input.reservation.stamps.size ||
            input.types.size != input.reservation.uuids.size
        ) return TimerMutationTransition.Ignored
        val commands = cancelCommands(input)
        val dependency = dependencyForTimer(input.state, input.current.id)
        val dependencies = dependency?.let { source ->
            commands.associate { it.id to source }
        }.orEmpty()
        val queues = input.state.queues.copy(
            commands = input.state.queues.commands + commands,
        )
        val projected = project(
            input.state,
            queues,
            Instant.ofEpochMilli(input.physicalNowMs),
        ).also { result -> commands.forEach(result::requireApplied) }
        val nextSettings = input.state.settings.copy(
            selectedPhase = phaseAfterCancellation(input.state, input.current, projected),
        )
        val finalStamp = input.reservation.stamps.last()
        val nextLocal = input.state.local.copy(
            deviceSequence = requireNotNull(finalStamp.deviceSequence),
            hlcWallMs = finalStamp.wallMs,
            hlcCounter = finalStamp.counter,
            settingsJson = json.encodeToString(nextSettings),
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            TimerCommandMutationPlan(commands, dependencies, nextLocal, nextSettings, projected),
        )
    }

    fun finish(
        input: TimerFinishMutationInput,
    ): TimerMutationTransition<TimerCommandMutationPlan> {
        val physicalOccurredAt = Instant.ofEpochMilli(input.physicalNowMs).toString()
        val finish = finishCommand(input, physicalOccurredAt)
        val finishQueues = input.state.queues.copy(
            commands = input.state.queues.commands + finish,
        )
        val finishProjection = project(
            input.state,
            finishQueues,
            Instant.ofEpochMilli(input.physicalNowMs),
        ).requireApplied(finish)
        val completion = completionDecision(input, finish, finishProjection)
        if (completion.queueAutoBreak != input.completionRequest.reserveGeneratedBreak) {
            throw CoreProjectionException.InvalidOutput("Shared Core completion plans contradict")
        }
        val commands = completionCommands(input, finish, completion, physicalOccurredAt)
        val dependencies = completionDependencies(input.state, finish, commands)
        val projected = completionProjection(input, commands, finishProjection)
        val nextSettings = input.state.settings.copy(selectedPhase = completion.selectedPhase)
        val nextLocal = completedLocal(input, commands, nextSettings)
        return TimerMutationTransition.Planned(
            TimerCommandMutationPlan(commands, dependencies, nextLocal, nextSettings, projected),
        )
    }

    private fun completionCommands(
        input: TimerFinishMutationInput,
        finish: TimerCommand,
        completion: CoreFinishAppliedDecision,
        physicalOccurredAt: String,
    ): List<TimerCommand> = buildList {
        add(finish)
        if (completion.queueAutoBreak) {
            add(generatedBreakCommand(input, completion.selectedPhase, physicalOccurredAt))
        }
    }

    private fun completionDependencies(
        state: TimerMutationState,
        finish: TimerCommand,
        commands: List<TimerCommand>,
    ): Map<String, String> = buildMap {
        dependencyForTimer(state, finish.timerId)?.let { put(finish.id, it) }
        commands.lastOrNull()?.takeIf { it.type == CommandType.Start }?.let {
            put(it.id, finish.id)
        }
    }

    private fun completionProjection(
        input: TimerFinishMutationInput,
        commands: List<TimerCommand>,
        finishProjection: CoreProjectionResult,
    ): CoreProjectionResult {
        if (commands.size == 1) return finishProjection
        return project(
            input.state,
            input.state.queues.copy(commands = input.state.queues.commands + commands),
            Instant.ofEpochMilli(input.physicalNowMs),
        ).also { result -> commands.forEach(result::requireApplied) }
    }

    private fun completedLocal(
        input: TimerFinishMutationInput,
        commands: List<TimerCommand>,
        settings: TimerSettings,
    ): LocalStateEntity {
        val finalStamp = input.reservation.stamps.last()
        val provisionalBreak = commands.lastOrNull()?.takeIf { it.type == CommandType.Start }
        return input.state.local.copy(
            deviceSequence = commands.last().deviceSequence,
            hlcWallMs = finalStamp.wallMs,
            hlcCounter = finalStamp.counter,
            ownedTimerId = provisionalBreak?.timerId ?: input.state.local.ownedTimerId,
            settingsJson = json.encodeToString(settings),
            lastUuidV7 = input.reservation.lastUuidV7,
        )
    }

    fun duration(
        input: DurationMutationInput,
    ): TimerMutationTransition<DurationMutationPlan> {
        val nextDurationMs = changedDurationMs(input.state.settings, input.phase, input.delta)
            ?: return TimerMutationTransition.Ignored
        val stamp = input.reservation.stamps.single()
        val operation = DurationOperation(
            id = "duration-operation-${input.reservation.uuids.single()}",
            phase = input.phase,
            durationMs = nextDurationMs,
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
        )
        val operations = input.state.queues.durationOperations
            .filterNot { it.phase == input.phase } + operation
        val projected = project(
            input.state,
            input.state.queues.copy(durationOperations = operations),
            Instant.parse(operation.occurredAt),
        ).requireWinner(operation)
        val nextSettings = input.state.settings.withDurations(projected.durationsMs).copy(
            autoStartBreaks = projected.autoStartBreaks,
        )
        val nextLocal = input.state.local.copy(
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            settingsJson = json.encodeToString(nextSettings),
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            DurationMutationPlan(operation, nextLocal, nextSettings, operations, projected),
        )
    }

    fun autoStart(
        input: AutoStartMutationInput,
    ): TimerMutationTransition<AutoStartMutationPlan> {
        if (input.state.settings.autoStartBreaks == input.enabled) {
            return TimerMutationTransition.Ignored
        }
        val stamp = input.reservation.stamps.single()
        val operation = AutoStartOperation(
            id = input.reservation.uuids.single().toString(),
            deviceId = input.state.local.deviceId,
            enabled = input.enabled,
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
        )
        val operations = input.state.queues.autoStartOperations + operation
        val projected = project(
            input.state,
            input.state.queues.copy(autoStartOperations = operations),
            Instant.parse(operation.occurredAt),
        ).requireWinner(operation)
        val nextSettings = input.state.settings.withDurations(projected.durationsMs).copy(
            autoStartBreaks = projected.autoStartBreaks,
        )
        val nextLocal = input.state.local.copy(
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            settingsJson = json.encodeToString(nextSettings),
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            AutoStartMutationPlan(operation, nextLocal, nextSettings, operations, projected),
        )
    }

    fun selectedTask(
        input: SelectedTaskMutationInput,
    ): TimerMutationTransition<SelectedTaskMutationPlan> {
        if (input.taskId != null && input.state.visibleTasks.none { it.id == input.taskId }) {
            return TimerMutationTransition.Ignored
        }
        if (input.taskId == input.state.selectedTaskId) {
            return TimerMutationTransition.Ignored
        }
        val stamp = input.reservation.stamps.single()
        val operation = SelectedTaskOperation(
            id = input.reservation.uuids.single().toString(),
            taskId = input.taskId,
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
        )
        val operations = input.state.queues.selectedTaskOperations + operation
        val projected = project(
            input.state,
            input.state.queues.copy(selectedTaskOperations = operations),
            Instant.parse(operation.occurredAt),
        ).requireWinner(operation)
        val nextLocal = input.state.local.copy(
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            selectedTaskId = projected.selectedTaskId,
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            SelectedTaskMutationPlan(operation, nextLocal, operations, projected),
        )
    }

    fun task(
        input: TaskMutationInput,
    ): TimerMutationTransition<TaskMutationPlan> {
        val state = input.state
        val reserved = reservedTaskMutation(input) ?: return TimerMutationTransition.Ignored
        val projected = project(
            state,
            state.queues.copy(
                taskOperations = reserved.taskOperations,
                selectedTaskOperations = reserved.selectedTaskOperations,
            ),
            Instant.parse(reserved.finalStamp.occurredAt),
        ).requireWinner(reserved.operation).also { result ->
            reserved.selectedOperation?.let(result::requireWinner)
        }
        val nextKnownTasks = state.knownTasks + (input.task.id to input.task)
        val nextLocal = state.local.copy(
            hlcWallMs = reserved.finalStamp.wallMs,
            hlcCounter = reserved.finalStamp.counter,
            selectedTaskId = projected.selectedTaskId,
            knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            lastUuidV7 = input.reservation.lastUuidV7,
        )
        return TimerMutationTransition.Planned(
            TaskMutationPlan(
                reserved.operation,
                reserved.selectedOperation,
                nextLocal,
                nextKnownTasks,
                reserved.taskOperations,
                reserved.selectedTaskOperations,
                projected,
            ),
        )
    }

    private fun commandTarget(input: TimerCommandMutationInput): TimerCommandTarget? {
        val current = input.state.projection.timer
        val starting = input.type == CommandType.Start
        val targetTimerId = if (starting) timerId() else current?.id
        if (targetTimerId == null || !validTransition(input.type, current)) return null
        val phase = if (starting) {
            input.startingPhase ?: input.state.settings.selectedPhase
        } else {
            current?.phase ?: return null
        }
        val durationMs = if (starting) {
            input.state.settings.durationMsFor(phase)
        } else {
            current?.plannedDurationMs ?: return null
        }
        return TimerCommandTarget(targetTimerId, phase, durationMs, starting)
    }

    private fun reservedCommand(
        input: TimerCommandMutationInput,
        target: TimerCommandTarget,
        stamp: SyncWireBounds.MutationStamp,
    ) = TimerCommand(
        id = input.reservation.uuids.single().toString(),
        deviceSequence = requireNotNull(stamp.deviceSequence),
        timerId = target.timerId,
        type = input.type,
        phase = target.phase,
        plannedDurationMs = target.durationMs,
        occurredAt = stamp.occurredAt,
        hlcWallMs = stamp.wallMs,
        hlcCounter = stamp.counter,
        observedElapsedMs = if (target.starting) {
            0
        } else {
            TimerPresentation.elapsedAt(input.state.projection.timer, input.physicalNowMs)
        },
        taskId = selectedTaskForStart(input.state, target.starting, target.phase),
        physicalOccurredAt = Instant.ofEpochMilli(input.physicalNowMs).toString(),
    )

    private fun reservedTaskMutation(input: TaskMutationInput): ReservedTaskMutation? {
        val state = input.state
        val clearsSelection = input.type == TaskOperationType.Delete &&
            state.selectedTaskId == input.task.id
        val changesSelection = input.select || clearsSelection
        val reservationSize = if (changesSelection) 2 else 1
        if (input.reservation.stamps.size != reservationSize ||
            input.reservation.uuids.size != reservationSize
        ) return null
        val stamp = input.reservation.stamps.first()
        val operation = TaskOperation(
            id = "task-operation-${input.reservation.uuids.first()}",
            taskId = input.task.id,
            type = input.type,
            title = input.task.title.takeIf { input.type == TaskOperationType.Upsert },
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
        )
        val selectedOperation = if (changesSelection) {
            val selectedStamp = input.reservation.stamps.last()
            SelectedTaskOperation(
                id = input.reservation.uuids.last().toString(),
                taskId = input.task.id.takeIf { input.select },
                occurredAt = selectedStamp.occurredAt,
                hlcWallMs = selectedStamp.wallMs,
                hlcCounter = selectedStamp.counter,
            )
        } else {
            null
        }
        return ReservedTaskMutation(
            operation = operation,
            selectedOperation = selectedOperation,
            taskOperations = state.queues.taskOperations + operation,
            selectedTaskOperations = selectedOperation?.let {
                state.queues.selectedTaskOperations + it
            } ?: state.queues.selectedTaskOperations,
            finalStamp = input.reservation.stamps.last(),
        )
    }

    private fun cancelCommands(input: TimerCancelMutationInput): List<TimerCommand> {
        val elapsedMs = TimerPresentation.elapsedAt(input.current, input.physicalNowMs)
        val physicalOccurredAt = Instant.ofEpochMilli(input.physicalNowMs).toString()
        return input.types.mapIndexed { index, type ->
            val stamp = input.reservation.stamps[index]
            TimerCommand(
                id = input.reservation.uuids[index].toString(),
                deviceSequence = requireNotNull(stamp.deviceSequence),
                timerId = input.current.id,
                type = type,
                phase = input.current.phase,
                plannedDurationMs = input.current.plannedDurationMs,
                occurredAt = stamp.occurredAt,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                observedElapsedMs = elapsedMs,
                physicalOccurredAt = physicalOccurredAt,
            )
        }
    }

    private fun phaseAfterCancellation(
        state: TimerMutationState,
        current: CanonicalTimer,
        projected: CoreProjectionResult,
    ): String {
        val completionApplied = projected.history.any {
            it.timerId == current.id && it.status == TimerStatus.Completed
        }
        if (!completionApplied) return state.settings.selectedPhase
        val source = projected.history.firstOrNull {
            it.timerId == current.id && it.status == TimerStatus.Completed
        }
        val occurredAt = source?.completedAt ?: source?.endedAt ?: current.anchorAt
        return completionDispatcher.finishApplied(
            CoreFinishAppliedInput(
                commandId = source?.commandId ?: "cancelled-completion-${current.id}",
                timerId = current.id,
                phase = current.phase,
                occurredAt = occurredAt,
                history = projected.history,
                autoStartBreaks = false,
                localDeviceId = state.local.deviceId,
                ownedTimerId = state.local.ownedTimerId,
                reference = Instant.parse(occurredAt),
                zoneId = zoneId,
            ),
        ).selectedPhase
    }

    private fun finishCommand(
        input: TimerFinishMutationInput,
        physicalOccurredAt: String,
    ): TimerCommand {
        val stamp = input.reservation.stamps.first()
        return TimerCommand(
            id = input.reservation.uuids.first().toString(),
            deviceSequence = requireNotNull(stamp.deviceSequence),
            timerId = input.current.id,
            type = CommandType.Finish,
            phase = input.current.phase,
            plannedDurationMs = input.current.plannedDurationMs,
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            observedElapsedMs = TimerPresentation.elapsedAt(input.current, input.physicalNowMs),
            physicalOccurredAt = physicalOccurredAt,
        )
    }

    private fun generatedBreakCommand(
        input: TimerFinishMutationInput,
        phase: String,
        physicalOccurredAt: String,
    ): TimerCommand {
        val stamp = input.reservation.stamps.last()
        return TimerCommand(
            id = input.reservation.uuids.last().toString(),
            deviceSequence = requireNotNull(stamp.deviceSequence),
            timerId = timerId(),
            type = CommandType.Start,
            phase = phase,
            plannedDurationMs = input.state.settings.durationMsFor(phase),
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            observedElapsedMs = 0,
            physicalOccurredAt = physicalOccurredAt,
        )
    }

    private fun completionDecision(
        input: TimerFinishMutationInput,
        finish: TimerCommand,
        projection: CoreProjectionResult,
    ): CoreFinishAppliedDecision = completionDispatcher.finishApplied(
        CoreFinishAppliedInput(
            commandId = finish.id,
            timerId = finish.timerId,
            phase = finish.phase,
            occurredAt = finish.occurredAt,
            history = projection.history,
            autoStartBreaks = input.state.settings.autoStartBreaks,
            localDeviceId = input.state.local.deviceId,
            ownedTimerId = input.state.local.ownedTimerId,
            reference = Instant.ofEpochMilli(input.physicalNowMs),
            zoneId = zoneId,
        ),
    )

    private fun changedDurationMs(settings: TimerSettings, phase: String, delta: Int): Long? {
        val currentDurationMs = settings.durationMsFor(phase)
        val nextDurationMs = (
            currentDurationMs / DurationLimits.MinuteMs + delta.toLong()
        ).coerceIn(1L, DurationLimits.MaxMs / DurationLimits.MinuteMs) * DurationLimits.MinuteMs
        return nextDurationMs.takeIf { it != currentDurationMs }
    }

    private fun selectedTaskForStart(
        state: TimerMutationState,
        starting: Boolean,
        phase: String,
    ): String? = if (starting && phase == TimerPhase.Focus) {
        state.local.selectedTaskId?.takeIf { selected ->
            state.visibleTasks.any { it.id == selected }
        }
    } else {
        null
    }

    private fun dependencyForTimer(state: TimerMutationState, timerId: String): String? =
        state.queues.commands.firstNotNullOfOrNull { command ->
            state.dependencies[command.id]?.takeIf { command.timerId == timerId }
        }

    private fun validTransition(type: String, timer: CanonicalTimer?): Boolean = when (type) {
        CommandType.Start -> true
        CommandType.Pause -> timer?.status == TimerStatus.Running
        CommandType.Resume -> timer?.status == TimerStatus.Paused || timer?.status == TimerStatus.Superseded
        CommandType.Finish, CommandType.Cancel -> timer?.status in ActiveStatuses
        CommandType.Clear -> timer?.status in setOf(TimerStatus.Completed, TimerStatus.Cancelled)
        else -> false
    }

    private fun project(
        state: TimerMutationState,
        queues: PendingSyncQueues,
        now: Instant,
    ): CoreProjectionResult = projectionDispatcher.apply(
        base = state.projectionBase,
        pending = CoreProjectionPending(
            commands = queues.commands.map { DeviceOperation(state.local.deviceId, it) },
            taskOperations = queues.taskOperations.map { DeviceOperation(state.local.deviceId, it) },
            durationOperations = queues.durationOperations.map { DeviceOperation(state.local.deviceId, it) },
            autoStartOperations = queues.autoStartOperations.map { DeviceOperation(it.deviceId, it) },
            selectedTaskOperations = queues.selectedTaskOperations.map {
                DeviceOperation(state.local.deviceId, it)
            },
        ),
        now = now,
    )

    private companion object {
        val ActiveStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
    }
}
