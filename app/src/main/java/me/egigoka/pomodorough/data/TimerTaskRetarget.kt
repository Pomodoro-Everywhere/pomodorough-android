package me.egigoka.pomodorough.data

// Canonical retarget planner. TimerMutationCoordinator delegates here so
// construction and validation live in exactly one place.
internal object TimerRetargetPolicy {
    fun plan(
        timerId: String,
        taskId: String?,
        current: CanonicalTimer,
        reservation: TimerMutationReservation,
        plannedDurationMs: Long,
        physicalNowMs: Long,
        elapsedMs: Long,
    ): TimerCommand? {
        if (current.id != timerId) return null
        if (current.status !in setOf(TimerStatus.Running, TimerStatus.Paused)) return null
        if (current.phase != TimerPhase.Focus) return null
        if (taskId != null && taskId.isBlank()) return null
        if (taskId == current.taskId) return null
        val stamp = reservation.stamps.singleOrNull() ?: return null
        val sequence = stamp.deviceSequence ?: return null
        val commandId = reservation.uuids.singleOrNull()?.toString() ?: return null
        val command = TimerCommand(
            id = commandId,
            deviceSequence = sequence,
            timerId = timerId,
            type = CommandType.Retarget,
            phase = TimerPhase.Focus,
            plannedDurationMs = plannedDurationMs,
            occurredAt = stamp.occurredAt,
            hlcWallMs = stamp.wallMs,
            hlcCounter = stamp.counter,
            observedElapsedMs = elapsedMs.coerceIn(0, plannedDurationMs),
            taskId = taskId,
            physicalOccurredAt = java.time.Instant.ofEpochMilli(physicalNowMs).toString(),
        )
        TimerSyncValidation.validateRetargetCommand(command)
        return command
    }
}
