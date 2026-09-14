package me.egigoka.pomodorough.data.storage

import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CoreNeverSentProof
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
    val neverSent: CoreNeverSentProof,
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
    val retainedNeverSent: CoreNeverSentProof = CoreNeverSentProof(),
)

internal data class FullSyncStorageUpdate(
    val local: LocalStateEntity,
    val acknowledged: SyncRequest,
    val acknowledgedDurationOperationIds: List<String>,
    val retained: PendingSyncQueues,
    val retainedCommandDependencies: Map<String, String>,
    val discardedCommands: List<TimerCommand>,
    val discardedCommandDependencies: Map<String, String>,
    val retainedNeverSent: CoreNeverSentProof = CoreNeverSentProof(),
)

internal data class BootstrapResolutionStorageUpdate(
    val local: LocalStateEntity,
    val clearAutoStartOperations: Boolean,
    val retainedCommands: List<TimerCommand>,
    val retainedCommandDependencies: Map<String, String>,
    val retainedAutoStartOperations: List<AutoStartOperation>,
    val clearSelectedTaskOperations: Boolean,
    val retainedSelectedTaskOperations: List<SelectedTaskOperation>,
    val retainedNeverSent: CoreNeverSentProof = CoreNeverSentProof(),
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
        val taskEntities = dao.loadTaskOperationsBounded()
        val durationEntities = dao.loadDurationOperationsBounded()
        val autoStartEntities = dao.loadAutoStartOperationsBounded()
        val selectedEntities = dao.loadSelectedTaskOperationsBounded()
        val pending = PendingSyncQueues(
            commands = commandEntities.map(PendingCommandEntity::toModel),
            taskOperations = taskEntities.map(PendingTaskOperationEntity::toModel),
            durationOperations = durationEntities.map(PendingDurationOperationEntity::toModel),
            autoStartOperations = autoStartEntities.map(PendingAutoStartOperationEntity::toModel),
            selectedTaskOperations = selectedEntities.map(PendingSelectedTaskOperationEntity::toModel),
        )
        val neverSent = CoreNeverSentProof(
            commands = commandEntities.filter { it.neverSent }.map { it.id },
            taskOperations = taskEntities.filter { it.neverSent }.map { it.id },
            durationOperations = durationEntities.filter { it.neverSent }.map { it.id },
            autoStartOperations = autoStartEntities.filter { it.neverSent }.map { it.id },
            selectedTaskOperations = selectedEntities.filter { it.neverSent }.map { it.id },
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
            neverSent = neverSent,
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
        neverSent: CoreNeverSentProof = CoreNeverSentProof(),
    ) {
        dao.updateMutationState(
            local,
            pending.commandEntities(commandDependencies, neverSent.commands.toSet()),
            pending.taskEntities(neverSent.taskOperations.toSet()),
            pending.durationEntities(neverSent.durationOperations.toSet()),
            pending.autoStartEntities(neverSent.autoStartOperations.toSet()),
            pending.selectedTaskEntities(neverSent.selectedTaskOperations.toSet()),
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
            update.pending.commandEntities(update.commandDependencies, update.retainedNeverSent.commands.toSet()),
            update.pending.taskEntities(update.retainedNeverSent.taskOperations.toSet()),
            update.pending.durationEntities(update.retainedNeverSent.durationOperations.toSet()),
            update.pending.autoStartEntities(update.retainedNeverSent.autoStartOperations.toSet()),
            update.resolution,
            update.pending.selectedTaskEntities(update.retainedNeverSent.selectedTaskOperations.toSet()),
        )
    }

    suspend fun applyFullSync(update: FullSyncStorageUpdate) {
        val proof = update.retainedNeverSent
        dao.applyFullSync(
            acknowledgedCommands = update.acknowledged.commands.map { PendingCommandEntity.from(it, neverSent = false) },
            acknowledgedTaskOperations = update.acknowledged.taskOperations
                .map { PendingTaskOperationEntity.from(it, neverSent = false) },
            acknowledgedDurationOperationIds = update.acknowledgedDurationOperationIds,
            state = update.local,
            acknowledgedAutoStartOperations = update.acknowledged.autoStartOperations
                .map { PendingAutoStartOperationEntity.from(it, neverSent = false) },
            updatedCommands = update.retained.commands.map { command ->
                PendingCommandEntity.from(command, update.retainedCommandDependencies[command.id], command.id in proof.commands)
            },
            updatedTaskOperations = update.retained.taskOperations.map {
                PendingTaskOperationEntity.from(it, it.id in proof.taskOperations)
            },
            updatedDurationOperations = update.retained.durationOperations.map {
                PendingDurationOperationEntity.from(it, it.id in proof.durationOperations)
            },
            updatedAutoStartOperations = update.retained.autoStartOperations.map {
                PendingAutoStartOperationEntity.from(it, it.id in proof.autoStartOperations)
            },
            discardedCommands = update.discardedCommands.map { command ->
                PendingCommandEntity.from(command, update.discardedCommandDependencies[command.id], neverSent = false)
            },
            acknowledgedSelectedTaskOperations = update.acknowledged.selectedTaskOperations
                .map { PendingSelectedTaskOperationEntity.from(it, neverSent = false) },
            updatedSelectedTaskOperations = update.retained.selectedTaskOperations.map {
                PendingSelectedTaskOperationEntity.from(it, it.id in proof.selectedTaskOperations)
            },
        )
    }

    suspend fun retireNeverSent(request: SyncRequest) {
        dao.retireNeverSent(
            request.commands.map { it.id },
            request.taskOperations.map { it.id },
            request.durationOperations.map { it.id },
            request.autoStartOperations.map { it.id },
            request.selectedTaskOperations.map { it.id },
        )
    }

    suspend fun applyBootstrapResolution(update: BootstrapResolutionStorageUpdate) {
        val proof = update.retainedNeverSent
        dao.applyBootstrapResolution(
            update.local,
            clearAutoStartOperations = update.clearAutoStartOperations,
            retainedCommands = update.retainedCommands.map { command ->
                PendingCommandEntity.from(command, update.retainedCommandDependencies[command.id], command.id in proof.commands)
            },
            retainedAutoStartOperations = update.retainedAutoStartOperations
                .map { PendingAutoStartOperationEntity.from(it, it.id in proof.autoStartOperations) },
            clearSelectedTaskOperations = update.clearSelectedTaskOperations,
            retainedSelectedTaskOperations = update.retainedSelectedTaskOperations
                .map { PendingSelectedTaskOperationEntity.from(it, it.id in proof.selectedTaskOperations) },
        )
    }

    private fun commandDependencies(
        entities: List<PendingCommandEntity>,
    ): Map<String, String> = entities.mapNotNull { entity ->
        entity.generatedByFinishCommandId?.let { entity.id to it }
    }.toMap()

    private fun PendingSyncQueues.commandEntities(
        dependencies: Map<String, String>,
        neverSent: Set<String> = emptySet(),
    ): List<PendingCommandEntity> = commands.map { command ->
        PendingCommandEntity.from(command, dependencies[command.id], command.id in neverSent)
    }

    private fun PendingSyncQueues.taskEntities(neverSent: Set<String> = emptySet()) =
        taskOperations.map { PendingTaskOperationEntity.from(it, it.id in neverSent) }

    private fun PendingSyncQueues.durationEntities(neverSent: Set<String> = emptySet()) =
        durationOperations.map { PendingDurationOperationEntity.from(it, it.id in neverSent) }

    private fun PendingSyncQueues.autoStartEntities(neverSent: Set<String> = emptySet()) =
        autoStartOperations.map { PendingAutoStartOperationEntity.from(it, it.id in neverSent) }

    private fun PendingSyncQueues.selectedTaskEntities(neverSent: Set<String> = emptySet()) =
        selectedTaskOperations.map { PendingSelectedTaskOperationEntity.from(it, it.id in neverSent) }
}
