package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.data.local.LocalStateEntity

/**
 * R43-A03 safe projection policy.
 * A domain projects only when every retained operation carries never-sent
 * proof and its HLC strictly exceeds the canonical head. Otherwise the whole
 * domain stays at its canonical base. Mirrors Core delivery policy and the
 * Desktop safe-projection filter; Core still computes the final projection.
 */
internal object SafeProjectionPolicy {
    fun headOrNull(local: LocalStateEntity): Pair<Long, Long>? {
        val wall = local.safeCanonicalHeadWallMs ?: return null
        val counter = local.safeCanonicalHeadCounter ?: return null
        return wall to counter
    }

    fun safeQueues(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>?,
    ): PendingSyncQueues {
        if (head == null) return queues
        return PendingSyncQueues(
            commands = safeCommands(queues, proof, head),
            taskOperations = safeTasks(queues, proof, head),
            durationOperations = safeDurations(queues, proof, head),
            autoStartOperations = safeAutoStarts(queues, proof, head),
            selectedTaskOperations = safeSelected(queues, proof, head),
        )
    }

    private fun safeCommands(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>,
    ): List<TimerCommand> {
        if (queues.commands.isEmpty()) return emptyList()
        val proven = proof.commands.toSet()
        val fresh = queues.commands.all { it.id in proven && after(it.hlcWallMs, it.hlcCounter, head) }
        return if (fresh) queues.commands else emptyList()
    }

    private fun safeTasks(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>,
    ): List<TaskOperation> {
        if (queues.taskOperations.isEmpty()) return emptyList()
        val proven = proof.taskOperations.toSet()
        val fresh = queues.taskOperations.all { it.id in proven && after(it.hlcWallMs, it.hlcCounter, head) }
        return if (fresh) queues.taskOperations else emptyList()
    }

    private fun safeDurations(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>,
    ): List<DurationOperation> {
        if (queues.durationOperations.isEmpty()) return emptyList()
        val proven = proof.durationOperations.toSet()
        val ops = queues.durationOperations
        val fresh = ops.all { it.id in proven && after(it.hlcWallMs, it.hlcCounter, head) }
        return if (fresh) ops else emptyList()
    }

    private fun safeAutoStarts(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>,
    ): List<AutoStartOperation> {
        if (queues.autoStartOperations.isEmpty()) return emptyList()
        val proven = proof.autoStartOperations.toSet()
        val ops = queues.autoStartOperations
        val fresh = ops.all { it.id in proven && after(it.hlcWallMs, it.hlcCounter, head) }
        return if (fresh) ops else emptyList()
    }

    private fun safeSelected(
        queues: PendingSyncQueues,
        proof: CoreNeverSentProof,
        head: Pair<Long, Long>,
    ): List<SelectedTaskOperation> {
        if (queues.selectedTaskOperations.isEmpty()) return emptyList()
        val proven = proof.selectedTaskOperations.toSet()
        val ops = queues.selectedTaskOperations
        val fresh = ops.all { it.id in proven && after(it.hlcWallMs, it.hlcCounter, head) }
        return if (fresh) ops else emptyList()
    }

    private fun after(wall: Long, counter: Long, head: Pair<Long, Long>): Boolean =
        wall > head.first || (wall == head.first && counter > head.second)
}
