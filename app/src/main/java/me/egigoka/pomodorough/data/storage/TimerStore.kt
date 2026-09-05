package me.egigoka.pomodorough.data.storage

import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.LocalInitializationData
import me.egigoka.pomodorough.data.PendingSyncQueues
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerLocalInitializer
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.CentralizedSyncDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.loadAutoStartOperationsBounded
import me.egigoka.pomodorough.data.local.loadCommandsBounded
import me.egigoka.pomodorough.data.local.loadDurationOperationsBounded
import me.egigoka.pomodorough.data.local.loadSelectedTaskOperationsBounded
import me.egigoka.pomodorough.data.local.loadTaskOperationsBounded

internal data class StoredTimerWorkspace(
    val local: LocalStateEntity,
    val pending: PendingSyncQueues,
    val commandDependencies: Map<String, String>,
    val bootstrapResolution: PendingBootstrapResolutionEntity?,
    val settings: TimerSettings,
    val canonicalTimer: CanonicalTimer?,
    val canonicalHistory: List<HistoryItem>,
    val canonicalTasks: List<FocusTask>,
    val canonicalAutoStartBreaks: Boolean,
    val knownTasks: Map<String, FocusTask>,
    val user: User?,
)

internal data class BootstrapPreparationStorageUpdate(
    val local: LocalStateEntity,
    val pending: PendingSyncQueues,
    val commandDependencies: Map<String, String>,
    val resolution: PendingBootstrapResolutionEntity,
)

internal data class FullSyncStorageUpdate(
    val local: LocalStateEntity,
    val acknowledged: SyncRequest,
    val acknowledgedDurationOperationIds: List<String>,
    val retained: PendingSyncQueues,
    val retainedCommandDependencies: Map<String, String>,
    val discardedCommands: List<TimerCommand>,
    val discardedCommandDependencies: Map<String, String>,
)

internal data class BootstrapResolutionStorageUpdate(
    val local: LocalStateEntity,
    val clearAutoStartOperations: Boolean,
    val retainedCommands: List<TimerCommand>,
    val retainedCommandDependencies: Map<String, String>,
    val retainedAutoStartOperations: List<AutoStartOperation>,
    val clearSelectedTaskOperations: Boolean,
    val retainedSelectedTaskOperations: List<SelectedTaskOperation>,
)

internal class TimerStore(
    private val dao: CentralizedSyncDao,
    json: Json,
    private val strictJson: Json,
    validateUser: (User) -> Unit,
) {
    private val initializer = TimerLocalInitializer(dao, dao, json, strictJson, validateUser)

    suspend fun initialize(): LocalInitializationData = initializer.load()

    suspend fun loadWorkspace(): StoredTimerWorkspace {
        val local = checkNotNull(dao.localState()) { "Local workspace is missing" }
        val commandEntities = dao.loadCommandsBounded()
        val pending = PendingSyncQueues(
            commands = commandEntities.map(PendingCommandEntity::toModel),
            taskOperations = dao.loadTaskOperationsBounded().map(PendingTaskOperationEntity::toModel),
            durationOperations = dao.loadDurationOperationsBounded()
                .map(PendingDurationOperationEntity::toModel),
            autoStartOperations = dao.loadAutoStartOperationsBounded()
                .map(PendingAutoStartOperationEntity::toModel),
            selectedTaskOperations = dao.loadSelectedTaskOperationsBounded()
                .map(PendingSelectedTaskOperationEntity::toModel),
        )
        val bootstrapResolution = dao.pendingBootstrapResolution()
        val settings: TimerSettings = strictJson.decodeFromString(local.settingsJson)
        val canonicalTimer: CanonicalTimer? = local.canonicalTimerJson?.let(strictJson::decodeFromString)
        val canonicalHistory: List<HistoryItem> = strictJson.decodeFromString(local.historyJson)
        val canonicalTasks: List<FocusTask> = strictJson.decodeFromString(local.tasksJson)
        val knownTasks = strictJson.decodeFromString<List<FocusTask>>(local.knownTasksJson)
            .plus(canonicalTasks)
            .associateBy(FocusTask::id)
        val user: User? = local.userJson?.let(strictJson::decodeFromString)
        return StoredTimerWorkspace(
            local = local,
            pending = pending,
            commandDependencies = commandDependencies(commandEntities),
            bootstrapResolution = bootstrapResolution,
            settings = settings,
            canonicalTimer = canonicalTimer,
            canonicalHistory = canonicalHistory,
            canonicalTasks = canonicalTasks,
            canonicalAutoStartBreaks = local.canonicalAutoStartBreaks,
            knownTasks = knownTasks,
            user = user,
        )
    }

    suspend fun saveState(local: LocalStateEntity) {
        dao.updateState(local)
    }

    suspend fun clearAccount(local: LocalStateEntity) {
        dao.clearAccount(local)
    }

    suspend fun saveMutationState(
        local: LocalStateEntity,
        pending: PendingSyncQueues,
        commandDependencies: Map<String, String>,
    ) {
        dao.updateMutationState(
            local,
            pending.commandEntities(commandDependencies),
            pending.taskEntities(),
            pending.durationEntities(),
            pending.autoStartEntities(),
            pending.selectedTaskEntities(),
        )
    }

    suspend fun deleteCommands(commands: List<PendingCommandEntity>) {
        dao.deleteCommands(commands)
    }

    suspend fun saveTimerCommand(
        local: LocalStateEntity,
        command: TimerCommand,
        dependency: String?,
    ) {
        dao.persistCommand(PendingCommandEntity.from(command, dependency), local)
    }

    suspend fun saveTimerCommands(
        local: LocalStateEntity,
        commands: List<TimerCommand>,
        commandDependencies: Map<String, String>,
    ) {
        dao.persistCommands(
            commands.map { PendingCommandEntity.from(it, commandDependencies[it.id]) },
            local,
        )
    }

    suspend fun saveTaskOperation(
        local: LocalStateEntity,
        operation: TaskOperation,
        selectedTaskOperation: SelectedTaskOperation?,
    ) {
        dao.persistTaskOperation(
            PendingTaskOperationEntity.from(operation),
            local,
            selectedTaskOperation?.let(PendingSelectedTaskOperationEntity::from),
        )
    }

    suspend fun saveDurationOperation(local: LocalStateEntity, operation: DurationOperation) {
        dao.persistDurationOperation(PendingDurationOperationEntity.from(operation), local)
    }

    suspend fun saveAutoStartOperation(local: LocalStateEntity, operation: AutoStartOperation) {
        dao.persistAutoStartOperation(PendingAutoStartOperationEntity.from(operation), local)
    }

    suspend fun saveSelectedTaskOperation(local: LocalStateEntity, operation: SelectedTaskOperation) {
        dao.persistSelectedTaskOperation(PendingSelectedTaskOperationEntity.from(operation), local)
    }

    suspend fun discardBootstrapResolution() {
        dao.deleteBootstrapResolution()
    }

    suspend fun prepareBootstrap(update: BootstrapPreparationStorageUpdate) {
        dao.persistBootstrapPreparation(
            update.local,
            update.pending.commandEntities(update.commandDependencies),
            update.pending.taskEntities(),
            update.pending.durationEntities(),
            update.pending.autoStartEntities(),
            update.resolution,
            update.pending.selectedTaskEntities(),
        )
    }

    suspend fun applyFullSync(update: FullSyncStorageUpdate) {
        dao.applyFullSync(
            acknowledgedCommands = update.acknowledged.commands.map(PendingCommandEntity::from),
            acknowledgedTaskOperations = update.acknowledged.taskOperations
                .map(PendingTaskOperationEntity::from),
            acknowledgedDurationOperationIds = update.acknowledgedDurationOperationIds,
            state = update.local,
            acknowledgedAutoStartOperations = update.acknowledged.autoStartOperations
                .map(PendingAutoStartOperationEntity::from),
            updatedCommands = update.retained.commandEntities(update.retainedCommandDependencies),
            updatedTaskOperations = update.retained.taskEntities(),
            updatedDurationOperations = update.retained.durationEntities(),
            updatedAutoStartOperations = update.retained.autoStartEntities(),
            discardedCommands = update.discardedCommands.map { command ->
                PendingCommandEntity.from(command, update.discardedCommandDependencies[command.id])
            },
            acknowledgedSelectedTaskOperations = update.acknowledged.selectedTaskOperations
                .map(PendingSelectedTaskOperationEntity::from),
            updatedSelectedTaskOperations = update.retained.selectedTaskEntities(),
        )
    }

    suspend fun applyBootstrapResolution(update: BootstrapResolutionStorageUpdate) {
        dao.applyBootstrapResolution(
            update.local,
            clearAutoStartOperations = update.clearAutoStartOperations,
            retainedCommands = update.retainedCommands.map { command ->
                PendingCommandEntity.from(command, update.retainedCommandDependencies[command.id])
            },
            retainedAutoStartOperations = update.retainedAutoStartOperations
                .map(PendingAutoStartOperationEntity::from),
            clearSelectedTaskOperations = update.clearSelectedTaskOperations,
            retainedSelectedTaskOperations = update.retainedSelectedTaskOperations
                .map(PendingSelectedTaskOperationEntity::from),
        )
    }

    private fun commandDependencies(
        entities: List<PendingCommandEntity>,
    ): Map<String, String> = entities.mapNotNull { entity ->
        entity.generatedByFinishCommandId?.let { entity.id to it }
    }.toMap()

    private fun PendingSyncQueues.commandEntities(
        dependencies: Map<String, String>,
    ): List<PendingCommandEntity> = commands.map { command ->
        PendingCommandEntity.from(command, dependencies[command.id])
    }

    private fun PendingSyncQueues.taskEntities() =
        taskOperations.map(PendingTaskOperationEntity::from)

    private fun PendingSyncQueues.durationEntities() =
        durationOperations.map(PendingDurationOperationEntity::from)

    private fun PendingSyncQueues.autoStartEntities() =
        autoStartOperations.map(PendingAutoStartOperationEntity::from)

    private fun PendingSyncQueues.selectedTaskEntities() =
        selectedTaskOperations.map(PendingSelectedTaskOperationEntity::from)
}
