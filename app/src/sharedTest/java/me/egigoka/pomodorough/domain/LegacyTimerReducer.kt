package me.egigoka.pomodorough.domain

import java.time.Duration
import java.time.Instant
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerProjection
import me.egigoka.pomodorough.data.TimerStatus

internal object LegacyTimerReducer {
    fun replay(
        canonicalTimer: CanonicalTimer?,
        canonicalHistory: List<HistoryItem>,
        commands: List<TimerCommand>,
    ): TimerProjection {
        return replayOrdered(
            canonicalTimer,
            canonicalHistory,
            // Native pending commands all belong to this client device, so the
            // device-ID component of the Rust total-order tuple is constant here.
            commands.sortedWith(
                compareBy<TimerCommand>(
                    TimerCommand::hlcWallMs,
                    TimerCommand::hlcCounter,
                    TimerCommand::id,
                ),
            ),
        )
    }

    fun replayOrdered(
        canonicalTimer: CanonicalTimer?,
        canonicalHistory: List<HistoryItem>,
        commands: List<TimerCommand>,
    ): TimerProjection {
        var timer = canonicalTimer
        val history = canonicalHistory.toMutableList()
        val sessions = mutableMapOf<String, CanonicalTimer>()
        val historyIds = canonicalHistory.associateTo(mutableMapOf()) { it.timerId to it.id }
        canonicalTimer?.let { sessions[it.id] = it }
        commands.forEach { command ->
            timer = reduce(timer, history, command, sessions, historyIds)
        }
        return TimerProjection(timer, history.sortedWith(terminalHistoryComparator))
    }

    fun projectAt(
        timer: CanonicalTimer?,
        history: List<HistoryItem>,
        nowMs: Long,
    ): TimerProjection {
        val projectedHistory = history.toMutableList()
        val occurredAt = runCatching { Instant.ofEpochMilli(nowMs).toString() }.getOrNull()
            ?: return TimerProjection(timer, history)
        val historyIds = history.associateTo(mutableMapOf()) { it.timerId to it.id }
        return TimerProjection(
            autoComplete(timer, projectedHistory, occurredAt, historyIds),
            projectedHistory.sortedWith(terminalHistoryComparator),
        )
    }

    private fun reduce(
        initial: CanonicalTimer?,
        history: MutableList<HistoryItem>,
        command: TimerCommand,
        sessions: MutableMap<String, CanonicalTimer>,
        historyIds: MutableMap<String, String>,
    ): CanonicalTimer? {
        val occurredAt = command.physicalOccurredAt ?: command.occurredAt
        val context = ReductionContext(
            current = autoComplete(initial, history, occurredAt, historyIds),
            history = history,
            command = command,
            sessions = sessions,
            historyIds = historyIds,
            occurredAt = occurredAt,
        )
        val result = context.apply()
        result?.let { sessions[it.id] = it }
        return result
    }

    private class ReductionContext(
        val current: CanonicalTimer?,
        val history: MutableList<HistoryItem>,
        val command: TimerCommand,
        val sessions: MutableMap<String, CanonicalTimer>,
        val historyIds: MutableMap<String, String>,
        val occurredAt: String,
    ) {
        private val intent = TimerIntent(command.type, command.id, command.occurredAt)

        fun apply(): CanonicalTimer? = when (command.type) {
            CommandType.Start -> start()
            CommandType.Pause -> changeActivity(TimerStatus.Paused)
            CommandType.Resume -> changeActivity(TimerStatus.Running)
            CommandType.Finish -> terminate(TimerStatus.Completed)
            CommandType.Cancel -> terminate(TimerStatus.Cancelled)
            CommandType.Clear -> clear()
            else -> current
        }

        private fun target(): CanonicalTimer? {
            current?.takeIf { it.id == command.timerId }?.let { return it }
            val item = history.firstOrNull { it.timerId == command.timerId }
            if (item != null) {
                historyIds[item.timerId] = item.id
                return timerFromHistory(item)
            }
            return sessions[command.timerId]
        }

        private fun timerFromHistory(item: HistoryItem) = CanonicalTimer(
            id = item.timerId,
            phase = item.phase,
            status = item.status,
            plannedDurationMs = item.plannedDurationMs,
            elapsedAtAnchorMs = if (item.status == TimerStatus.Completed) item.plannedDurationMs else 0,
            anchorAt = item.endedAt ?: occurredAt,
            taskId = item.taskId,
            lastIntent = null,
        )

        private fun start(): CanonicalTimer {
            target()?.let { history.removeAll { item -> item.timerId == command.timerId } }
            preserveDisplaced(command.timerId)
            historyIds[command.timerId] = command.timerId
            return CanonicalTimer(
                id = command.timerId,
                phase = command.phase,
                status = TimerStatus.Running,
                plannedDurationMs = command.plannedDurationMs,
                elapsedAtAnchorMs = 0,
                anchorAt = occurredAt,
                taskId = command.taskId,
                lastIntent = intent,
            )
        }

        private fun changeActivity(status: String): CanonicalTimer? {
            val timer = target() ?: return current
            activate(timer)
            return timer.copy(
                status = status,
                elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                anchorAt = occurredAt,
                lastIntent = intent,
            )
        }

        private fun terminate(status: String): CanonicalTimer? {
            val timer = target() ?: return current
            val historyId = history.firstOrNull { it.timerId == timer.id }?.id
                ?: historyIds[timer.id] ?: timer.id
            activate(timer)
            val terminal = timer.copy(
                status = status,
                elapsedAtAnchorMs = if (status == TimerStatus.Completed) {
                    timer.plannedDurationMs
                } else {
                    command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs)
                },
                anchorAt = occurredAt,
                lastIntent = intent,
            )
            addTerminalHistory(history, terminal, command, status, occurredAt, historyId)
            return terminal
        }

        private fun clear(): CanonicalTimer? {
            val target = target() ?: return current
            sessions[command.timerId] = target
            return if (current?.id == command.timerId) null else current
        }

        private fun activate(timer: CanonicalTimer) {
            preserveDisplaced(timer.id)
            history.removeAll { it.timerId == timer.id }
        }

        private fun preserveDisplaced(replacementId: String) {
            val displaced = current?.takeIf { it.id != replacementId } ?: return
            if (displaced.status in activeStatuses) {
                addTerminalHistory(
                    history, displaced, command, TimerStatus.Superseded,
                    historyId = historyIds[displaced.id] ?: displaced.id,
                )
            } else if (displaced.status in terminalStatuses && history.none { it.timerId == displaced.id }) {
                history.add(0, displacedHistory(displaced))
            }
        }

        private fun displacedHistory(timer: CanonicalTimer) = HistoryItem(
            id = historyIds[timer.id] ?: timer.id,
            timerId = timer.id,
            commandId = timer.lastIntent?.commandId,
            phase = timer.phase,
            status = timer.status,
            plannedDurationMs = timer.plannedDurationMs,
            completedAt = timer.anchorAt.takeIf { timer.status == TimerStatus.Completed },
            endedAt = timer.anchorAt,
            taskId = timer.taskId,
        )
    }

    private fun autoComplete(
        current: CanonicalTimer?,
        history: MutableList<HistoryItem>,
        occurredAt: String,
        historyIds: MutableMap<String, String>,
    ): CanonicalTimer? {
        if (current == null || current.status != TimerStatus.Running) return current
        val anchor = runCatching { Instant.parse(current.anchorAt) }.getOrNull() ?: return current
        val occurrence = runCatching { Instant.parse(occurredAt) }.getOrNull() ?: return current
        val planned = current.plannedDurationMs.coerceAtLeast(0)
        val stored = current.elapsedAtAnchorMs.coerceIn(0, planned)
        val elapsedSinceAnchor = runCatching {
            Duration.between(anchor, occurrence).toMillis()
        }.getOrNull()?.coerceAtLeast(0) ?: return current
        val remaining = planned - stored
        if (elapsedSinceAnchor < remaining) return current
        val completedAt = runCatching { anchor.plusMillis(remaining).toString() }.getOrNull()
            ?: return current
        val completed = current.copy(
            status = TimerStatus.Completed,
            elapsedAtAnchorMs = current.plannedDurationMs,
            anchorAt = completedAt,
        )
        if (history.none { it.timerId == current.id }) {
            history.add(
                0,
                HistoryItem(
                    id = historyIds[current.id] ?: current.id,
                    timerId = current.id,
                    phase = current.phase,
                    status = TimerStatus.Completed,
                    plannedDurationMs = current.plannedDurationMs,
                    completedAt = completedAt,
                    endedAt = completedAt,
                    taskId = current.taskId,
                ),
            )
        }
        return completed
    }

    private fun addTerminalHistory(
        history: MutableList<HistoryItem>,
        timer: CanonicalTimer,
        command: TimerCommand,
        status: String,
        physicalOccurredAt: String = command.physicalOccurredAt ?: command.occurredAt,
        historyId: String = timer.id,
    ) {
        if (history.any { it.commandId == command.id && it.timerId == timer.id }) return
        history.add(
            0,
            HistoryItem(
                id = historyId,
                timerId = timer.id,
                commandId = command.id,
                phase = timer.phase,
                status = status,
                plannedDurationMs = timer.plannedDurationMs,
                completedAt = physicalOccurredAt.takeIf { status == TimerStatus.Completed },
                endedAt = physicalOccurredAt,
                pending = true,
                taskId = timer.taskId,
            ),
        )
    }

    private val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
    private val terminalStatuses = setOf(
        TimerStatus.Completed,
        TimerStatus.Cancelled,
        TimerStatus.Superseded,
    )

    private val terminalHistoryComparator = Comparator<HistoryItem> { left, right ->
        val leftTimestamp = terminalInstant(left)
        val rightTimestamp = terminalInstant(right)
        val timestampOrder = rightTimestamp.compareTo(leftTimestamp)
        if (timestampOrder != 0) timestampOrder else compareUtf8(left.timerId, right.timerId)
    }

    private fun terminalInstant(item: HistoryItem): Instant {
        val timestamp = item.completedAt ?: item.endedAt ?: return Instant.MIN
        return runCatching { Instant.parse(timestamp) }.getOrDefault(Instant.MIN)
    }

    private fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
            val difference = (leftBytes[index].toInt() and 0xff) -
                (rightBytes[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return leftBytes.size - rightBytes.size
    }
}
