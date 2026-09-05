package me.egigoka.pomodorough.data.iroh

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohInventoryDao
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohPeersDao
import me.egigoka.pomodorough.data.local.IrohRecordsDao
import me.egigoka.pomodorough.data.local.IrohRoomMetadataDao
import me.egigoka.pomodorough.data.local.IrohWorkspaceTransactionsDao
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class IrohSnapshotBoundsTest {
    @Test
    fun snapshotUsesCountsWithoutLoadingFullTables() = runTest {
        val persistence = IrohInventoryReferencePersistence(
            inventory = proxy(IrohInventoryDao::class.java, emptyMap()),
            rooms = proxy(IrohRoomMetadataDao::class.java, mapOf("irohRoom" to null)),
            records = CountingRecordsDao(operationCount = 42),
            conflicts = proxy(IrohConflictsDao::class.java, mapOf("irohConflict" to null)),
            peers = CountingPeersDao(peerCount = 7),
            metadata = fakeMetadata(),
            canonicalRecords = blankInstance(),
        )

        val snapshot = persistence.snapshot("room")

        assertEquals(7, snapshot.peerCount)
        assertEquals(42, snapshot.operationCount)
    }
}

private class CountingRecordsDao(private val operationCount: Int) : IrohRecordsDao {
    override suspend fun irohOperations(roomId: String): List<IrohOperationEntity> =
        throw AssertionError("Full iroh_operations load must not be used for snapshot")

    override suspend fun irohOperationsCapped(roomId: String, limit: Int): List<IrohOperationEntity> =
        throw AssertionError("Capped iroh_operations load must not be used for snapshot")

    override suspend fun irohOperationCount(roomId: String): Int = operationCount

    override suspend fun irohOperation(roomId: String, domain: String, operationId: String) = null

    override suspend fun hasIrohGenesis(roomId: String): Int = 0

    override suspend fun insertIrohOperations(operations: List<IrohOperationEntity>) = emptyList<Long>()

    override suspend fun insertNewIrohOperations(operations: List<IrohOperationEntity>) = Unit
}

private class CountingPeersDao(private val peerCount: Int) : IrohPeersDao {
    override suspend fun irohPeers(roomId: String): List<IrohPeerEntity> =
        throw AssertionError("Full iroh_peers load must not be used for snapshot")

    override suspend fun irohPeersCapped(roomId: String, limit: Int): List<IrohPeerEntity> =
        throw AssertionError("Capped iroh_peers load must not be used for snapshot")

    override suspend fun irohPeerCount(roomId: String): Int = peerCount

    override suspend fun upsertIrohPeer(peer: IrohPeerEntity) = Unit
}

private fun fakeMetadata(): IrohRoomMetadataPersistence = IrohRoomMetadataPersistence(
    dao = proxy(
        IrohWorkspaceTransactionsDao::class.java,
        mapOf("replicationSettings" to null, "upsertReplicationSettings" to Unit),
    ),
    conflicts = proxy(IrohConflictsDao::class.java, emptyMap()),
    vault = blankInstance(),
    peerRegistry = blankInstance(),
    projection = blankInstance(),
    workspaceCoordinator = LocalWorkspaceCoordinator(),
    random = java.security.SecureRandom(),
    currentTimeMillis = { 1L },
)

@Suppress("UNCHECKED_CAST")
private fun <T> proxy(type: Class<T>, values: Map<String, Any?>): T =
    Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, _ -> values[method.name] } as T

private inline fun <reified T> blankInstance(): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
        .invoke(field.get(null), T::class.java) as T
}
