package me.egigoka.pomodorough.data.iroh

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.local.IrohConflictEntity
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohInventoryDao
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.IrohRecordsDao
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohRoomTransactionsDao
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohPersistencePositiveUnitTest {
    @Test
    fun inventoryPaginatesWithStableDomainAndIdentityCursor() = runTest {
        val harness = InventoryPersistenceHarness()
        harness.inventoryRows += listOf(
            operation("timer", "operation-0001"),
            operation("timer", "operation-0002"),
            operation("timer", "operation-0003"),
        )

        val (entries, next) = harness.persistence.inventory(RoomId, null, 2)

        assertEquals(listOf("operation-0001", "operation-0002"), entries.map { it.id })
        assertEquals("timer\u0000operation-0002", next)
        assertEquals(PageQuery(RoomId, null, null, 3), harness.pageQueries.single())
    }

    @Test
    fun inventoryCursorIsDecodedBeforeLoadingNextPage() = runTest {
        val harness = InventoryPersistenceHarness()
        harness.inventoryRows += operation("task", "task-operation-0002")

        val (entries, next) = harness.persistence.inventory(
            RoomId,
            "task\u0000task-operation-0001",
            1,
        )

        assertEquals(listOf("task-operation-0002"), entries.map { it.id })
        assertNull(next)
        assertEquals(
            PageQuery(RoomId, "task", "task-operation-0001", 2),
            harness.pageQueries.single(),
        )
    }

    @Test
    fun joinedPeerRetainsInviteCredentialsWithoutInventingIdentityMetadata() {
        val harness = RegistryPersistenceHarness()

        val peer = harness.persistence.joinedRoomPeer(invite(), EndpointId)

        assertEquals(RoomId, peer.roomId)
        assertEquals(EndpointId, peer.endpointId)
        assertEquals("ticket-value", peer.endpointTicket)
        assertNull(peer.deviceId)
        assertNull(peer.displayName)
        assertNull(peer.lastSeenAtMs)
    }
}

class IrohPersistenceNegativeUnitTest {
    @Test
    fun inventoryRejectsUnsafeLimitsBeforeQueryingStorage() = runTest {
        val harness = InventoryPersistenceHarness()

        listOf(0, IrohProtocolV1.MaxInventoryEntries + 1).forEach { limit ->
            assertSuspendThrows<IllegalArgumentException> {
                harness.persistence.inventory(RoomId, null, limit)
            }
        }

        assertTrue(harness.pageQueries.isEmpty())
    }

    @Test
    fun inventoryRejectsMalformedAndSemanticallyInvalidCursors() = runTest {
        val harness = InventoryPersistenceHarness()
        val invalid = listOf(
            "timer",
            "unknown\u0000operation-0001",
            "timer\u0000bad",
            "genesis\u0000operation-0001",
            "timer\u0000operation-0001\u0000extra",
        )

        invalid.forEach { cursor ->
            assertSuspendThrows<Exception> { harness.persistence.inventory(RoomId, cursor, 1) }
        }

        assertTrue(harness.pageQueries.isEmpty())
    }

    @Test
    fun operationRequestsRejectEmptyDuplicateAndOversizedReferences() = runTest {
        val harness = InventoryPersistenceHarness()
        val reference = IrohInventoryReference(IrohDomain.timer, "operation-0001")
        val invalid = listOf(
            emptyList(),
            listOf(reference, reference),
            List(IrohProtocolV1.MaxOperationReferences + 1) { index ->
                IrohInventoryReference(IrohDomain.timer, "operation-${index.toString().padStart(4, '0')}")
            },
        )

        invalid.forEach { references ->
            assertSuspendThrows<IllegalArgumentException> {
                harness.persistence.operations(RoomId, references)
            }
        }

        assertTrue(harness.recordQueries.isEmpty())
    }

    @Test
    fun peerValidationFailsBeforeBoundedPersistence() = runTest {
        val harness = RegistryPersistenceHarness()
        val valid = peer()
        val invalid = listOf(
            valid.copy(endpointTicket = "x".repeat(IrohProtocolV1.MaxEndpointTicketBytes + 1)),
            valid.copy(displayName = ""),
        )

        invalid.forEach { candidate ->
            assertSuspendThrows<IllegalArgumentException> {
                harness.persistence.upsertPeer(candidate)
            }
        }

        assertTrue(harness.upsertedPeers.isEmpty())
    }

    @Test
    fun joinedPeerRejectsInvalidEndpointIdentity() {
        val harness = RegistryPersistenceHarness()

        val error = assertThrows(IllegalArgumentException::class.java) {
            harness.persistence.joinedRoomPeer(invite(), "bad")
        }

        assertEquals("Endpoint ID is invalid", error.message)
    }
}

class IrohPersistencePositiveIntegrationTest {
    @Test
    fun operationReferencesResolveInCallerOrderAndDecodeCanonicalRecords() = runTest {
        val harness = InventoryPersistenceHarness()
        val first = operation("autoStart", "auto-operation-0001", enabled = true)
        val second = operation("autoStart", "auto-operation-0002", enabled = false)
        harness.records[first.domain to first.operationId] = first
        harness.records[second.domain to second.operationId] = second

        val loaded = harness.persistence.operations(
            RoomId,
            listOf(
                IrohInventoryReference(IrohDomain.autoStart, second.operationId),
                IrohInventoryReference(IrohDomain.autoStart, first.operationId),
            ),
        )

        assertEquals(listOf(second.operationId, first.operationId), loaded.map { it.id })
        assertEquals(
            listOf(second.domain to second.operationId, first.domain to first.operationId),
            harness.recordQueries,
        )
    }

    @Test
    fun missingReferenceDetectionReturnsOnlyUnknownRemoteOperations() = runTest {
        val harness = InventoryPersistenceHarness()
        val stored = operation("autoStart", "auto-operation-0001")
        harness.records[stored.domain to stored.operationId] = stored
        val remote = listOf(
            IrohInventoryEntry(IrohDomain.autoStart, stored.operationId, stored.digest),
            IrohInventoryEntry(IrohDomain.timer, "timer-operation-0002", "remote-digest"),
        )

        val missing = harness.persistence.missingReferences(RoomId, remote)

        assertEquals(
            listOf(IrohInventoryReference(IrohDomain.timer, "timer-operation-0002")),
            missing,
        )
        assertTrue(harness.savedConflicts.isEmpty())
    }

    @Test
    fun joinedRoomTransactionsPersistRoomBeforePeerAndBoundExistingPeers() = runTest {
        val harness = RegistryPersistenceHarness()
        val room = room()
        val peer = peer()

        harness.persistence.prepareJoinedRoom(room, peer)
        harness.persistence.prepareExistingJoinedRoom(room.copy(activated = true), peer)

        assertEquals(
            listOf("insertRoom", "upsertPeer", "updateRoom", "listPeers", "peerCount", "upsertPeer"),
            harness.calls,
        )
        assertEquals(listOf(peer, peer), harness.upsertedPeers)
    }

    @Test
    fun existingPeerCanRefreshWhenAddressBookIsAtCapacity() = runTest {
        val harness = RegistryPersistenceHarness()
        val peer = peer()
        harness.peers += peer
        harness.reportedPeerCount = IrohProtocolV1.MaxPeers

        harness.persistence.upsertPeer(peer.copy(displayName = "Current device"))

        assertEquals(1, harness.upsertedPeers.size)
        assertEquals("Current device", harness.upsertedPeers.single().displayName)
    }
}

class IrohPersistenceNegativeIntegrationTest {
    @Test
    fun missingOperationStopsOrderedResolutionAtFirstAbsentReference() = runTest {
        val harness = InventoryPersistenceHarness()
        val first = operation("autoStart", "auto-operation-0001")
        harness.records[first.domain to first.operationId] = first
        val references = listOf(
            IrohInventoryReference(IrohDomain.autoStart, first.operationId),
            IrohInventoryReference(IrohDomain.timer, "timer-operation-0002"),
            IrohInventoryReference(IrohDomain.task, "task-operation-0003"),
        )

        val error = assertSuspendThrows<NoSuchElementException> {
            harness.persistence.operations(RoomId, references)
        }

        assertEquals("Iroh operation was not found", error.message)
        assertEquals(
            listOf(first.domain to first.operationId, "timer" to "timer-operation-0002"),
            harness.recordQueries,
        )
    }

    @Test
    fun digestConflictIsPersistedBeforeImmutableOperationIsRejected() = runTest {
        val harness = InventoryPersistenceHarness(currentTimeMillis = { 123_456L })
        val stored = operation("autoStart", "auto-operation-0001")
        harness.records[stored.domain to stored.operationId] = stored
        val remote = IrohInventoryEntry(IrohDomain.autoStart, stored.operationId, "different-digest")

        val error = assertSuspendThrows<IllegalStateException> {
            harness.persistence.missingReferences(RoomId, listOf(remote))
        }

        assertEquals("Immutable Iroh operation conflict", error.message)
        assertEquals(
            IrohConflictEntity(
                RoomId,
                stored.domain,
                stored.operationId,
                stored.digest,
                remote.digest,
                123_456L,
            ),
            harness.savedConflicts.single(),
        )
    }

    @Test
    fun corruptedStoredOperationFailsClosedDuringReferenceResolution() = runTest {
        val harness = InventoryPersistenceHarness()
        val stored = operation("autoStart", "auto-operation-0001")
            .copy(digest = "corrupt-digest")
        harness.records[stored.domain to stored.operationId] = stored

        assertSuspendThrows<IllegalArgumentException> {
            harness.persistence.operations(
                RoomId,
                listOf(IrohInventoryReference(IrohDomain.autoStart, stored.operationId)),
            )
        }
    }

    @Test
    fun fullAddressBookRejectsNewPeerWithoutPartialWrite() = runTest {
        val harness = RegistryPersistenceHarness()
        harness.reportedPeerCount = IrohProtocolV1.MaxPeers

        val error = assertSuspendThrows<IllegalStateException> {
            harness.persistence.upsertPeer(peer())
        }

        assertEquals("Iroh room address book contains 64 peers", error.message)
        assertTrue(harness.upsertedPeers.isEmpty())
    }
}

private data class PageQuery(
    val roomId: String,
    val afterDomain: String?,
    val afterId: String?,
    val limit: Int,
)

private class InventoryPersistenceHarness(currentTimeMillis: () -> Long = { 1L }) {
    val inventoryRows = mutableListOf<IrohOperationEntity>()
    val pageQueries = mutableListOf<PageQuery>()
    val records = mutableMapOf<Pair<String, String>, IrohOperationEntity>()
    val recordQueries = mutableListOf<Pair<String, String>>()
    val savedConflicts = mutableListOf<IrohConflictEntity>()

    private val inventory = object : IrohInventoryDao {
        override suspend fun irohOperationPage(
            roomId: String,
            afterDomain: String?,
            afterId: String?,
            limit: Int,
        ): List<IrohOperationEntity> {
            pageQueries += PageQuery(roomId, afterDomain, afterId, limit)
            return inventoryRows.take(limit)
        }
    }
    private val recordDao = object : IrohRecordsDao {
        override suspend fun irohOperations(roomId: String) = records.values.toList()
        override suspend fun irohOperationsCapped(roomId: String, limit: Int) =
            records.values.toList().take(limit)
        override suspend fun irohOperationCount(roomId: String) = records.size
        override suspend fun irohOperation(roomId: String, domain: String, operationId: String): IrohOperationEntity? {
            recordQueries += domain to operationId
            return records[domain to operationId]
        }
        override suspend fun hasIrohGenesis(roomId: String) = 0
        override suspend fun insertIrohOperations(operations: List<IrohOperationEntity>) = emptyList<Long>()
        override suspend fun insertNewIrohOperations(operations: List<IrohOperationEntity>) = Unit
    }
    private val conflictDao = object : IrohConflictsDao {
        override suspend fun irohConflict(roomId: String): IrohConflictEntity? = null
        override suspend fun upsertIrohConflict(conflict: IrohConflictEntity) {
            savedConflicts += conflict
        }
    }
    private val canonicalRecords = IrohCanonicalRecordPersistence(
        dao = interfaceProxy(),
        conflicts = conflictDao,
        metadata = blankInstance(),
        projection = blankInstance(),
        workspaceCoordinator = LocalWorkspaceCoordinator(),
        currentTimeMillis = currentTimeMillis,
    )
    val persistence = IrohInventoryReferencePersistence(
        inventory = inventory,
        rooms = interfaceProxy(),
        records = recordDao,
        conflicts = conflictDao,
        peers = interfaceProxy(),
        metadata = blankInstance(),
        canonicalRecords = canonicalRecords,
    )
}

private class RegistryPersistenceHarness : IrohRoomTransactionsDao {
    val calls = mutableListOf<String>()
    val peers = mutableListOf<IrohPeerEntity>()
    val upsertedPeers = mutableListOf<IrohPeerEntity>()
    var reportedPeerCount = 0
    val persistence = IrohPeerRegistryPersistence(this)

    override suspend fun irohRooms(): List<IrohRoomEntity> = emptyList()
    override suspend fun irohRoomsCapped(limit: Int): List<IrohRoomEntity> = emptyList()
    override suspend fun irohRoomCount(): Int = 0
    override suspend fun irohRoom(roomId: String): IrohRoomEntity? = null
    override suspend fun preferredIrohRoom(): IrohRoomEntity? = null
    override suspend fun insertIrohRoom(room: IrohRoomEntity) { calls += "insertRoom" }
    override suspend fun updateIrohRoom(room: IrohRoomEntity) { calls += "updateRoom" }
    override suspend fun deleteIrohRoom(roomId: String) { calls += "deleteRoom" }

    override suspend fun deleteIncompleteIrohRooms() { calls += "deleteIncompleteRooms" }
    override suspend fun irohPeers(roomId: String): List<IrohPeerEntity> {
        calls += "listPeers"
        return peers.toList()
    }
    override suspend fun irohPeersCapped(roomId: String, limit: Int): List<IrohPeerEntity> {
        calls += "listPeers"
        return peers.take(limit)
    }
    override suspend fun irohPeerCount(roomId: String): Int {
        calls += "peerCount"
        return reportedPeerCount
    }
    override suspend fun upsertIrohPeer(peer: IrohPeerEntity) {
        calls += "upsertPeer"
        upsertedPeers += peer
    }
}

private fun operation(
    domain: String,
    id: String,
    enabled: Boolean = true,
): IrohOperationEntity {
    require(domain == "autoStart" || domain == "timer" || domain == "task")
    if (domain != "autoStart") {
        return IrohOperationEntity(RoomId, domain, id, DeviceId, "{}", "digest-$id", 1L, 0L, null)
    }
    val record = IrohOperationRecord.autoStart(
        DeviceId,
        AutoStartOperation(id, DeviceId, enabled, "2026-01-01T00:00:00Z", 1L, 0L),
    )
    return record.toIrohEntity(RoomId)
}

private fun invite() = IrohRoomInvite(RoomId, "Room", "ticket-value", RoomSecret.copyOf())
private fun peer() = IrohPeerEntity(RoomId, EndpointId, "ticket-value", DeviceId, null, null)
private fun room() = IrohRoomEntity(RoomId, "Room", byteArrayOf(1), "return", "state", 1L)

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> interfaceProxy(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ ->
    when {
        method.name == "toString" -> "TestProxy(${T::class.java.simpleName})"
        method.returnType == Boolean::class.javaPrimitiveType -> false
        method.returnType == Int::class.javaPrimitiveType -> 0
        method.returnType == Long::class.javaPrimitiveType -> 0L
        else -> null
    }
} as T

private inline fun <reified T> blankInstance(): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
        .invoke(field.get(null), T::class.java) as T
}

private suspend inline fun <reified T : Throwable> assertSuspendThrows(
    crossinline block: suspend () -> Unit,
): T = try {
    block()
    throw AssertionError("Expected ${T::class.java.name}")
} catch (error: Throwable) {
    if (error is T) error else throw AssertionError(
        "Expected ${T::class.java.name}, got ${error::class.java.name}",
        error,
    )
}

private val RoomSecret = ByteArray(32) { it.toByte() }
private val RoomId = IrohProtocolV1.roomId(RoomSecret)
private const val EndpointId = "endpoint-valid01"
private const val DeviceId = "device-valid01"
