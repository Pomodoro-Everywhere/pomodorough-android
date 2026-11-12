package me.egigoka.pomodorough.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.local.BootstrapDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.TimerWorkspaceDao
import me.egigoka.pomodorough.data.local.loadAutoStartOperationsBounded
import me.egigoka.pomodorough.data.local.loadCommandsBounded
import me.egigoka.pomodorough.data.local.loadDurationOperationsBounded
import me.egigoka.pomodorough.data.local.loadSelectedTaskOperationsBounded
import me.egigoka.pomodorough.data.local.loadTaskOperationsBounded

internal data class DecodedLocalJson(
    val settings: TimerSettings,
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val knownTasks: List<FocusTask>,
    val user: User?,
)

internal data class LocalInitializationData(
    val storedLocal: LocalStateEntity?,
    val local: LocalStateEntity,
    val commandEntities: List<PendingCommandEntity>,
    val commands: List<TimerCommand>,
    val commandDependencies: Map<String, String>,
    val durationOperations: List<DurationOperation>,
    val taskOperations: List<TaskOperation>,
    val autoStartOperations: List<AutoStartOperation>,
    val selectedTaskOperations: List<SelectedTaskOperation>,
    val neverSent: CoreNeverSentProof,
    val bootstrapResolution: PendingBootstrapResolutionEntity?,
    val decoded: DecodedLocalJson,
)

internal class LocalDecodingException(
    val local: LocalStateEntity,
    cause: Exception,
) : Exception(cause)

/** Storage stage whose read failed during local initialization; names one bounded diagnostic. */
internal enum class LocalStorageReadStage(val category: CorruptStateCategory) {
    WorkspaceState(CorruptStateCategory.WorkspaceStateRead),
    PendingQueue(CorruptStateCategory.PendingQueueRead),
    BootstrapResolution(CorruptStateCategory.BootstrapResolutionRead),
}

/** Storage access failed before decoding; persisted rows are untouched and the load may be retried. */
internal class LocalStorageReadException(
    val stage: LocalStorageReadStage,
    cause: Exception,
) : Exception(cause)

internal class TimerLocalInitializer(
    private val workspace: TimerWorkspaceDao,
    private val bootstrap: BootstrapDao,
    private val json: Json,
    private val strictJson: Json,
    private val validateUser: (User) -> Unit,
) {
    suspend fun load(): LocalInitializationData {
        val stored = read(LocalStorageReadStage.WorkspaceState) { workspace.localState() }
        val local = stored ?: read(LocalStorageReadStage.WorkspaceState) { createLocalState() }
        val queue = LocalStorageReadStage.PendingQueue
        val commandEntities = read(queue) { workspace.loadCommandsBounded() }
        val taskEntities = read(queue) { workspace.loadTaskOperationsBounded() }
        val durationEntities = read(queue) { workspace.loadDurationOperationsBounded() }
        val autoStartEntities = read(queue) { workspace.loadAutoStartOperationsBounded() }
        val selectedEntities = read(queue) { workspace.loadSelectedTaskOperationsBounded() }
        val bootstrapResolution = read(LocalStorageReadStage.BootstrapResolution) {
            bootstrap.pendingBootstrapResolution()
        }
        return LocalInitializationData(
            storedLocal = stored,
            local = local,
            commandEntities = commandEntities,
            commands = commandEntities.map(PendingCommandEntity::toModel),
            commandDependencies = commandDependencies(commandEntities),
            durationOperations = durationEntities.map(PendingDurationOperationEntity::toModel),
            taskOperations = taskEntities.map(PendingTaskOperationEntity::toModel),
            autoStartOperations = autoStartEntities.map(PendingAutoStartOperationEntity::toModel),
            selectedTaskOperations = selectedEntities.map(PendingSelectedTaskOperationEntity::toModel),
            neverSent = CoreNeverSentProof(
                commands = commandEntities.filter { it.neverSent }.map { it.id },
                taskOperations = taskEntities.filter { it.neverSent }.map { it.id },
                durationOperations = durationEntities.filter { it.neverSent }.map { it.id },
                autoStartOperations = autoStartEntities.filter { it.neverSent }.map { it.id },
                selectedTaskOperations = selectedEntities.filter { it.neverSent }.map { it.id },
            ),
            bootstrapResolution = bootstrapResolution,
            decoded = try {
                decode(local)
            } catch (error: CancellationException) {
                // Guard-first: decode is pure today; future suspension must propagate.
                throw error
            } catch (error: Exception) {
                throw LocalDecodingException(local, error)
            },
        )
    }

    private suspend fun <T> read(stage: LocalStorageReadStage, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw LocalStorageReadException(stage, error)
    }

    private suspend fun createLocalState(): LocalStateEntity = LocalStateEntity(
        deviceId = UUID.randomUUID().toString(),
        settingsJson = json.encodeToString(TimerSettings()),
    ).also { workspace.insertState(it) }

    private fun commandDependencies(
        entities: List<PendingCommandEntity>,
    ): Map<String, String> = entities.mapNotNull { entity ->
        entity.generatedByFinishCommandId?.let { entity.id to it }
    }.toMap()

    private fun decode(local: LocalStateEntity): DecodedLocalJson {
        require(local.syncNextDomain in CoreBatchPlanDispatcher.Domains) { "Sync batch cursor is invalid" }
        return DecodedLocalJson(
            settings = json.decodeFromString(local.settingsJson),
            canonicalTimer = local.canonicalTimerJson?.let(json::decodeFromString),
            history = json.decodeFromString(local.historyJson),
            tasks = json.decodeFromString(local.tasksJson),
            knownTasks = json.decodeFromString(local.knownTasksJson),
            user = local.userJson?.let(::decodeUser),
        )
    }

    private fun decodeUser(raw: String): User = requireNotNull(
        strictJson.decodeFromString<User?>(raw),
    ) { "Persisted account JSON is null" }.also(validateUser)
}
