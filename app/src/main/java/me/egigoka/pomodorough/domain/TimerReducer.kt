package me.egigoka.pomodorough.domain

import java.time.Duration
import java.time.Instant
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerProjection
import me.egigoka.pomodorough.data.TimerStatus

object TimerReducer {
    fun replay(
        canonicalTimer: CanonicalTimer?,
        canonicalHistory: List<HistoryItem>,
        commands: List<TimerCommand>,
    ): TimerProjection {
        return replayOrdered(
            canonicalTimer,
            canonicalHistory,
            commands.sortedWith(compareBy(TimerCommand::deviceSequence, TimerCommand::id)),
        )
    }

    fun replayOrdered(
        canonicalTimer: CanonicalTimer?,
        canonicalHistory: List<HistoryItem>,
        commands: List<TimerCommand>,
    ): TimerProjection {
        var timer = canonicalTimer
        val history = canonicalHistory.toMutableList()
        commands.forEach { command ->
            timer = reduce(timer, history, command)
        }
        return TimerProjection(timer, history)
    }

    fun projectAt(
        timer: CanonicalTimer?,
        history: List<HistoryItem>,
        nowMs: Long,
    ): TimerProjection {
        val projectedHistory = history.toMutableList()
        val occurredAt = runCatching { Instant.ofEpochMilli(nowMs).toString() }.getOrNull()
            ?: return TimerProjection(timer, history)
        return TimerProjection(autoComplete(timer, projectedHistory, occurredAt), projectedHistory)
    }

    fun elapsedAt(timer: CanonicalTimer?, nowMs: Long = System.currentTimeMillis()): Long {
        if (timer == null) return 0
        var elapsed = timer.elapsedAtAnchorMs.coerceIn(0, timer.plannedDurationMs)
        if (timer.status == TimerStatus.Running) {
            val anchorMs = runCatching { Instant.parse(timer.anchorAt).toEpochMilli() }.getOrNull()
            if (anchorMs != null) elapsed += (nowMs - anchorMs).coerceAtLeast(0)
        }
        return elapsed.coerceIn(0, timer.plannedDurationMs)
    }

    private fun reduce(
        initial: CanonicalTimer?,
        history: MutableList<HistoryItem>,
        command: TimerCommand,
    ): CanonicalTimer? {
        val physicalOccurredAt = command.physicalOccurredAt ?: command.occurredAt
        val current = autoComplete(initial, history, physicalOccurredAt)
        val intent = TimerIntent(command.type, command.id, command.occurredAt)
        return when (command.type) {
            CommandType.Start -> if (
                current?.id == command.timerId || history.any { it.timerId == command.timerId }
            ) {
                current
            } else {
                current?.takeIf { it.status in activeStatuses }?.let { timer ->
                    addTerminalHistory(history, timer, command, TimerStatus.Superseded)
                }
                CanonicalTimer(
                    id = command.timerId,
                    phase = command.phase,
                    status = TimerStatus.Running,
                    plannedDurationMs = command.plannedDurationMs,
                    elapsedAtAnchorMs = 0,
                    anchorAt = physicalOccurredAt,
                    taskId = command.taskId,
                    lastIntent = intent,
                )
            }

            CommandType.Pause -> current?.takeIf {
                it.id == command.timerId && it.status == TimerStatus.Running
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Paused,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = physicalOccurredAt,
                    lastIntent = intent,
                )
            } ?: current

            CommandType.Resume -> current?.takeIf {
                it.id == command.timerId && it.status in resumableStatuses
            }?.let { timer ->
                if (timer.status == TimerStatus.Superseded) {
                    history.removeAll {
                        it.timerId == timer.id && it.status == TimerStatus.Superseded
                    }
                }
                timer.copy(
                    status = TimerStatus.Running,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = physicalOccurredAt,
                    lastIntent = intent,
                )
            } ?: history.firstOrNull {
                it.timerId == command.timerId && it.status == TimerStatus.Superseded
            }?.let { target ->
                current?.takeIf { it.status in activeStatuses }?.let { timer ->
                    addTerminalHistory(history, timer, command, TimerStatus.Superseded)
                }
                history.removeAll {
                    it.timerId == target.timerId && it.status == TimerStatus.Superseded
                }
                CanonicalTimer(
                    id = target.timerId,
                    phase = target.phase,
                    status = TimerStatus.Running,
                    plannedDurationMs = target.plannedDurationMs,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(
                        0,
                        target.plannedDurationMs,
                    ),
                    anchorAt = physicalOccurredAt,
                    taskId = target.taskId,
                    lastIntent = intent,
                )
            } ?: current

            CommandType.Finish -> {
                val unclaimedCompletion = history.indexOfFirst {
                    current?.id == command.timerId &&
                        it.timerId == command.timerId &&
                        it.status == TimerStatus.Completed &&
                        it.commandId == null
                }
                when {
                    current?.status == TimerStatus.Completed && unclaimedCompletion >= 0 -> {
                        history[unclaimedCompletion] = history[unclaimedCompletion].copy(
                            commandId = command.id,
                            pending = true,
                        )
                        current.copy(lastIntent = intent)
                    }

                    current?.id == command.timerId && current.status in activeStatuses -> current.copy(
                        status = TimerStatus.Completed,
                        elapsedAtAnchorMs = current.plannedDurationMs,
                        anchorAt = physicalOccurredAt,
                        lastIntent = intent,
                    ).also { completedTimer ->
                        addTerminalHistory(
                            history,
                            completedTimer,
                            command,
                            TimerStatus.Completed,
                            physicalOccurredAt,
                        )
                    }

                    else -> current
                }
            }

            CommandType.Cancel -> current?.takeIf {
                it.id == command.timerId && it.status in activeStatuses
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Cancelled,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = physicalOccurredAt,
                    lastIntent = intent,
                )
            }?.also { cancelledTimer ->
                addTerminalHistory(
                    history,
                    cancelledTimer,
                    command,
                    TimerStatus.Cancelled,
                    physicalOccurredAt,
                )
            } ?: current

            CommandType.Clear -> if (
                current?.id == command.timerId &&
                current.status in setOf(
                    TimerStatus.Completed,
                    TimerStatus.Cancelled,
                )
            ) null else current

            else -> current
        }
    }

    private fun autoComplete(
        current: CanonicalTimer?,
        history: MutableList<HistoryItem>,
        occurredAt: String,
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
                    id = current.id,
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
    ) {
        if (history.any { it.commandId == command.id }) return
        history.add(
            0,
            HistoryItem(
                id = "${timer.id}:${command.id}",
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
    private val resumableStatuses = setOf(TimerStatus.Paused, TimerStatus.Superseded)
}
