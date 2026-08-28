package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.storage.BootstrapPreparationStorageUpdate
import me.egigoka.pomodorough.data.storage.BootstrapResolutionStorageUpdate
import me.egigoka.pomodorough.data.storage.FullSyncStorageUpdate
import me.egigoka.pomodorough.data.storage.TimerStore

internal sealed interface RepositoryTransition<out E : RepositoryTransitionEvent> {
    fun event(): E
}

internal data class RepositorySyncTransition(
    val update: FullSyncStorageUpdate,
    val application: CentralizedSyncApplication,
    val response: SyncResponse,
    val clockSample: ServerClockSample,
) : RepositoryTransition<RepositoryTransitionEvent.SyncApplied> {
    override fun event() = RepositoryTransitionEvent.SyncApplied(application, response, clockSample)
}

internal data class RepositoryBootstrapPreparationTransition(
    val update: BootstrapPreparationStorageUpdate,
    val profile: User,
    val bootstrap: SyncResponse,
    val clockSample: ServerClockSample,
    val plan: CentralizedBootstrapPreparationTransition.Planned,
    val resolution: PendingBootstrapResolutionEntity,
) : RepositoryTransition<RepositoryTransitionEvent.BootstrapPrepared> {
    override fun event() = RepositoryTransitionEvent.BootstrapPrepared(
        profile, bootstrap, clockSample, plan, resolution,
    )
}

internal data class RepositoryBootstrapInstallationTransition(
    val application: CentralizedSyncApplication,
    val response: SyncResponse,
    val clearLocal: Boolean,
    val clockSample: ServerClockSample,
) : RepositoryTransition<RepositoryTransitionEvent.BootstrapInstalled> {
    override fun event() = RepositoryTransitionEvent.BootstrapInstalled(
        application, response, clearLocal, clockSample,
    )
}

internal data class RepositoryBootstrapResolutionTransition(
    val update: BootstrapResolutionStorageUpdate,
    val application: CentralizedSyncApplication,
    val response: SyncResponse,
    val clockSample: ServerClockSample,
) : RepositoryTransition<RepositoryTransitionEvent.BootstrapResolved> {
    override fun event() = RepositoryTransitionEvent.BootstrapResolved(
        application, response, clockSample,
    )
}

internal data class RepositoryTimerCommandTransition(
    val plan: TimerCommandMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.TimerCommandApplied> {
    override fun event() = RepositoryTransitionEvent.TimerCommandApplied(plan)
}

internal data class RepositoryTimerCommandBatchTransition(
    val plan: TimerCommandMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.TimerCommandBatchApplied> {
    override fun event() = RepositoryTransitionEvent.TimerCommandBatchApplied(plan)
}

internal data class RepositoryDurationTransition(
    val plan: DurationMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.DurationApplied> {
    override fun event() = RepositoryTransitionEvent.DurationApplied(plan)
}

internal data class RepositoryAutoStartTransition(
    val plan: AutoStartMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.AutoStartApplied> {
    override fun event() = RepositoryTransitionEvent.AutoStartApplied(plan)
}

internal data class RepositorySelectedTaskTransition(
    val plan: SelectedTaskMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.SelectedTaskApplied> {
    override fun event() = RepositoryTransitionEvent.SelectedTaskApplied(plan)
}

internal data class RepositoryTaskTransition(
    val plan: TaskMutationPlan,
) : RepositoryTransition<RepositoryTransitionEvent.TaskApplied> {
    override fun event() = RepositoryTransitionEvent.TaskApplied(plan)
}

internal sealed interface RepositoryTransitionEvent {
    data class SyncApplied(
        val application: CentralizedSyncApplication,
        val response: SyncResponse,
        val clockSample: ServerClockSample,
    ) : RepositoryTransitionEvent

    data class BootstrapPrepared(
        val profile: User,
        val bootstrap: SyncResponse,
        val clockSample: ServerClockSample,
        val plan: CentralizedBootstrapPreparationTransition.Planned,
        val resolution: PendingBootstrapResolutionEntity,
    ) : RepositoryTransitionEvent

    data class BootstrapInstalled(
        val application: CentralizedSyncApplication,
        val response: SyncResponse,
        val clearLocal: Boolean,
        val clockSample: ServerClockSample,
    ) : RepositoryTransitionEvent

    data class BootstrapResolved(
        val application: CentralizedSyncApplication,
        val response: SyncResponse,
        val clockSample: ServerClockSample,
    ) : RepositoryTransitionEvent

    data class TimerCommandApplied(val plan: TimerCommandMutationPlan) : RepositoryTransitionEvent

    data class TimerCommandBatchApplied(
        val plan: TimerCommandMutationPlan,
    ) : RepositoryTransitionEvent

    data class DurationApplied(val plan: DurationMutationPlan) : RepositoryTransitionEvent

    data class AutoStartApplied(val plan: AutoStartMutationPlan) : RepositoryTransitionEvent

    data class SelectedTaskApplied(val plan: SelectedTaskMutationPlan) : RepositoryTransitionEvent

    data class TaskApplied(val plan: TaskMutationPlan) : RepositoryTransitionEvent
}

internal fun interface RepositoryTransitionPersistence {
    suspend fun persist(transition: RepositoryTransition<*>)
}

internal class RepositoryTransitionCommitter(
    private val persistence: RepositoryTransitionPersistence,
) {
    suspend fun <E : RepositoryTransitionEvent> commit(transition: RepositoryTransition<E>): E {
        persistence.persist(transition)
        return transition.event()
    }

    companion object {
        fun forTimerStore(timerStore: TimerStore): RepositoryTransitionCommitter {
            return RepositoryTransitionCommitter(TimerStoreTransitionPersistence(timerStore))
        }
    }
}

private class TimerStoreTransitionPersistence(
    private val timerStore: TimerStore,
) : RepositoryTransitionPersistence {
    override suspend fun persist(transition: RepositoryTransition<*>) {
        when (transition) {
            is RepositorySyncTransition -> timerStore.applyFullSync(transition.update)
            is RepositoryBootstrapPreparationTransition ->
                timerStore.prepareBootstrap(transition.update)
            is RepositoryBootstrapInstallationTransition -> installBootstrap(transition)
            is RepositoryBootstrapResolutionTransition ->
                timerStore.applyBootstrapResolution(transition.update)
            is RepositoryTimerCommandTransition -> saveTimerCommand(transition.plan)
            is RepositoryTimerCommandBatchTransition -> timerStore.saveTimerCommands(
                transition.plan.local,
                transition.plan.commands,
                transition.plan.dependencies,
            )
            is RepositoryDurationTransition -> timerStore.saveDurationOperation(
                transition.plan.local,
                transition.plan.operation,
            )
            is RepositoryAutoStartTransition -> timerStore.saveAutoStartOperation(
                transition.plan.local,
                transition.plan.operation,
            )
            is RepositorySelectedTaskTransition -> timerStore.saveSelectedTaskOperation(
                transition.plan.local,
                transition.plan.operation,
            )
            is RepositoryTaskTransition -> timerStore.saveTaskOperation(
                transition.plan.local,
                transition.plan.operation,
                transition.plan.selectedOperation,
            )
        }
    }

    private suspend fun installBootstrap(transition: RepositoryBootstrapInstallationTransition) {
        val application = transition.application
        if (transition.clearLocal) {
            timerStore.clearAccount(application.local)
        } else {
            timerStore.saveMutationState(
                application.local,
                application.pending.queues,
                application.pending.dependencies,
            )
        }
    }

    private suspend fun saveTimerCommand(plan: TimerCommandMutationPlan) {
        val command = plan.commands.single()
        timerStore.saveTimerCommand(plan.local, command, plan.dependencies[command.id])
    }
}
