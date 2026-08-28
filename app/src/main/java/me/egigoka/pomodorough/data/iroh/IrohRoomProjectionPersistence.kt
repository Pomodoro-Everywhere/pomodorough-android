package me.egigoka.pomodorough.data.iroh

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import me.egigoka.pomodorough.data.CoreProjectionBase
import me.egigoka.pomodorough.data.CoreProjectionDispatcher
import me.egigoka.pomodorough.data.CoreProjectionPending
import me.egigoka.pomodorough.data.CoreProjectionResult
import me.egigoka.pomodorough.data.DeviceOperation
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohRecordsDao
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohRoomMetadataDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot

internal class IrohRoomProjectionPersistence(
    private val rooms: IrohRoomMetadataDao,
    private val records: IrohRecordsDao,
    sharedCoreDispatch: (String, String) -> JsonElement,
    private val currentTimeMillis: () -> Long,
) {
    private val coreProjection = CoreProjectionDispatcher(sharedCoreDispatch)

    suspend fun project(
        roomId: String,
        records: List<IrohOperationEntity>? = null,
        baseSnapshot: LocalWorkspaceSnapshot? = null,
    ): IrohRoomProjection {
        val input = loadProjectionInput(roomId, records, baseSnapshot)
        val selectedTaskId = if (baseSnapshot != null || input.room.activated) {
            input.base.local.selectedTaskId
        } else {
            input.genesis.selectedTaskId
        }
        val timerOperations = timerOperations(input.operations)
        val projection = projectOperations(input, selectedTaskId, timerOperations)
        val retainedCommands = retainedCommands(input.base, input.operations, projection)
        val local = projectedLocalState(input, projection)
        val snapshot = input.base.copy(
            local = local,
            commands = retainedCommands,
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
            bootstrapResolution = null,
        )
        return IrohRoomProjection(snapshot, input.storedRecords.size)
    }

    private data class RoomProjectionInput(
        val room: IrohRoomEntity,
        val storedRecords: List<IrohOperationEntity>,
        val genesisRecord: IrohOperationRecord,
        val genesis: IrohGenesis,
        val base: LocalWorkspaceSnapshot,
        val operations: List<IrohOperationRecord>,
    )

    private suspend fun loadProjectionInput(
        roomId: String,
        records: List<IrohOperationEntity>?,
        baseSnapshot: LocalWorkspaceSnapshot?,
    ): RoomProjectionInput {
        val storedRecords = records ?: this.records.irohOperations(roomId)
        validateRecordSet(storedRecords)
        val room = checkNotNull(rooms.irohRoom(roomId)) { "Iroh room is missing" }
        val parsed = storedRecords.map(IrohOperationEntity::toIrohRecord)
        val genesisRecord = parsed.single { it.domain == IrohDomain.genesis }
        return RoomProjectionInput(
            room = room,
            storedRecords = storedRecords,
            genesisRecord = genesisRecord,
            genesis = genesisRecord.decodeOperation(),
            base = baseSnapshot ?: WorkspaceCodec.decode(room.roomStateJson),
            operations = parsed.filter { it.domain != IrohDomain.genesis }
                .sortedWith(IrohOperationRecord.canonicalComparator),
        )
    }

    private fun timerOperations(operations: List<IrohOperationRecord>) = operations
        .filter { it.domain == IrohDomain.timer }
        .map { record -> DeviceOperation(record.deviceId, record.decodeOperation<TimerCommand>()) }

    private fun projectOperations(
        input: RoomProjectionInput,
        selectedTaskId: String?,
        timerOperations: List<DeviceOperation<TimerCommand>>,
    ): CoreProjectionResult = coreProjection.apply(
        base = CoreProjectionBase(
            canonicalTimer = input.genesis.canonicalTimer,
            history = input.genesis.history,
            tasks = input.genesis.tasks,
            durationsMs = input.genesis.durationsMs,
            autoStartBreaks = input.genesis.autoStartBreaks,
            selectedTaskId = selectedTaskId,
        ),
        pending = pendingOperations(input.operations, timerOperations),
        now = Instant.ofEpochMilli(currentTimeMillis()),
    ).withTimerProvenance(
        starterByTimerId = timerStarters(input, timerOperations),
        deviceByCommandId = timerOperations.associate { it.value.id to it.deviceId },
    )

    private fun pendingOperations(
        operations: List<IrohOperationRecord>,
        timerOperations: List<DeviceOperation<TimerCommand>>,
    ) = CoreProjectionPending(
        commands = timerOperations,
        taskOperations = operationsFor<TaskOperation>(operations, IrohDomain.task),
        durationOperations = operationsFor<DurationOperation>(operations, IrohDomain.duration),
        autoStartOperations = operations.filter { it.domain == IrohDomain.autoStart }.map { record ->
            DeviceOperation(record.deviceId, record.decodeAutoStart())
        },
        selectedTaskOperations = operationsFor<SelectedTaskOperation>(operations, IrohDomain.selectedTask),
    )

    private inline fun <reified T> operationsFor(
        operations: List<IrohOperationRecord>,
        domain: IrohDomain,
    ): List<DeviceOperation<T>> = operations.filter { it.domain == domain }.map { record ->
        DeviceOperation(record.deviceId, record.decodeOperation<T>())
    }

    private fun timerStarters(
        input: RoomProjectionInput,
        timerOperations: List<DeviceOperation<TimerCommand>>,
    ) = buildMap {
        input.genesis.history.forEach { item -> put(item.timerId, input.genesisRecord.deviceId) }
        input.genesis.canonicalTimer?.let { timer ->
            put(timer.id, timer.startedByDeviceId ?: input.genesisRecord.deviceId)
        }
        timerOperations.filter { it.value.type == me.egigoka.pomodorough.data.CommandType.Start }
            .forEach { operation -> put(operation.value.timerId, operation.deviceId) }
    }

    private fun retainedCommands(
        base: LocalWorkspaceSnapshot,
        operations: List<IrohOperationRecord>,
        projection: CoreProjectionResult,
    ): List<me.egigoka.pomodorough.data.local.PendingCommandEntity> {
        val timerRecordIds = operations.asSequence()
            .filter { it.domain == IrohDomain.timer }.map { it.id }.toSet()
        val completedCommandIds = projection.history.asSequence()
            .filter {
                it.status == me.egigoka.pomodorough.data.TimerStatus.Completed &&
                    it.phase == me.egigoka.pomodorough.data.TimerPhase.Focus
            }
            .mapNotNull { it.commandId }.toSet()
        return base.commands.filter { command ->
            val dependency = command.generatedByFinishCommandId
            dependency == null || dependency !in timerRecordIds || dependency in completedCommandIds
        }
    }

    private fun projectedLocalState(
        input: RoomProjectionInput,
        projection: CoreProjectionResult,
    ): LocalStateEntity {
        val maximumClock = maximumClock(input)
        val settings = IrohJson.strict.decodeFromString<TimerSettings>(input.base.local.settingsJson)
            .withDurations(projection.durationsMs).copy(autoStartBreaks = projection.autoStartBreaks)
        return input.base.local.copy(
            deviceSequence = maximumDeviceSequence(input),
            hlcWallMs = maximumClock.first,
            hlcCounter = maximumClock.second,
            revision = 0,
            canonicalTimerJson = projection.canonicalTimer?.let(IrohJson.strict::encodeToString),
            historyJson = IrohJson.strict.encodeToString(canonicalRoomHistory(projection.history)),
            settingsJson = IrohJson.strict.encodeToString(settings),
            tasksJson = IrohJson.strict.encodeToString(projection.tasks),
            knownTasksJson = IrohJson.strict.encodeToString(knownTasks(input)),
            selectedTaskId = projection.selectedTaskId,
            canonicalAutoStartBreaks = projection.autoStartBreaks,
            serverClockOffsetMs = null,
            serverClockUncertaintyMs = null,
            serverClockSamplePhysicalMs = null,
            serverClockSampleElapsedRealtimeMs = null,
            serverClockBootId = null,
        )
    }

    private fun maximumClock(input: RoomProjectionInput): Pair<Long, Long> =
        (input.operations.map { it.hlcWallMs to it.hlcCounter } + listOf(
            input.genesis.hlcWallMs to input.genesis.hlcCounter,
            input.base.local.hlcWallMs to input.base.local.hlcCounter,
        )).maxWith(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))

    private fun maximumDeviceSequence(input: RoomProjectionInput): Long = maxOf(
        input.base.local.deviceSequence,
        input.operations.filter { it.deviceId == input.base.local.deviceId }
            .mapNotNull { it.deviceSequence }.maxOrNull() ?: 0L,
    )

    private fun knownTasks(input: RoomProjectionInput): List<FocusTask> {
        val tasks = input.genesis.tasks.associateByTo(linkedMapOf(), FocusTask::id)
        input.operations.filter { it.domain == IrohDomain.task }.forEach { record ->
            val operation = record.decodeOperation<TaskOperation>()
            if (operation.type == me.egigoka.pomodorough.data.TaskOperationType.Upsert) {
                tasks[operation.taskId] = FocusTask(operation.taskId, checkNotNull(operation.title))
            }
        }
        return tasks.values.toList()
    }

    fun genesis(snapshot: LocalWorkspaceSnapshot): IrohGenesis {
        val local = snapshot.local
        val projection = projectSnapshot(snapshot)
        return IrohGenesis(
            canonicalTimer = projection.canonicalTimer,
            history = canonicalRoomHistory(projection.history),
            tasks = projection.tasks,
            durationsMs = projection.durationsMs,
            autoStartBreaks = projection.autoStartBreaks,
            selectedTaskId = projection.selectedTaskId,
            hlcWallMs = local.hlcWallMs,
            hlcCounter = local.hlcCounter,
        )
    }

    private fun projectSnapshot(snapshot: LocalWorkspaceSnapshot): CoreProjectionResult {
        val local = snapshot.local
        val settings = IrohJson.strict.decodeFromString<TimerSettings>(local.settingsJson)
        val baseTimer = local.canonicalTimerJson?.let {
            IrohJson.strict.decodeFromString<me.egigoka.pomodorough.data.CanonicalTimer>(it)
        }
        val timerOperations = snapshot.commands.map { DeviceOperation(local.deviceId, it.toModel()) }
        return coreProjection.apply(
            base = CoreProjectionBase(
                canonicalTimer = baseTimer,
                history = IrohJson.strict.decodeFromString(local.historyJson),
                tasks = IrohJson.strict.decodeFromString(local.tasksJson),
                durationsMs = settings.effectiveDurationsMs(),
                autoStartBreaks = settings.autoStartBreaks,
                selectedTaskId = local.selectedTaskId,
            ),
            pending = CoreProjectionPending(
                commands = timerOperations,
                taskOperations = snapshot.taskOperations.map { DeviceOperation(local.deviceId, it.toModel()) },
                durationOperations = snapshot.durationOperations.map { DeviceOperation(local.deviceId, it.toModel()) },
                autoStartOperations = snapshot.autoStartOperations.map { DeviceOperation(it.deviceId, it.toModel()) },
                selectedTaskOperations = snapshot.selectedTaskOperations.map {
                    DeviceOperation(local.deviceId, it.toModel())
                },
            ),
            now = Instant.ofEpochMilli(currentTimeMillis()),
        ).withTimerProvenance(
            starterByTimerId = buildMap {
                baseTimer?.let { timer ->
                    val origin = timer.startedByDeviceId ?: local.deviceId.takeIf { local.ownedTimerId == timer.id }
                    origin?.let { put(timer.id, it) }
                }
                timerOperations.forEach { operation ->
                    if (operation.value.type == me.egigoka.pomodorough.data.CommandType.Start) {
                        put(operation.value.timerId, operation.deviceId)
                    }
                }
            },
            deviceByCommandId = timerOperations.associate { it.value.id to it.deviceId },
        )
    }

    private fun CoreProjectionResult.withTimerProvenance(
        starterByTimerId: Map<String, String>,
        deviceByCommandId: Map<String, String>,
    ): CoreProjectionResult = copy(
        canonicalTimer = canonicalTimer?.let { timer ->
            timer.copy(
                startedByDeviceId = timer.startedByDeviceId ?: starterByTimerId[timer.id],
                lastIntent = timer.lastIntent?.let { intent ->
                    intent.copy(deviceId = intent.deviceId ?: deviceByCommandId[intent.commandId])
                },
            )
        },
    )

    fun makeRoomDeviceState(source: LocalWorkspaceSnapshot): LocalWorkspaceSnapshot {
        val projection = projectSnapshot(source)
        val base = clearedRoomDeviceState(source)
        val settings = IrohJson.strict.decodeFromString<TimerSettings>(source.local.settingsJson)
            .withDurations(projection.durationsMs)
            .copy(autoStartBreaks = projection.autoStartBreaks)
        val knownTasks = IrohJson.strict.decodeFromString<List<FocusTask>>(source.local.knownTasksJson)
            .associateByTo(linkedMapOf(), FocusTask::id)
        source.taskOperations.forEach { operation ->
            if (operation.type == me.egigoka.pomodorough.data.TaskOperationType.Upsert) {
                knownTasks[operation.taskId] = FocusTask(operation.taskId, checkNotNull(operation.title))
            }
        }
        return base.copy(local = base.local.copy(
            canonicalTimerJson = projection.canonicalTimer?.let(IrohJson.strict::encodeToString),
            historyJson = IrohJson.strict.encodeToString(canonicalRoomHistory(projection.history)),
            settingsJson = IrohJson.strict.encodeToString(settings),
            tasksJson = IrohJson.strict.encodeToString(projection.tasks),
            knownTasksJson = IrohJson.strict.encodeToString(knownTasks.values.toList()),
            selectedTaskId = projection.selectedTaskId,
            canonicalAutoStartBreaks = projection.autoStartBreaks,
        ))
    }

    private fun clearedRoomDeviceState(source: LocalWorkspaceSnapshot): LocalWorkspaceSnapshot = source.copy(
        local = source.local.copy(
            revision = 0,
            serverClockOffsetMs = null,
            serverClockUncertaintyMs = null,
            serverClockSamplePhysicalMs = null,
            serverClockSampleElapsedRealtimeMs = null,
            serverClockBootId = null,
        ),
        commands = emptyList(),
        taskOperations = emptyList(),
        durationOperations = emptyList(),
        autoStartOperations = emptyList(),
        selectedTaskOperations = emptyList(),
        bootstrapResolution = null,
    )

    fun makeGenesisState(
        source: LocalWorkspaceSnapshot,
        genesis: IrohGenesis,
    ): LocalWorkspaceSnapshot {
        val base = clearedRoomDeviceState(source)
        val settings = IrohJson.strict.decodeFromString<TimerSettings>(source.local.settingsJson)
            .withDurations(genesis.durationsMs)
            .copy(autoStartBreaks = genesis.autoStartBreaks)
        return base.copy(
            local = base.local.copy(
                canonicalTimerJson = genesis.canonicalTimer?.let(IrohJson.strict::encodeToString),
                historyJson = IrohJson.strict.encodeToString(canonicalRoomHistory(genesis.history)),
                tasksJson = IrohJson.strict.encodeToString(genesis.tasks),
                knownTasksJson = IrohJson.strict.encodeToString(genesis.tasks),
                settingsJson = IrohJson.strict.encodeToString(settings),
                canonicalAutoStartBreaks = genesis.autoStartBreaks,
                selectedTaskId = genesis.selectedTaskId,
                hlcWallMs = genesis.hlcWallMs,
                hlcCounter = genesis.hlcCounter,
            ),
        )
    }

    private fun canonicalRoomHistory(history: List<me.egigoka.pomodorough.data.HistoryItem>) =
        history.distinctBy { it.timerId }
            .map { item -> item.copy(pending = false) }
            .sortedWith(
                compareByDescending<me.egigoka.pomodorough.data.HistoryItem> {
                    (it.endedAt ?: it.completedAt)?.let(Instant::parse) ?: Instant.EPOCH
                }
                    .thenBy { it.timerId },
            )

    fun eligibleRoomCommands(
        snapshot: LocalWorkspaceSnapshot,
    ): List<me.egigoka.pomodorough.data.local.PendingCommandEntity> {
        val commands = snapshot.commands
        val stored = commands.associateBy { it.id }
        val history = IrohJson.strict.decodeFromString<List<me.egigoka.pomodorough.data.HistoryItem>>(
            snapshot.local.historyJson,
        )
        val acceptedDependencies = history.asSequence()
            .filter {
                it.status == me.egigoka.pomodorough.data.TimerStatus.Completed &&
                    it.phase == me.egigoka.pomodorough.data.TimerPhase.Focus
            }
            .mapNotNull { it.commandId }
            .toSet()
        return commands.filter { command ->
            val dependency = command.generatedByFinishCommandId ?: return@filter true
            dependency !in stored && dependency in acceptedDependencies
        }
    }

    private fun validateRecordSet(records: List<IrohOperationEntity>) {
        require(records.count { it.domain == IrohDomain.genesis.name && it.operationId == "genesis" } == 1) {
            "Iroh room genesis is missing or conflicting"
        }
        require(records.map { it.domain to it.operationId }.toSet().size == records.size)
        records.map(IrohOperationEntity::toIrohRecord).forEach(IrohOperationRecord::validate)
        val sequences = records.filter { it.domain == IrohDomain.timer.name }.mapNotNull { operation ->
            operation.deviceSequence?.let { Triple(operation.originDeviceId, it, operation.operationId) }
        }
        require(sequences.map { it.first to it.second }.toSet().size == sequences.size) {
            "Iroh device sequence is reused"
        }
    }
}
