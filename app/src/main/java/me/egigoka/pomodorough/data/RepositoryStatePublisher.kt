package me.egigoka.pomodorough.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.egigoka.pomodorough.data.iroh.protocol.IrohNetworkState
import me.egigoka.pomodorough.domain.TaskReducer

/** Immutable input for one complete AppState publication. */
internal data class RepositoryPublication(
    val ready: Boolean,
    val authStatus: AuthStatus,
    val localAccountResetRequired: Boolean,
    val user: User?,
    val projection: TimerProjection,
    val completionAlertTimerId: String?,
    val tasks: List<FocusTask>,
    val knownTasks: Collection<FocusTask>,
    val selectedTaskId: String?,
    val settings: TimerSettings,
    val pendingCounts: List<Int>,
    val online: Boolean,
    val syncing: Boolean,
    val retrying: Boolean,
    val historyResolution: HistoryResolutionState?,
    val accountSwitch: AccountSwitchState?,
    val conflict: String?,
    val notice: String?,
    val deviceId: String,
    val network: IrohNetworkState,
)

internal class AccountPublicationLinearizer {
    private val monitor = Any()
    @Volatile private var currentQuarantine = false

    val quarantined: Boolean
        get() = currentQuarantine

    fun publish(commit: (quarantined: Boolean) -> Unit) {
        synchronized(monitor) {
            commit(currentQuarantine)
        }
    }

    fun transition(
        quarantined: Boolean,
        repairPublication: ((quarantined: Boolean) -> Unit)? = null,
    ) {
        synchronized(monitor) {
            currentQuarantine = quarantined
            repairPublication?.invoke(currentQuarantine)
        }
    }
}

internal class RepositoryStatePublisher {
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun publish(value: RepositoryPublication) {
        mutableState.value = AppState(
            ready = value.ready,
            authStatus = value.authStatus,
            localAccountResetRequired = value.localAccountResetRequired,
            user = value.user,
            timer = value.projection.timer,
            completionAlertTimerId = value.completionAlertTimerId,
            history = value.projection.history,
            tasks = value.tasks,
            knownTasks = value.knownTasks.sortedWith(taskComparator),
            taskSummaries = TaskReducer.summariesToday(value.tasks, value.projection.history),
            selectedTaskId = value.selectedTaskId,
            settings = value.settings,
            pendingCount = value.pendingCounts.sum(),
            syncStatus = syncStatus(value),
            historyResolution = value.historyResolution,
            accountSwitch = value.accountSwitch,
            conflict = value.conflict,
            notice = value.notice,
            deviceId = value.deviceId,
            network = value.network,
        )
    }

    fun accept(event: AlarmCoordinatorEvent) {
        when (event) {
            is AlarmCoordinatorEvent.CompletionAlertChanged -> {
                mutableState.value = mutableState.value.copy(
                    completionAlertTimerId = event.timerId,
                )
            }
        }
    }

    private fun syncStatus(value: RepositoryPublication): SyncStatus = when {
        value.accountSwitch != null || value.historyResolution != null -> SyncStatus.Conflict
        !value.online -> SyncStatus.Offline
        value.conflict != null -> SyncStatus.Conflict
        value.syncing -> SyncStatus.Syncing
        value.retrying -> SyncStatus.Retrying
        value.pendingCounts.any { it > 0 } -> SyncStatus.Queued
        !value.ready -> SyncStatus.Checking
        else -> SyncStatus.Synced
    }

    private companion object {
        val taskComparator = compareBy<FocusTask> { it.title }.thenBy { it.id }
    }
}
