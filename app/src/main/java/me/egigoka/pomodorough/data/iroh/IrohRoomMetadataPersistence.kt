package me.egigoka.pomodorough.data.iroh

import java.security.SecureRandom
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohWorkspaceTransactionsDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

internal class IrohRoomMetadataPersistence(
    private val dao: IrohWorkspaceTransactionsDao,
    private val conflicts: IrohConflictsDao,
    private val vault: IrohSecretVault,
    private val peerRegistry: IrohPeerRegistryPersistence,
    private val projection: IrohRoomProjectionPersistence,
    private val workspaceCoordinator: LocalWorkspaceCoordinator,
    private val random: SecureRandom,
    private val currentTimeMillis: () -> Long,
) {
    suspend fun replicationSettings(): ReplicationSettingsEntity =
        dao.replicationSettings() ?: ReplicationSettingsEntity(mode = ReplicationMode.CENTRALIZED.name)
            .also { dao.upsertReplicationSettings(it) }

    suspend fun activeRoom(): IrohRoomEntity? = replicationSettings().activeRoomId?.let { roomId ->
        dao.irohRoom(roomId)
    }

    suspend fun activeRoomSecret(): ByteArray? = activeRoom()?.let { room ->
        vault.decryptRoomSecret(room.roomId, room.encryptedRoomSecret)
    }

    suspend fun validateRoomSecrets() {
        dao.irohRooms().forEach { room ->
            vault.decryptRoomSecret(room.roomId, room.encryptedRoomSecret).fill(0)
        }
    }

    suspend fun resetIdentityData() = workspaceCoordinator.withLock {
        val settings = replicationSettings()
        val rooms = dao.irohRooms()
        val restored = identityResetWorkspace(settings, rooms)
        dao.resetIrohIdentity(
            rooms.map(IrohRoomEntity::roomId),
            restored,
            ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name),
        )
    }

    private suspend fun identityResetWorkspace(
        settings: ReplicationSettingsEntity,
        rooms: List<IrohRoomEntity>,
    ): LocalWorkspaceSnapshot {
        val activeRoom = rooms.singleOrNull { it.roomId == settings.activeRoomId }
        if (settings.mode == ReplicationMode.IROH.name) {
            return WorkspaceCodec.decode(
                checkNotNull(activeRoom) { "Active Iroh room is missing" }.returnStateJson,
            )
        }
        return dao.localWorkspaceSnapshot()
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
        validateRoomSecrets()
        val returnState = dao.localWorkspaceSnapshot()
        val secret = ByteArray(32).also(random::nextBytes)
        val roomId = IrohProtocolV1.roomId(secret)
        return try {
            val genesis = projection.genesis(returnState)
            val genesisRecord = IrohOperationRecord.genesis(returnState.local.deviceId, genesis).also {
                it.validate()
            }
            val roomState = projection.makeGenesisState(returnState, genesis)
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
                genesisRecord.toIrohEntity(roomId),
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
        val peer = peerRegistry.joinedRoomPeer(invite, endpointId)
        val existing = dao.irohRoom(invite.roomId)
        if (existing != null) return@withLock prepareExistingJoinedRoom(existing, invite, peer)
        validateRoomSecrets()
        val returnState = dao.localWorkspaceSnapshot()
        val roomState = projection.makeRoomDeviceState(returnState)
        val room = IrohRoomEntity(
            roomId = invite.roomId,
            roomName = invite.roomName,
            encryptedRoomSecret = vault.encryptRoomSecret(invite.roomId, invite.roomSecret),
            returnStateJson = WorkspaceCodec.encode(returnState),
            roomStateJson = WorkspaceCodec.encode(roomState),
            createdAtMs = currentTimeMillis(),
            activated = false,
        )
        peerRegistry.prepareJoinedRoom(room, peer)
        room to true
    }

    private suspend fun prepareExistingJoinedRoom(
        existing: IrohRoomEntity,
        invite: IrohRoomInvite,
        peer: IrohPeerEntity,
    ): Pair<IrohRoomEntity, Boolean> {
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
            existing.copy(returnStateJson = WorkspaceCodec.encode(dao.localWorkspaceSnapshot()))
        } else {
            existing
        }
        peerRegistry.prepareExistingJoinedRoom(prepared, peer)
        return prepared to false
    }

    suspend fun activateJoinedRoom(roomId: String): IrohRoomProjection =
        workspaceCoordinator.withLock { activateJoinedRoomLocked(roomId) }

    private suspend fun activateJoinedRoomLocked(roomId: String): IrohRoomProjection {
        val settings = replicationSettings()
        val alreadyActive = settings.mode == ReplicationMode.IROH.name && settings.activeRoomId == roomId
        val projected = projection.project(roomId)
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
        require(conflicts.irohConflict(roomId) == null) { "Iroh room requires repair" }
        val returnState = dao.localWorkspaceSnapshot()
        val projected = projection.project(roomId)
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

    suspend fun hasGenesis(roomId: String): Boolean = dao.hasIrohGenesis(roomId) == 1

    suspend fun discardIncompleteInactiveRoom(roomId: String) {
        require(replicationSettings().activeRoomId != roomId) { "Active Iroh room cannot be discarded" }
        if (!hasGenesis(roomId) && conflicts.irohConflict(roomId) == null) {
            dao.deleteIrohRoom(roomId)
        }
    }

    suspend fun clearAccountData() = workspaceCoordinator.withLock {
        val stored = dao.localWorkspaceSnapshot()
        val current = stored.withoutIrohAccount(preserveDomain = stored.local.ownerUserId == null)
        val malformedRoomIds = mutableListOf<String>()
        val clearedRooms = dao.irohRooms().mapNotNull { room ->
            runCatching {
                val returned = WorkspaceCodec.decode(room.returnStateJson)
                val roomState = WorkspaceCodec.decode(room.roomStateJson)
                room.copy(
                    returnStateJson = WorkspaceCodec.encode(
                        returned.withoutIrohAccount(preserveDomain = returned.local.ownerUserId == null),
                    ),
                    roomStateJson = WorkspaceCodec.encode(
                        roomState.withoutIrohAccount(preserveDomain = roomState.local.ownerUserId == null),
                    ),
                )
            }.getOrElse {
                malformedRoomIds += room.roomId
                null
            }
        }
        val storedSettings = replicationSettings()
        val clearedSettings = if (storedSettings.activeRoomId in malformedRoomIds) {
            ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name)
        } else {
            storedSettings
        }
        dao.clearIrohAccountData(
            rooms = clearedRooms,
            malformedRoomIds = malformedRoomIds,
            snapshot = current,
            settings = clearedSettings,
        )
    }
}

private fun LocalWorkspaceSnapshot.withoutIrohAccount(preserveDomain: Boolean): LocalWorkspaceSnapshot = copy(
    local = local.withoutIrohAccount(preserveDomain),
    commands = commands.takeIf { preserveDomain }.orEmpty(),
    taskOperations = taskOperations.takeIf { preserveDomain }.orEmpty(),
    durationOperations = durationOperations.takeIf { preserveDomain }.orEmpty(),
    autoStartOperations = autoStartOperations.takeIf { preserveDomain }.orEmpty(),
    selectedTaskOperations = selectedTaskOperations.takeIf { preserveDomain }.orEmpty(),
    bootstrapResolution = null,
)

private fun LocalStateEntity.withoutIrohAccount(preserveDomain: Boolean): LocalStateEntity {
    val clearedSettings = TimerSettings()
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
