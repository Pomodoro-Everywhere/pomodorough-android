package me.egigoka.pomodorough.data

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import me.egigoka.pomodorough.data.local.LocalStateEntity

internal class SyncProtocolException(message: String) : Exception(message)
internal class ProfileProtocolException(message: String) : Exception(message)

internal object TimerSyncValidation {
    fun validateUser(value: User) {
        if (value.id.isBlank() || value.id != value.id.trim() || value.id.utf8Size() > 512 ||
            value.id.any(Char::isISOControl)
        ) throw ProfileProtocolException("Account profile ID is invalid")
        val at = value.email.indexOf('@')
        if (value.email != value.email.trim() || value.email.utf8Size() > 320 ||
            at <= 0 || at != value.email.lastIndexOf('@') || at == value.email.lastIndex ||
            value.email.any { it.isWhitespace() || it.isISOControl() }
        ) throw ProfileProtocolException("Account profile email is invalid")
        if (value.name.utf8Size() > 512 || value.name.any(Char::isISOControl)) {
            throw ProfileProtocolException("Account profile name is invalid")
        }
        if (value.avatarUrl.isNotBlank()) validateAvatar(value.avatarUrl)
    }

    fun validateResolutionEnvelope(request: BootstrapResolutionRequest, deviceId: String) {
        require(request.requestId.isNotBlank()) { "Saved bootstrap request ID is invalid" }
        require(request.deviceId == deviceId) { "Saved bootstrap device does not match this device" }
        require(request.expectedRevision in 0..SyncWireBounds.MaxSafeInteger) {
            "Saved bootstrap revision is invalid"
        }
        validateResolutionCollectionSizes(request)
        requireUniqueResolutionOperations(request)
        request.commands.forEach(::validateTimerCommand)
        request.taskOperations.forEach(::validateTaskOperation)
        request.durationOperations.forEach(::validateDurationOperation)
        request.autoStartOperations?.forEach { validateAutoStartOperation(it, deviceId) }
        request.selectedTaskOperations?.forEach(::validateSelectedTaskOperation)
    }

    fun validatePendingQueues(queues: PendingSyncQueues, deviceId: String) {
        validateQueued("timer command", queues.commands, ::validateTimerCommand)
        validateQueued("task operation", queues.taskOperations, ::validateTaskOperation)
        validateQueued("duration operation", queues.durationOperations, ::validateDurationOperation)
        validateQueued("auto-start operation", queues.autoStartOperations) {
            validateAutoStartOperation(it, deviceId)
        }
        validateQueued(
            "selected-task operation",
            queues.selectedTaskOperations,
            ::validateSelectedTaskOperation,
        )
    }

    fun validateCanonicalResponse(
        response: SyncResponse,
        source: String,
        requireEmptyAcknowledgements: Boolean = false,
    ) {
        validateCanonicalEnvelope(response, source, requireEmptyAcknowledgements)
        response.canonicalTimer?.let { validateCanonicalTimer(it, source) }
        validateCanonicalHistory(response.history, source)
        validateCanonicalTasks(response, source)
        validateAcknowledgements(response, source)
    }

    fun validateAcknowledgementSet(
        sentIds: Set<String>,
        acknowledgedIds: List<String>,
        kind: String,
    ) {
        if (acknowledgedIds.size != sentIds.size || acknowledgedIds.toSet() != sentIds) {
            throw SyncProtocolException("Sync returned an invalid $kind acknowledgement set")
        }
    }

    fun validatePersistedMutationRanges(local: LocalStateEntity, queues: PendingSyncQueues) {
        SyncWireBounds.requirePersistedState(local.deviceSequence, local.hlcWallMs, local.hlcCounter)
        validateStoredClockSample(local)
        require(local.revision in 0..SyncWireBounds.MaxSafeInteger) {
            "Persisted revision is invalid"
        }
        validatePersistedOperationRanges(queues)
        validatePersistedQueueUniqueness(queues)
    }

    private fun validateAvatar(value: String) {
        val avatar = runCatching { URI(value) }.getOrNull()
        if (value.utf8Size() > 2_048 || avatar?.scheme != "https" || avatar.host.isNullOrBlank()) {
            throw ProfileProtocolException("Account profile avatar URL is invalid")
        }
    }

    internal fun validateResolutionCollectionSizes(request: BootstrapResolutionRequest) {
        require(request.commands.size <= MaxBootstrapOperations) {
            "Saved bootstrap commands exceed the 4096 item limit"
        }
        require(request.taskOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap task operations exceed the 4096 item limit"
        }
        require(request.durationOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap duration operations exceed the 4096 item limit"
        }
        require(request.autoStartOperations == null ||
            request.autoStartOperations.size <= MaxBootstrapOperations
        ) { "Saved bootstrap auto-start operations exceed the 4096 item limit" }
        require(request.selectedTaskOperations == null ||
            request.selectedTaskOperations.size <= MaxBootstrapOperations
        ) { "Saved bootstrap selected-task operations exceed the 4096 item limit" }
    }

    private fun requireUniqueResolutionOperations(request: BootstrapResolutionRequest) {
        require(request.commands.map(TimerCommand::id).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate IDs"
        }
        require(request.commands.map(TimerCommand::deviceSequence).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate device sequences"
        }
        require(request.taskOperations.map(TaskOperation::id).toSet().size == request.taskOperations.size) {
            "Saved bootstrap task operations contain duplicate IDs"
        }
        require(request.durationOperations.map(DurationOperation::id).toSet().size ==
            request.durationOperations.size
        ) { "Saved bootstrap duration operations contain duplicate IDs" }
        require(request.autoStartOperations == null ||
            request.autoStartOperations.map(AutoStartOperation::id).toSet().size ==
            request.autoStartOperations.size
        ) { "Saved bootstrap auto-start operations contain duplicate IDs" }
        require(request.selectedTaskOperations == null ||
            request.selectedTaskOperations.map(SelectedTaskOperation::id).toSet().size ==
            request.selectedTaskOperations.size
        ) { "Saved bootstrap selected-task operations contain duplicate IDs" }
    }

    private fun validateTimerCommand(command: TimerCommand) {
        require(command.id.isNotBlank() && command.timerId.isNotBlank()) {
            "Saved timer command identity is invalid"
        }
        require(command.deviceSequence in 1..SyncWireBounds.MaxSafeInteger) {
            "Saved timer command sequence is invalid"
        }
        require(command.type in commandTypes) { "Saved timer command type is invalid" }
        require(command.phase in TimerPhase.all) { "Saved timer command phase is invalid" }
        require(command.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs) {
            "Saved timer command duration is invalid"
        }
        requireOperationClock(command.occurredAt, command.hlcWallMs, command.hlcCounter, false)
        require(command.taskId == null || command.taskId.isNotBlank()) {
            "Saved timer command task is invalid"
        }
        require(command.observedElapsedMs in 0..command.plannedDurationMs) {
            "Saved timer command elapsed time is invalid"
        }
        require(command.type != CommandType.Start || command.observedElapsedMs == 0L) {
            "Saved start command elapsed time is invalid"
        }
        require(command.taskId == null || isUuid(command.taskId) &&
            command.type == CommandType.Start && command.phase == TimerPhase.Focus
        ) { "Saved timer command task is invalid" }
    }

    private fun validateTaskOperation(operation: TaskOperation) {
        require(operation.id.isNotBlank() && operation.taskId.isNotBlank()) {
            "Saved task operation identity is invalid"
        }
        require(isUuid(operation.taskId)) { "Saved task operation task ID is invalid" }
        require(operation.type in setOf(TaskOperationType.Upsert, TaskOperationType.Delete)) {
            "Saved task operation type is invalid"
        }
        requireOperationClock(operation.occurredAt, operation.hlcWallMs, operation.hlcCounter, false)
        when (operation.type) {
            TaskOperationType.Upsert -> require(operation.title != null) {
                "Saved task upsert title is missing"
            }
            TaskOperationType.Delete -> require(operation.title == null) {
                "Saved task delete must not contain a title"
            }
        }
    }

    private fun validateDurationOperation(operation: DurationOperation) {
        val valid = operation.phase in TimerPhase.all && DurationLimits.isValid(operation.durationMs) &&
            SyncWireBounds.isClockTuple(operation.hlcWallMs, operation.hlcCounter, true)
        require(operation.id.isNotBlank() && valid) { "Saved duration operation is invalid" }
        requireOperationClock(operation.occurredAt, operation.hlcWallMs, operation.hlcCounter, true)
    }

    private fun validateAutoStartOperation(operation: AutoStartOperation, deviceId: String) {
        val valid = SyncWireBounds.isIdentifier(operation.id) &&
            SyncWireBounds.isIdentifier(operation.deviceId) && operation.deviceId == deviceId &&
            parseInstant(operation.occurredAt) &&
            SyncWireBounds.isClockTuple(operation.hlcWallMs, operation.hlcCounter, true)
        require(valid) { "Saved auto-start operation is invalid" }
        requireOperationClock(operation.occurredAt, operation.hlcWallMs, operation.hlcCounter, true)
    }

    private fun validateSelectedTaskOperation(operation: SelectedTaskOperation) {
        require(operation.id.isNotBlank() && (operation.taskId == null || isUuid(operation.taskId))) {
            "Saved selected-task operation is invalid"
        }
        requireOperationClock(operation.occurredAt, operation.hlcWallMs, operation.hlcCounter, false)
    }

    private fun validateCanonicalEnvelope(
        response: SyncResponse,
        source: String,
        requireEmptyAcknowledgements: Boolean,
    ) {
        protocolRequire(response.revision in 0..SyncWireBounds.MaxSafeInteger,
            "$source returned an invalid revision")
        if (!response.durationsMs.isValid()) {
            throw SyncProtocolException("$source returned invalid canonical durations")
        }
        if (requireEmptyAcknowledgements && response.hasAcknowledgements()) {
            throw SyncProtocolException("$source returned acknowledgements for a read-only request")
        }
        protocolRequire(parseInstant(response.serverTime),
            "$source returned an invalid server timestamp")
        protocolRequire(SyncWireBounds.isClockTuple(
            response.serverHlcWallMs,
            response.serverHlcCounter,
            allowLegacySentinel = false,
        ), "$source returned an invalid server clock")
    }

    private fun validateCanonicalTimer(timer: CanonicalTimer, source: String) {
        protocolRequire(timer.id.isNotBlank(), "$source returned an invalid timer identity")
        protocolRequire(timer.phase in TimerPhase.all, "$source returned an invalid timer phase")
        protocolRequire(timer.status in timerStatuses, "$source returned an invalid timer status")
        protocolRequire(timer.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
            "$source returned an invalid timer duration")
        protocolRequire(timer.elapsedAtAnchorMs in 0..timer.plannedDurationMs,
            "$source returned invalid timer elapsed time")
        protocolRequire(parseInstant(timer.anchorAt), "$source returned an invalid timer timestamp")
        timer.taskId?.let { validateCanonicalTaskId(it, source) }
        timer.lastIntent?.let { intent ->
            protocolRequire(intent.type in commandTypes && intent.commandId.isNotBlank(),
                "$source returned an invalid timer intent")
            protocolRequire(parseInstant(intent.occurredAt),
                "$source returned an invalid timer intent timestamp")
        }
    }

    private fun validateCanonicalHistory(history: List<HistoryItem>, source: String) {
        protocolRequire(history.map(HistoryItem::id).toSet().size == history.size,
            "$source returned duplicate history identities")
        protocolRequire(history.map(HistoryItem::timerId).toSet().size == history.size,
            "$source returned duplicate history timer identities")
        val commandIds = history.mapNotNull(HistoryItem::commandId)
        protocolRequire(commandIds.toSet().size == commandIds.size,
            "$source returned duplicate history command identities")
        history.forEach { validateHistoryItem(it, source) }
    }

    private fun validateHistoryItem(item: HistoryItem, source: String) {
        protocolRequire(item.id.isNotBlank() && item.timerId.isNotBlank() &&
            (item.commandId == null || item.commandId.isNotBlank()),
            "$source returned an invalid history identity")
        protocolRequire(item.phase in TimerPhase.all, "$source returned an invalid history phase")
        protocolRequire(item.status in historyStatuses,
            "$source returned an invalid history status")
        protocolRequire(item.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
            "$source returned an invalid history duration")
        protocolRequire(!item.pending, "$source returned pending canonical history")
        if (item.status == TimerStatus.Completed) validateCompletedHistoryTime(item, source)
        else protocolRequire(item.endedAt != null && parseInstant(item.endedAt),
            "$source returned an invalid history end timestamp")
        if (item.status != TimerStatus.Completed && item.completedAt != null) {
            protocolRequire(parseInstant(item.completedAt),
                "$source returned an invalid history completion timestamp")
        }
        item.taskId?.let { validateCanonicalTaskId(it, source) }
    }

    private fun validateCompletedHistoryTime(item: HistoryItem, source: String) {
        protocolRequire(item.completedAt != null && parseInstant(item.completedAt),
            "$source returned an invalid history completion timestamp")
        if (item.endedAt != null) {
            protocolRequire(parseInstant(item.endedAt),
                "$source returned an invalid history end timestamp")
        }
    }

    private fun validateCanonicalTasks(response: SyncResponse, source: String) {
        protocolRequire(response.tasks.map(FocusTask::id).toSet().size == response.tasks.size,
            "$source returned duplicate task identities")
        response.tasks.forEach { task ->
            validateCanonicalTaskId(task.id, source)
        }
        response.selectedTaskId?.let { validateCanonicalTaskId(it, source) }
    }

    private fun validateAcknowledgements(response: SyncResponse, source: String) {
        response.acknowledgements.forEach {
            validateAcknowledgement(it.commandId, it.outcome, source, "command")
        }
        response.taskAcknowledgements.forEach {
            validateAcknowledgement(it.operationId, it.outcome, source, "task")
        }
        response.durationAcknowledgements.forEach {
            validateAcknowledgement(it.operationId, it.outcome, source, "duration")
        }
        response.autoStartAcknowledgements.forEach {
            validateAcknowledgement(it.operationId, it.outcome, source, "auto-start")
        }
        response.selectedTaskAcknowledgements.forEach {
            validateAcknowledgement(it.operationId, it.outcome, source, "selected-task")
        }
    }

    private fun validatePersistedOperationRanges(queues: PendingSyncQueues) {
        queues.commands.forEach { command ->
            require(command.deviceSequence in 1..SyncWireBounds.MaxSafeInteger)
            requireOperationClock(command.occurredAt, command.hlcWallMs, command.hlcCounter, false)
            command.physicalOccurredAt?.let {
                require(supportedPhysicalOccurrence(it)) {
                    "Persisted timer command physical occurrence is invalid"
                }
            }
        }
        queues.taskOperations.forEach {
            requireOperationClock(it.occurredAt, it.hlcWallMs, it.hlcCounter, false)
        }
        queues.durationOperations.forEach {
            requireOperationClock(it.occurredAt, it.hlcWallMs, it.hlcCounter, true)
        }
        queues.autoStartOperations.forEach {
            requireOperationClock(it.occurredAt, it.hlcWallMs, it.hlcCounter, true)
        }
        queues.selectedTaskOperations.forEach(::validateSelectedTaskOperation)
    }

    private fun validatePersistedQueueUniqueness(queues: PendingSyncQueues) {
        requireUnique(queues.commands.map(TimerCommand::id), "Persisted timer commands contain duplicate IDs")
        requireUnique(queues.commands.map(TimerCommand::deviceSequence),
            "Persisted timer commands contain duplicate sequences")
        requireUnique(queues.taskOperations.map(TaskOperation::id),
            "Persisted task operations contain duplicate IDs")
        requireUnique(queues.durationOperations.map(DurationOperation::id),
            "Persisted duration operations contain duplicate IDs")
        requireUnique(queues.autoStartOperations.map(AutoStartOperation::id),
            "Persisted auto-start operations contain duplicate IDs")
        requireUnique(queues.selectedTaskOperations.map(SelectedTaskOperation::id),
            "Persisted selected-task operations contain duplicate IDs")
    }

    private fun validateStoredClockSample(local: LocalStateEntity) {
        require(
            (local.serverClockOffsetMs == null) == (local.serverClockUncertaintyMs == null),
        ) { "Persisted server clock offset is incomplete" }
        local.serverClockOffsetMs?.let {
            require(it in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock offset is invalid"
            }
        }
        validateStoredClockAnchor(local)
    }

    private fun validateStoredClockAnchor(local: LocalStateEntity) {
        local.serverClockUncertaintyMs?.let {
            require(it in 0..MaxServerClockUncertaintyMs) {
                "Persisted server clock uncertainty is invalid"
            }
        }
        require((local.serverClockSamplePhysicalMs == null) ==
            (local.serverClockSampleElapsedRealtimeMs == null)
        ) { "Persisted server clock anchor is incomplete" }
        local.serverClockSamplePhysicalMs?.let {
            require(local.serverClockOffsetMs != null && it in 1..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock anchor is invalid"
            }
        }
        local.serverClockSampleElapsedRealtimeMs?.let {
            require(it in 0..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock anchor is invalid"
            }
        }
        require(local.serverClockBootId == null || local.serverClockSampleElapsedRealtimeMs != null) {
            "Persisted server clock boot identity is invalid"
        }
    }

    private fun <T> validateQueued(kind: String, values: List<T>, validator: (T) -> Unit) {
        try {
            values.forEach(validator)
        } catch (_: Exception) {
            throw SyncProtocolException("Queued $kind is invalid")
        }
    }

    private fun requireOperationClock(at: String, wall: Long, counter: Long, legacy: Boolean) {
        SyncWireBounds.requireOperationClock(at, wall, counter, allowLegacySentinel = legacy)
    }

    private fun validateCanonicalTaskId(taskId: String, source: String) {
        protocolRequire(isUuid(taskId), "$source returned an invalid task identity")
    }

    private fun validateAcknowledgement(id: String, outcome: String, source: String, kind: String) {
        protocolRequire(id.isNotBlank() && outcome in acknowledgementOutcomes,
            "$source returned an invalid $kind acknowledgement")
    }

    private fun SyncResponse.hasAcknowledgements(): Boolean = acknowledgements.isNotEmpty() ||
        taskAcknowledgements.isNotEmpty() || durationAcknowledgements.isNotEmpty() ||
        autoStartAcknowledgements.isNotEmpty() || selectedTaskAcknowledgements.isNotEmpty()

    private fun <T> requireUnique(values: List<T>, message: String) {
        require(values.toSet().size == values.size) { message }
    }

    private fun supportedPhysicalOccurrence(value: String): Boolean = runCatching {
        Instant.parse(value).toEpochMilli() in 1..SyncWireBounds.MaxSafeInteger
    }.getOrDefault(false)

    private fun protocolRequire(condition: Boolean, message: String) {
        if (!condition) throw SyncProtocolException(message)
    }

    private fun parseInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess
    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private const val MaxBootstrapOperations = 4096
    private const val MaxTimerDurationMs = 14_400_000L
    private const val MaxServerClockUncertaintyMs = 30_000L
    private val commandTypes = setOf(
        CommandType.Start,
        CommandType.Pause,
        CommandType.Resume,
        CommandType.Finish,
        CommandType.Cancel,
        CommandType.Clear,
    )
    private val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
    private val timerStatuses = activeStatuses + setOf(
        TimerStatus.Completed,
        TimerStatus.Cancelled,
        TimerStatus.Superseded,
    )
    private val historyStatuses = setOf(
        TimerStatus.Completed,
        TimerStatus.Cancelled,
        TimerStatus.Superseded,
    )
    private val acknowledgementOutcomes = setOf("applied", "ignored", "rejected")
}
