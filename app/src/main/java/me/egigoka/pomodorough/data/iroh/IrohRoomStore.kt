package me.egigoka.pomodorough.data.iroh

import android.database.sqlite.SQLiteConstraintException
import java.security.SecureRandom
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohConflictEntity
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.domain.SettingsReducer
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.TimerReducer

data class IrohRoomProjection(
    val snapshot: LocalWorkspaceSnapshot,
    val operationCount: Int,
)

class IrohRoomStore(
    private val dao: TimerDao,
    private val vault: IrohSecretVault,
    private val workspaceCoordinator: LocalWorkspaceCoordinator = LocalWorkspaceCoordinator(),
    private val random: SecureRandom = SecureRandom(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    internal val coordinator: LocalWorkspaceCoordinator
        get() = workspaceCoordinator

    suspend fun replicationSettings(): ReplicationSettingsEntity =
        dao.replicationSettings() ?: ReplicationSettingsEntity(mode = ReplicationMode.CENTRALIZED.name)
            .also { dao.upsertReplicationSettings(it) }

    suspend fun activeRoom(): IrohRoomEntity? = replicationSettings().activeRoomId?.let { roomId ->
        dao.irohRoom(roomId)
    }

    suspend fun activeRoomSecret(): ByteArray? = activeRoom()?.let { room ->
        vault.decryptRoomSecret(room.roomId, room.encryptedRoomSecret)
    }

    suspend fun discardIncompleteRooms() {
        val settings = replicationSettings()
        val activeRoomId = settings.activeRoomId
        if (activeRoomId != null && !hasGenesis(activeRoomId)) {
            dao.upsertReplicationSettings(ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name))
        }
        dao.deleteIncompleteIrohRooms()
    }

    suspend fun createRoom(name: String?): Pair<IrohRoomEntity, IrohRoomProjection> =
        workspaceCoordinator.withLock { createRoomLocked(name) }

    private suspend fun createRoomLocked(name: String?): Pair<IrohRoomEntity, IrohRoomProjection> {
        require(IrohProtocolV1.isDisplayName(name)) {
            "Room name must contain 1 through 64 Unicode scalars"
        }
        val returnState = dao.localWorkspaceSnapshot()
        val secret = ByteArray(32).also(random::nextBytes)
        val roomId = IrohProtocolV1.roomId(secret)
        return try {
            val genesis = genesis(returnState)
            val genesisRecord = IrohOperationRecord.genesis(returnState.local.deviceId, genesis).also {
                it.validate()
            }
            val roomState = makeGenesisState(returnState, genesis)
            val room = IrohRoomEntity(
                roomId = roomId,
                roomName = name,
                encryptedRoomSecret = vault.encryptRoomSecret(roomId, secret),
                returnStateJson = WorkspaceCodec.encode(returnState),
                roomStateJson = WorkspaceCodec.encode(roomState),
                createdAtMs = currentTimeMillis(),
                activated = true,
            )
            dao.createIrohRoom(
                room,
                genesisRecord.toEntity(roomId),
                ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = roomId),
                roomState,
            )
            room to IrohRoomProjection(roomState, 1)
        } finally {
            secret.fill(0)
        }
    }

    suspend fun prepareJoinedRoom(
        invite: IrohRoomInvite,
        endpointId: String,
    ): Pair<IrohRoomEntity, Boolean> = workspaceCoordinator.withLock {
        require(IrohProtocolV1.isIdentifier(endpointId)) { "Endpoint ID is invalid" }
        val peer = IrohPeerEntity(
            roomId = invite.roomId,
            endpointId = endpointId,
            endpointTicket = invite.endpointTicket,
            deviceId = null,
            displayName = null,
            lastSeenAtMs = null,
        )
        dao.irohRoom(invite.roomId)?.let { existing ->
            val savedSecret = vault.decryptRoomSecret(existing.roomId, existing.encryptedRoomSecret)
            try {
                require(savedSecret.contentEquals(invite.roomSecret)) {
                    "Saved Iroh room credentials do not match invite"
                }
            } finally {
                savedSecret.fill(0)
            }
            val settings = replicationSettings()
            val alreadyActive = settings.mode == ReplicationMode.IROH.name &&
                settings.activeRoomId == existing.roomId
            val prepared = if (existing.activated && !alreadyActive) {
                existing.copy(
                    returnStateJson = WorkspaceCodec.encode(dao.localWorkspaceSnapshot()),
                )
            } else {
                existing
            }
            dao.prepareExistingJoinedIrohRoom(prepared, peer)
            return@withLock prepared to false
        }
        val returnState = dao.localWorkspaceSnapshot()
        val roomState = makeRoomDeviceState(returnState)
        val room = IrohRoomEntity(
            roomId = invite.roomId,
            roomName = invite.roomName,
            encryptedRoomSecret = vault.encryptRoomSecret(invite.roomId, invite.roomSecret),
            returnStateJson = WorkspaceCodec.encode(returnState),
            roomStateJson = WorkspaceCodec.encode(roomState),
            createdAtMs = currentTimeMillis(),
            activated = false,
        )
        dao.prepareJoinedIrohRoom(
            room,
            peer,
        )
        room to true
    }

    suspend fun activateJoinedRoom(roomId: String): IrohRoomProjection =
        workspaceCoordinator.withLock { activateJoinedRoomLocked(roomId) }

    private suspend fun activateJoinedRoomLocked(roomId: String): IrohRoomProjection {
        val settings = replicationSettings()
        val alreadyActive = settings.mode == ReplicationMode.IROH.name && settings.activeRoomId == roomId
        val projected = project(roomId)
        val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
        val updated = room.copy(
            returnStateJson = if (alreadyActive) {
                room.returnStateJson
            } else {
                WorkspaceCodec.encode(dao.localWorkspaceSnapshot())
            },
            roomStateJson = WorkspaceCodec.encode(projected.snapshot),
            activated = true,
        )
        dao.activateIrohWorkspace(
            room = updated,
            settings = ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = roomId),
            snapshot = projected.snapshot,
        )
        return projected
    }

    suspend fun activateExistingRoom(roomId: String): IrohRoomProjection =
        workspaceCoordinator.withLock { activateExistingRoomLocked(roomId) }

    private suspend fun activateExistingRoomLocked(roomId: String): IrohRoomProjection {
        val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
        require(dao.irohConflict(roomId) == null) { "Iroh room requires repair" }
        val returnState = dao.localWorkspaceSnapshot()
        val projected = project(roomId)
        val updated = room.copy(
            returnStateJson = WorkspaceCodec.encode(returnState),
            roomStateJson = WorkspaceCodec.encode(projected.snapshot),
            activated = true,
        )
        dao.activateIrohWorkspace(
            room = updated,
            settings = ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = roomId),
            snapshot = projected.snapshot,
        )
        return projected
    }

    suspend fun leaveActiveRoom(): LocalWorkspaceSnapshot = workspaceCoordinator.withLock {
        val settings = replicationSettings()
        val roomId = checkNotNull(settings.activeRoomId) { "No Iroh room is active" }
        val room = checkNotNull(dao.irohRoom(roomId)) { "Active Iroh room is missing" }
        val restored = WorkspaceCodec.decode(room.returnStateJson)
        val updated = room.copy(roomStateJson = WorkspaceCodec.encode(dao.localWorkspaceSnapshot()))
        dao.restoreFromIrohWorkspace(
            room = updated,
            settings = ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name),
            snapshot = restored,
        )
        restored
    }

    suspend fun setMode(mode: ReplicationMode): LocalWorkspaceSnapshot? =
        workspaceCoordinator.withLock {
        val current = replicationSettings()
        if (mode == ReplicationMode.IROH) {
            val roomId = current.activeRoomId ?: dao.preferredIrohRoom()?.roomId
                ?: throw IllegalStateException("Create or join an Iroh room before selecting Iroh mode")
            return@withLock activateExistingRoomLocked(roomId).snapshot
        }
        if (current.mode == ReplicationMode.IROH.name && current.activeRoomId != null) {
            val room = checkNotNull(dao.irohRoom(current.activeRoomId)) { "Active Iroh room is missing" }
            val restored = WorkspaceCodec.decode(room.returnStateJson)
            val updated = room.copy(roomStateJson = WorkspaceCodec.encode(dao.localWorkspaceSnapshot()))
            dao.restoreFromIrohWorkspace(
                room = updated,
                settings = ReplicationSettingsEntity(mode = mode.name),
                snapshot = restored,
            )
            return@withLock restored
        }
        dao.upsertReplicationSettings(ReplicationSettingsEntity(mode = mode.name))
        null
    }

    suspend fun captureLocalOperations(): IrohRoomProjection =
        workspaceCoordinator.withLock { captureLocalOperationsLocked() }

    private suspend fun captureLocalOperationsLocked(): IrohRoomProjection {
        val room = checkNotNull(activeRoom()) { "No Iroh room is active" }
        require(dao.irohConflict(room.roomId) == null) { "Iroh room requires repair" }
        val current = dao.localWorkspaceSnapshot()
        val pendingCommands = eligibleRoomCommands(current)
        val records = buildList {
            pendingCommands.forEach { add(IrohOperationRecord.timer(current.local.deviceId, it.toModel())) }
            current.taskOperations.forEach { add(IrohOperationRecord.task(current.local.deviceId, it.toModel())) }
            current.durationOperations.forEach {
                add(IrohOperationRecord.duration(current.local.deviceId, it.toModel()))
            }
            current.autoStartOperations.forEach {
                add(IrohOperationRecord.autoStart(current.local.deviceId, it.toModel()))
            }
        }.onEach(IrohOperationRecord::validate)
        val capturedIds = pendingCommands.mapTo(mutableSetOf()) { it.id }
        val cleared = current.copy(
            commands = current.commands.filterNot { it.id in capturedIds },
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            bootstrapResolution = null,
        )
        val staged = records.map { it.toEntity(room.roomId) }
        val updatedRoom = room.copy(roomStateJson = WorkspaceCodec.encode(cleared))
        if (staged.isNotEmpty()) {
            val newEntities = staged.filter { candidate ->
                val stored = dao.irohOperation(room.roomId, candidate.domain, candidate.operationId)
                    ?: return@filter true
                if (stored.digest != candidate.digest) {
                    saveConflict(room.roomId, stored, candidate.digest)
                    throw IllegalStateException("Immutable Iroh operation conflict")
                }
                false
            }
            dao.captureIrohOperations(updatedRoom, newEntities, cleared)
        } else {
            dao.activateIrohWorkspace(
                updatedRoom,
                ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = room.roomId),
                cleared,
            )
        }
        val projected = project(room.roomId, baseSnapshot = cleared)
        dao.activateIrohWorkspace(
            updatedRoom.copy(roomStateJson = WorkspaceCodec.encode(projected.snapshot)),
            ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = room.roomId),
            projected.snapshot,
        )
        return if (eligibleRoomCommands(projected.snapshot).isNotEmpty()) {
            captureLocalOperationsLocked()
        } else {
            projected
        }
    }

    suspend fun insertRemoteRecords(roomId: String, records: List<IrohOperationRecord>) =
        workspaceCoordinator.withLock {
        require(records.isNotEmpty() && records.size <= IrohProtocolV1.MaxOperationReferences) {
            "Iroh operation batch is invalid"
        }
        require(records.map { it.domain to it.id }.toSet().size == records.size) {
            "Iroh operation batch contains duplicate references"
        }
        records.forEach(IrohOperationRecord::validate)
        val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
        require(dao.irohConflict(roomId) == null) { "Iroh room requires repair" }
        val existing = records.mapNotNull { record ->
            dao.irohOperation(roomId, record.domain.name, record.id)?.let { entity ->
                (record.domain.name to record.id) to entity
            }
        }.toMap()
        for (record in records) {
            val stored = existing[record.domain.name to record.id] ?: continue
            if (stored.digest != record.digest()) {
                saveConflict(roomId, stored, record.digest())
                throw IllegalStateException("Immutable Iroh operation conflict")
            }
        }
        val newEntities = records.filter { (it.domain.name to it.id) !in existing }.map {
            it.toEntity(roomId)
        }
        try {
            dao.insertIrohRecordsAtomically(newEntities)
        } catch (error: SQLiteConstraintException) {
            throw IllegalArgumentException("Iroh device sequence is reused", error)
        }
    }

    suspend fun refreshProjection(roomId: String): IrohRoomProjection =
        workspaceCoordinator.withLock {
            val settings = replicationSettings()
            val active = settings.mode == ReplicationMode.IROH.name && settings.activeRoomId == roomId
            val base = if (active) captureLocalOperationsLocked().snapshot else null
            val projected = project(roomId, baseSnapshot = base)
            val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
            val updated = room.copy(roomStateJson = WorkspaceCodec.encode(projected.snapshot))
            if (active) {
                dao.activateIrohWorkspace(updated, settings, projected.snapshot)
            } else {
                dao.updateIrohRoom(updated)
            }
            projected
        }

    suspend fun inventory(roomId: String, after: String?, limit: Int): Pair<List<IrohInventoryEntry>, String?> {
        require(limit in 1..IrohProtocolV1.MaxInventoryEntries)
        val (afterDomain, afterId) = after?.let(::parseCursor) ?: (null to null)
        val loaded = dao.irohOperationPage(roomId, afterDomain, afterId, limit + 1)
        val entries = loaded.take(limit).map { operation ->
            IrohInventoryEntry(IrohDomain.valueOf(operation.domain), operation.operationId, operation.digest)
        }
        val next = entries.lastOrNull()?.takeIf { loaded.size > limit }?.let(::cursor)
        return entries to next
    }

    suspend fun operations(
        roomId: String,
        references: List<IrohInventoryReference>,
    ): List<IrohOperationRecord> {
        require(references.isNotEmpty() && references.size <= IrohProtocolV1.MaxOperationReferences &&
            references.toSet().size == references.size
        )
        return references.map { reference ->
            dao.irohOperation(roomId, reference.domain.name, reference.id)?.toRecord()
                ?: throw NoSuchElementException("Iroh operation was not found")
        }
    }

    suspend fun missingReferences(
        roomId: String,
        remote: List<IrohInventoryEntry>,
    ): List<IrohInventoryReference> {
        require(remote.size <= IrohProtocolV1.MaxInventoryEntries)
        return remote.mapNotNull { entry ->
            val stored = dao.irohOperation(roomId, entry.domain.name, entry.id)
                ?: return@mapNotNull entry.reference
            if (stored.digest != entry.digest) {
                saveConflict(roomId, stored, entry.digest)
                throw IllegalStateException("Immutable Iroh operation conflict")
            }
            null
        }
    }

    suspend fun upsertPeer(peer: IrohPeerEntity) {
        require(peer.endpointTicket.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes &&
            IrohProtocolV1.isDisplayName(peer.displayName)
        )
        dao.upsertIrohPeerBounded(peer, IrohProtocolV1.MaxPeers)
    }

    suspend fun peers(roomId: String): List<IrohPeerEntity> = dao.irohPeers(roomId)

    suspend fun hasGenesis(roomId: String): Boolean = dao.hasIrohGenesis(roomId) == 1

    suspend fun snapshot(roomId: String): IrohNetworkState {
        val settings = replicationSettings()
        val room = dao.irohRoom(roomId)
        val conflict = dao.irohConflict(roomId)?.toEvidence()
        return IrohNetworkState(
            mode = ReplicationMode.valueOf(settings.mode),
            status = if (conflict == null) IrohConnectionStatus.STOPPED else IrohConnectionStatus.CONFLICT,
            roomId = room?.roomId,
            roomName = room?.roomName,
            peerCount = dao.irohPeers(roomId).size,
            operationCount = dao.irohOperations(roomId).size,
            conflict = conflict,
        )
    }

    suspend fun discardIncompleteInactiveRoom(roomId: String) {
        require(replicationSettings().activeRoomId != roomId) { "Active Iroh room cannot be discarded" }
        if (!hasGenesis(roomId) && dao.irohConflict(roomId) == null) dao.deleteIrohRoom(roomId)
    }

    suspend fun clearAccountData() = workspaceCoordinator.withLock {
        val settings = replicationSettings()
        require(settings.mode == ReplicationMode.IROH.name) { "Iroh room is not active" }
        val roomId = checkNotNull(settings.activeRoomId) { "No Iroh room is active" }
        val room = checkNotNull(dao.irohRoom(roomId)) { "Active Iroh room is missing" }
        val current = dao.localWorkspaceSnapshot()
        val clearedCurrent = current.copy(
            local = current.local.withoutAccount(preserveDomain = true),
            bootstrapResolution = null,
        )
        val returned = WorkspaceCodec.decode(room.returnStateJson)
        val preserveReturnDomain = returned.local.ownerUserId == null
        val clearedReturn = returned.copy(
            local = returned.local.withoutAccount(preserveDomain = preserveReturnDomain),
            commands = returned.commands.takeIf { preserveReturnDomain }.orEmpty(),
            taskOperations = returned.taskOperations.takeIf { preserveReturnDomain }.orEmpty(),
            durationOperations = returned.durationOperations.takeIf { preserveReturnDomain }.orEmpty(),
            autoStartOperations = returned.autoStartOperations.takeIf { preserveReturnDomain }.orEmpty(),
            bootstrapResolution = null,
        )
        dao.activateIrohWorkspace(
            room.copy(
                returnStateJson = WorkspaceCodec.encode(clearedReturn),
                roomStateJson = WorkspaceCodec.encode(clearedCurrent),
            ),
            settings,
            clearedCurrent,
        )
    }

    private suspend fun project(
        roomId: String,
        records: List<IrohOperationEntity>? = null,
        baseSnapshot: LocalWorkspaceSnapshot? = null,
    ): IrohRoomProjection {
        val storedRecords = records ?: dao.irohOperations(roomId)
        validateRecordSet(storedRecords)
        val room = checkNotNull(dao.irohRoom(roomId)) { "Iroh room is missing" }
        val parsed = storedRecords.map(IrohOperationEntity::toRecord)
        val genesisRecord = parsed.single { it.domain == IrohDomain.genesis }
        val genesis = genesisRecord.decodeOperation<IrohGenesis>()
        var timer = genesis.canonicalTimer
        var history = genesis.history
        var tasks = genesis.tasks
        val base = baseSnapshot ?: WorkspaceCodec.decode(room.roomStateJson)
        var settings = IrohJson.strict.decodeFromString<TimerSettings>(base.local.settingsJson)
            .withDurations(genesis.durationsMs).copy(
            autoStartBreaks = genesis.autoStartBreaks,
        )
        val knownTasks = genesis.tasks.associateByTo(linkedMapOf(), FocusTask::id)
        val timerStarters = mutableMapOf<String, String>()
        genesis.history.forEach { item -> timerStarters[item.timerId] = genesisRecord.deviceId }
        genesis.canonicalTimer?.let { timer ->
            timerStarters[timer.id] = timer.startedByDeviceId ?: genesisRecord.deviceId
        }
        val operations = parsed.filter { it.domain != IrohDomain.genesis }
            .sortedWith(IrohOperationRecord.canonicalComparator)
        operations.forEach { record ->
            when (record.domain) {
                IrohDomain.genesis -> error("Genesis must not enter room operation order")
                IrohDomain.timer -> TimerReducer.replayOrdered(
                    timer,
                    history,
                    listOf(record.decodeOperation<TimerCommand>()),
                ).also { projection ->
                    val command = record.decodeOperation<TimerCommand>()
                    if (command.type == me.egigoka.pomodorough.data.CommandType.Start &&
                        projection.timer?.lastIntent?.commandId == command.id
                    ) {
                        timerStarters[command.timerId] = record.deviceId
                    }
                    timer = projection.timer?.let { projected ->
                        projected.copy(
                            startedByDeviceId = timerStarters[projected.id] ?: projected.startedByDeviceId,
                            lastIntent = projected.lastIntent?.let { intent ->
                                if (intent.commandId == command.id) intent.copy(deviceId = record.deviceId) else intent
                            },
                        )
                    }
                    history = projection.history
                }
                IrohDomain.task -> {
                    val operation = record.decodeOperation<TaskOperation>()
                    tasks = TaskReducer.replay(tasks, listOf(operation))
                    operation.title?.let(TaskReducer::taskFromTitle)?.let { knownTasks[it.id] = it }
                }
                IrohDomain.duration -> settings = SettingsReducer.applyDuration(
                    settings,
                    record.decodeOperation<DurationOperation>(),
                )
                IrohDomain.autoStart -> settings = settings.copy(
                    autoStartBreaks = SettingsReducer.applyAutoStart(
                        settings.autoStartBreaks,
                        record.decodeAutoStart(),
                    ),
                )
            }
        }
        TimerReducer.projectAt(timer, history, currentTimeMillis()).also { projection ->
            timer = projection.timer
            history = projection.history
        }
        val maximumClock = (operations.map { it.hlcWallMs to it.hlcCounter } +
            listOf(genesis.hlcWallMs to genesis.hlcCounter, base.local.hlcWallMs to base.local.hlcCounter))
            .maxWith(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))
        val selectedTask = base.local.selectedTaskId?.takeIf { id -> tasks.any { it.id == id } }
        val timerRecordIds = operations.asSequence()
            .filter { it.domain == IrohDomain.timer }
            .map { it.id }
            .toSet()
        val completedCommandIds = history.asSequence()
            .filter {
                it.status == me.egigoka.pomodorough.data.TimerStatus.Completed &&
                    it.phase == me.egigoka.pomodorough.data.TimerPhase.Focus
            }
            .mapNotNull { it.commandId }
            .toSet()
        val retainedCommands = base.commands.filter { command ->
            val dependency = command.generatedByFinishCommandId
            dependency == null || dependency !in timerRecordIds || dependency in completedCommandIds
        }
        val local = base.local.copy(
            deviceSequence = maxOf(
                base.local.deviceSequence,
                operations.filter { it.deviceId == base.local.deviceId }.mapNotNull { it.deviceSequence }
                    .maxOrNull() ?: 0L,
            ),
            hlcWallMs = maximumClock.first,
            hlcCounter = maximumClock.second,
            revision = 0,
            canonicalTimerJson = timer?.let(IrohJson.strict::encodeToString),
            historyJson = IrohJson.strict.encodeToString(canonicalRoomHistory(history)),
            settingsJson = IrohJson.strict.encodeToString(settings),
            tasksJson = IrohJson.strict.encodeToString(tasks),
            knownTasksJson = IrohJson.strict.encodeToString(knownTasks.values.toList()),
            selectedTaskId = selectedTask,
            canonicalAutoStartBreaks = settings.autoStartBreaks,
            serverClockOffsetMs = null,
            serverClockUncertaintyMs = null,
            serverClockSamplePhysicalMs = null,
            serverClockSampleElapsedRealtimeMs = null,
            serverClockBootId = null,
        )
        return IrohRoomProjection(
            base.copy(
                local = local,
                commands = retainedCommands,
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                bootstrapResolution = null,
            ),
            storedRecords.size,
        )
    }

    private fun genesis(snapshot: LocalWorkspaceSnapshot): IrohGenesis {
        val local = snapshot.local
        val baseSettings = IrohJson.strict.decodeFromString<TimerSettings>(local.settingsJson)
        val projection = TimerReducer.replay(
            local.canonicalTimerJson?.let(IrohJson.strict::decodeFromString),
            IrohJson.strict.decodeFromString(local.historyJson),
            snapshot.commands.map { it.toModel() },
        ).let { value -> TimerReducer.projectAt(value.timer, value.history, currentTimeMillis()) }
        val projectedTasks = TaskReducer.replay(
            IrohJson.strict.decodeFromString(local.tasksJson),
            snapshot.taskOperations.map { it.toModel() },
        )
        val projectedSettings = SettingsReducer.replayDurations(
            baseSettings,
            snapshot.durationOperations.map { it.toModel() },
        ).copy(
            autoStartBreaks = SettingsReducer.replayAutoStart(
                baseSettings.autoStartBreaks,
                snapshot.autoStartOperations.map { it.toModel() },
            ),
        )
        val projectedTimer = projection.timer?.let { timer ->
            val pendingById = snapshot.commands.associateBy { it.id }
            val lastOrigin = timer.lastIntent?.commandId?.let(pendingById::get)?.let { local.deviceId }
            val startedBy = timer.startedByDeviceId ?: snapshot.commands.firstOrNull { command ->
                command.type == me.egigoka.pomodorough.data.CommandType.Start && command.timerId == timer.id
            }?.let { local.deviceId } ?: local.deviceId.takeIf { local.ownedTimerId == timer.id }
            timer.copy(
                startedByDeviceId = startedBy,
                lastIntent = timer.lastIntent?.copy(deviceId = lastOrigin ?: timer.lastIntent.deviceId),
            )
        }
        return IrohGenesis(
            canonicalTimer = projectedTimer,
            history = canonicalRoomHistory(projection.history),
            tasks = projectedTasks,
            durationsMs = projectedSettings.effectiveDurationsMs(),
            autoStartBreaks = projectedSettings.autoStartBreaks,
            hlcWallMs = local.hlcWallMs,
            hlcCounter = local.hlcCounter,
        )
    }

    private fun makeRoomDeviceState(source: LocalWorkspaceSnapshot): LocalWorkspaceSnapshot = source.copy(
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
        bootstrapResolution = null,
    )

    private fun makeGenesisState(
        source: LocalWorkspaceSnapshot,
        genesis: IrohGenesis,
    ): LocalWorkspaceSnapshot {
        val base = makeRoomDeviceState(source)
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
                hlcWallMs = genesis.hlcWallMs,
                hlcCounter = genesis.hlcCounter,
            ),
        )
    }

    private fun canonicalRoomHistory(history: List<me.egigoka.pomodorough.data.HistoryItem>) =
        history.distinctBy { it.timerId }
            .map { item -> item.copy(id = item.timerId, pending = false) }
            .sortedWith(
                compareByDescending<me.egigoka.pomodorough.data.HistoryItem> {
                    (it.endedAt ?: it.completedAt)?.let(Instant::parse) ?: Instant.EPOCH
                }
                    .thenBy { it.timerId },
            )

    private fun eligibleRoomCommands(
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

    private fun parseCursor(value: String): Pair<String, String> {
        val split = value.split('\u0000')
        require(split.size == 2)
        val domain = IrohDomain.valueOf(split[0])
        val id = split[1]
        require(if (domain == IrohDomain.genesis) id == "genesis" else IrohProtocolV1.isIdentifier(id))
        return domain.name to id
    }

    private fun validateRecordSet(records: List<IrohOperationEntity>) {
        require(records.count { it.domain == IrohDomain.genesis.name && it.operationId == "genesis" } == 1) {
            "Iroh room genesis is missing or conflicting"
        }
        require(records.map { it.domain to it.operationId }.toSet().size == records.size)
        records.map(IrohOperationEntity::toRecord).forEach(IrohOperationRecord::validate)
        val sequences = records.filter { it.domain == IrohDomain.timer.name }.mapNotNull { operation ->
            operation.deviceSequence?.let { Triple(operation.originDeviceId, it, operation.operationId) }
        }
        require(sequences.map { it.first to it.second }.toSet().size == sequences.size) {
            "Iroh device sequence is reused"
        }
    }

    private suspend fun saveConflict(roomId: String, stored: IrohOperationEntity, receivedDigest: String) {
        dao.upsertIrohConflict(
            IrohConflictEntity(
                roomId = roomId,
                domain = stored.domain,
                operationId = stored.operationId,
                localDigest = stored.digest,
                receivedDigest = receivedDigest,
                detectedAtMs = currentTimeMillis(),
            ),
        )
    }

    private fun cursor(entry: IrohInventoryEntry) = entry.domain.name + "\u0000" + entry.id
}

private fun IrohOperationRecord.toEntity(roomId: String) = IrohOperationEntity(
    roomId = roomId,
    domain = domain.name,
    operationId = id,
    originDeviceId = deviceId,
    operationJson = operation.toString(),
    digest = digest(),
    hlcWallMs = hlcWallMs,
    hlcCounter = hlcCounter,
    deviceSequence = deviceSequence,
)

private fun IrohOperationEntity.toRecord() = IrohOperationRecord(
    domain = IrohDomain.valueOf(domain),
    deviceId = originDeviceId,
    operation = IrohJson.strict.parseToJsonElement(operationJson).jsonObject,
).also { record ->
    require(record.id == operationId && record.digest() == digest) { "Saved Iroh operation is corrupted" }
}

private fun IrohConflictEntity.toEvidence() = IrohConflictEvidence(
    domain = IrohDomain.valueOf(domain),
    id = operationId,
    localDigest = localDigest,
    receivedDigest = receivedDigest,
    detectedAtMs = detectedAtMs,
)

private fun LocalStateEntity.withoutAccount(preserveDomain: Boolean): LocalStateEntity {
    val clearedSettings = IrohJson.strict.decodeFromString<TimerSettings>(settingsJson)
        .withDurations(me.egigoka.pomodorough.data.DurationsMs())
        .copy(autoStartBreaks = false)
    return copy(
        revision = 0,
        canonicalTimerJson = canonicalTimerJson.takeIf { preserveDomain },
        historyJson = historyJson.takeIf { preserveDomain } ?: "[]",
        settingsJson = if (preserveDomain) settingsJson else IrohJson.strict.encodeToString(clearedSettings),
        userJson = null,
        ownerUserId = null,
        tasksJson = tasksJson.takeIf { preserveDomain } ?: "[]",
        knownTasksJson = knownTasksJson.takeIf { preserveDomain } ?: "[]",
        selectedTaskId = selectedTaskId.takeIf { preserveDomain },
        canonicalAutoStartBreaks = canonicalAutoStartBreaks.takeIf { preserveDomain } ?: false,
        ownedTimerId = ownedTimerId.takeIf { preserveDomain },
        serverClockOffsetMs = null,
        serverClockUncertaintyMs = null,
        serverClockSamplePhysicalMs = null,
        serverClockSampleElapsedRealtimeMs = null,
        serverClockBootId = null,
    )
}
