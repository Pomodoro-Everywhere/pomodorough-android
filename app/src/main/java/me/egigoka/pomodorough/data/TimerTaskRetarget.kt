package me.egigoka.pomodorough.data

/**
 * Local-only focus-timer task retarget (Apple TimerTaskPicker parity).
 *
 * Picking a task while a focus timer runs reassigns the running timer:
 * display and local history follow at once. The pending start command is
 * rewritten in place so the eventual synced history follows with no dup id.
 * Remote selected-task syncs never write the marker, so they cannot hijack
 * the active timer.
 */
internal fun retargetStartCommands(
    commands: List<TimerCommand>,
    timerId: String,
    taskId: String?,
): List<TimerCommand> = commands.map { command ->
    if (command.timerId == timerId && command.type == CommandType.Start) {
        command.copy(taskId = taskId)
    } else {
        command
    }
}

internal fun applyTimerTaskRetarget(
    timer: CanonicalTimer?,
    history: List<HistoryItem>,
    retarget: Map<String, String?>,
): Pair<CanonicalTimer?, List<HistoryItem>> {
    if (retarget.isEmpty()) return timer to history
    val retargetedTimer = timer?.let { current ->
        if (retarget.containsKey(current.id)) current.copy(taskId = retarget[current.id]) else current
    }
    val retargetedHistory = history.map { item ->
        if (retarget.containsKey(item.timerId)) item.copy(taskId = retarget[item.timerId]) else item
    }
    return retargetedTimer to retargetedHistory
}

internal fun pruneTimerTaskRetarget(
    retarget: Map<String, String?>,
    timer: CanonicalTimer?,
    history: List<HistoryItem>,
): Map<String, String?> {
    if (retarget.isEmpty()) return retarget
    val liveIds = history.mapTo(mutableSetOf()) { it.timerId }
    timer?.id?.let(liveIds::add)
    return retarget.filterKeys { it in liveIds }
}
