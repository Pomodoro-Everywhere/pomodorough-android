package me.egigoka.pomodorough.data

import java.util.UUID
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
    val bootstrapResolution: PendingBootstrapResolutionEntity?,
    val decoded: DecodedLocalJson,
)

internal class LocalDecodingException(
    val local: LocalStateEntity,
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
        val stored = workspace.localState()
        val local = stored ?: createLocalState()
        val commandEntities = workspace.pendingCommands()
        return LocalInitializationData(
            storedLocal = stored,
            local = local,
            commandEntities = commandEntities,
            commands = commandEntities.map(PendingCommandEntity::toModel),
            commandDependencies = commandDependencies(commandEntities),
            durationOperations = workspace.pendingDurationOperations()
                .map(PendingDurationOperationEntity::toModel),
            taskOperations = workspace.pendingTaskOperations().map(PendingTaskOperationEntity::toModel),
            autoStartOperations = workspace.pendingAutoStartOperations()
                .map(PendingAutoStartOperationEntity::toModel),
            selectedTaskOperations = workspace.pendingSelectedTaskOperations()
                .map(PendingSelectedTaskOperationEntity::toModel),
            bootstrapResolution = bootstrap.pendingBootstrapResolution(),
            decoded = try {
                decode(local)
            } catch (error: Exception) {
                throw LocalDecodingException(local, error)
            },
        )
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

    private fun decode(local: LocalStateEntity): DecodedLocalJson = DecodedLocalJson(
        settings = json.decodeFromString(local.settingsJson),
        canonicalTimer = local.canonicalTimerJson?.let(json::decodeFromString),
        history = json.decodeFromString(local.historyJson),
        tasks = json.decodeFromString(local.tasksJson),
        knownTasks = json.decodeFromString(local.knownTasksJson),
        user = local.userJson?.let(::decodeUser),
    )

    private fun decodeUser(raw: String): User = requireNotNull(
        strictJson.decodeFromString<User?>(raw),
    ) { "Persisted account JSON is null" }.also(validateUser)
}
