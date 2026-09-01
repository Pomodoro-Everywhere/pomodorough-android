package me.egigoka.pomodorough.data.iroh

import java.security.SecureRandom
import kotlinx.serialization.json.JsonElement
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohPersistenceDao
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

data class IrohRoomProjection(
    val snapshot: LocalWorkspaceSnapshot,
    val operationCount: Int,
)

class IrohRoomStore(
    dao: IrohPersistenceDao,
    vault: IrohSecretVault,
    sharedCoreDispatch: (String, String) -> JsonElement,
    private val workspaceCoordinator: LocalWorkspaceCoordinator = LocalWorkspaceCoordinator(),
    random: SecureRandom = SecureRandom(),
    currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val projection = IrohRoomProjectionPersistence(
        rooms = dao,
        records = dao,
        sharedCoreDispatch = sharedCoreDispatch,
        currentTimeMillis = currentTimeMillis,
    )
    private val peerRegistry = IrohPeerRegistryPersistence(dao)
    private val metadata = IrohRoomMetadataPersistence(
        dao = dao,
        conflicts = dao,
        vault = vault,
        peerRegistry = peerRegistry,
        projection = projection,
        workspaceCoordinator = workspaceCoordinator,
        random = random,
        currentTimeMillis = currentTimeMillis,
    )
    private val canonicalRecords = IrohCanonicalRecordPersistence(
        dao = dao,
        conflicts = dao,
        metadata = metadata,
        projection = projection,
        workspaceCoordinator = workspaceCoordinator,
        currentTimeMillis = currentTimeMillis,
    )
    private val inventoryReferences = IrohInventoryReferencePersistence(
        inventory = dao,
        rooms = dao,
        records = dao,
        conflicts = dao,
        peers = dao,
        metadata = metadata,
        canonicalRecords = canonicalRecords,
    )

    internal val coordinator: LocalWorkspaceCoordinator
        get() = workspaceCoordinator

    suspend fun replicationSettings(): ReplicationSettingsEntity = metadata.replicationSettings()

    suspend fun activeRoom(): IrohRoomEntity? = metadata.activeRoom()

    suspend fun activeRoomSecret(): ByteArray? = metadata.activeRoomSecret()

    suspend fun validateRoomSecrets() = metadata.validateRoomSecrets()

    suspend fun resetIdentityData() = metadata.resetIdentityData()

    suspend fun discardIncompleteRooms() = metadata.discardIncompleteRooms()

    suspend fun createRoom(name: String?): Pair<IrohRoomEntity, IrohRoomProjection> =
        metadata.createRoom(name)

    suspend fun prepareJoinedRoom(
        invite: IrohRoomInvite,
        endpointId: String,
    ): Pair<IrohRoomEntity, Boolean> = metadata.prepareJoinedRoom(invite, endpointId)

    suspend fun activateJoinedRoom(roomId: String): IrohRoomProjection =
        metadata.activateJoinedRoom(roomId)

    suspend fun activateExistingRoom(roomId: String): IrohRoomProjection =
        metadata.activateExistingRoom(roomId)

    suspend fun leaveActiveRoom(): LocalWorkspaceSnapshot = metadata.leaveActiveRoom()

    suspend fun setMode(mode: ReplicationMode): LocalWorkspaceSnapshot? = metadata.setMode(mode)

    suspend fun captureLocalOperations(): IrohRoomProjection = canonicalRecords.captureLocalOperations()

    suspend fun insertRemoteRecords(roomId: String, records: List<IrohOperationRecord>) =
        canonicalRecords.insertRemoteRecords(roomId, records)

    suspend fun refreshProjection(roomId: String): IrohRoomProjection =
        canonicalRecords.refreshProjection(roomId)

    suspend fun inventory(
        roomId: String,
        after: String?,
        limit: Int,
    ): Pair<List<IrohInventoryEntry>, String?> = inventoryReferences.inventory(roomId, after, limit)

    suspend fun operations(
        roomId: String,
        references: List<IrohInventoryReference>,
    ): List<IrohOperationRecord> = inventoryReferences.operations(roomId, references)

    suspend fun missingReferences(
        roomId: String,
        remote: List<IrohInventoryEntry>,
    ): List<IrohInventoryReference> = inventoryReferences.missingReferences(roomId, remote)

    suspend fun upsertPeer(peer: IrohPeerEntity) = peerRegistry.upsertPeer(peer)

    suspend fun peers(roomId: String): List<IrohPeerEntity> = peerRegistry.peers(roomId)

    suspend fun hasGenesis(roomId: String): Boolean = metadata.hasGenesis(roomId)

    suspend fun snapshot(roomId: String): IrohNetworkState = inventoryReferences.snapshot(roomId)

    suspend fun discardIncompleteInactiveRoom(roomId: String) =
        metadata.discardIncompleteInactiveRoom(roomId)

    suspend fun clearAccountData() = metadata.clearAccountData()
}
