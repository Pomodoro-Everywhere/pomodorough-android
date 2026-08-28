package me.egigoka.pomodorough.data.iroh

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohRoomPersistenceDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var store: IrohRoomStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val core = SharedCore.fromAssets(context.assets)
        store = IrohRoomStore(
            database.timerDao(),
            IrohSecretVault(context),
            core::dispatch,
            currentTimeMillis = { NowMs },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomGenesisMetadataAndCanonicalRecordPersistTogether() = runBlocking {
        database.timerDao().insertState(state())

        val (room, projection) = store.createRoom("Persistence boundaries")

        assertEquals(
            ReplicationSettingsEntityForTest.iroh(room.roomId),
            database.timerDao().replicationSettings()?.let {
                ReplicationSettingsEntityForTest(it.mode, it.activeRoomId)
            },
        )
        assertEquals(room.roomId, store.activeRoom()?.roomId)
        assertEquals(projection.snapshot, database.timerDao().localWorkspaceSnapshot())
        assertEquals(1, projection.operationCount)
        val persisted = database.timerDao().irohOperations(room.roomId).single()
        val record = persisted.toRecordForTest()
        assertEquals(IrohDomain.genesis, record.domain)
        assertEquals("genesis", record.id)
        assertEquals(persisted.operationJson, record.operation.toString())
        assertEquals(persisted.digest, record.digest())
        assertTrue(store.hasGenesis(room.roomId))
        val secret = requireNotNull(store.activeRoomSecret())
        try {
            assertEquals(room.roomId, IrohProtocolV1.roomId(secret))
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun inventoryReferencesAndPeersPreserveDeterministicDatabaseOrder() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        val later = IrohOperationRecord.timer(
            "device-remote01",
            command("command-b0001", 2, "timer-b0001"),
        )
        val earlier = IrohOperationRecord.timer(
            "device-remote01",
            command("command-a0001", 1, "timer-a0001"),
        )
        store.insertRemoteRecords(room.roomId, listOf(later, earlier))
        store.upsertPeer(peer(room.roomId, "endpoint-b0001", "ticket-b"))
        store.upsertPeer(peer(room.roomId, "endpoint-a0001", "ticket-a"))
        store.upsertPeer(peer(room.roomId, "endpoint-b0001", "ticket-b-new"))

        val firstPage = store.inventory(room.roomId, after = null, limit = 2)
        assertEquals(
            listOf(
                IrohInventoryReference(IrohDomain.genesis, "genesis"),
                earlier.let { IrohInventoryReference(it.domain, it.id) },
            ),
            firstPage.first.map(IrohInventoryEntry::reference),
        )
        val secondPage = store.inventory(room.roomId, after = firstPage.second, limit = 2)
        assertEquals(listOf(later.id), secondPage.first.map(IrohInventoryEntry::id))
        assertEquals(null, secondPage.second)
        assertEquals(
            listOf(later.id, "genesis"),
            store.operations(
                room.roomId,
                listOf(later.reference(), IrohInventoryReference(IrohDomain.genesis, "genesis")),
            ).map(IrohOperationRecord::id),
        )
        assertEquals(
            listOf("endpoint-a0001", "endpoint-b0001"),
            store.peers(room.roomId).map(IrohPeerEntity::endpointId),
        )
        assertEquals("ticket-b-new", store.peers(room.roomId).last().endpointTicket)
        assertEquals(2, store.snapshot(room.roomId).peerCount)
        assertEquals(3, store.snapshot(room.roomId).operationCount)
    }

    @Test
    fun immutableDigestMismatchPersistsEvidenceAndBlocksFurtherWrites() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        val record = IrohOperationRecord.timer(
            "device-remote01",
            command("command-conflict01", 1, "timer-conflict01"),
        )
        store.insertRemoteRecords(room.roomId, listOf(record))
        val receivedDigest = Base64Url.encode(ByteArray(32) { 7 })

        val conflictError = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.missingReferences(
                    room.roomId,
                    listOf(IrohInventoryEntry(record.domain, record.id, receivedDigest)),
                )
            }
        }

        assertEquals("Immutable Iroh operation conflict", conflictError.message)
        val evidence = requireNotNull(database.timerDao().irohConflict(room.roomId))
        assertEquals(record.domain.name, evidence.domain)
        assertEquals(record.id, evidence.operationId)
        assertEquals(record.digest(), evidence.localDigest)
        assertEquals(receivedDigest, evidence.receivedDigest)
        assertEquals(NowMs, evidence.detectedAtMs)
        assertEquals(IrohConnectionStatus.CONFLICT, store.snapshot(room.roomId).status)
        val blocked = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.insertRemoteRecords(room.roomId, listOf(record)) }
        }
        assertEquals("Iroh room requires repair", blocked.message)
        assertEquals(2, database.timerDao().irohOperations(room.roomId).size)
    }

    @Test
    fun peerRegistryRejectsSixtyFifthPeerWithoutEvictionAndStillUpdatesExistingPeer() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        repeat(IrohProtocolV1.MaxPeers) { index ->
            store.upsertPeer(peer(room.roomId, "endpoint-${index.toString().padStart(4, '0')}", "ticket-$index"))
        }

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { store.upsertPeer(peer(room.roomId, "endpoint-overflow", "overflow")) }
        }

        assertEquals("Iroh room address book contains 64 peers", error.message)
        assertEquals(IrohProtocolV1.MaxPeers, store.peers(room.roomId).size)
        val existing = store.peers(room.roomId).first()
        store.upsertPeer(existing.copy(endpointTicket = "updated-ticket"))
        assertEquals(IrohProtocolV1.MaxPeers, store.peers(room.roomId).size)
        assertEquals("updated-ticket", store.peers(room.roomId).first().endpointTicket)
    }

    @Test
    fun captureTransactionRollsBackRecordRoomAndWorkspaceWhenWorkspaceReplacementFails() =
        runBlocking {
            database.timerDao().insertState(state())
            val room = store.createRoom(null).first
            val originalPending = PendingCommandEntity.from(
                command("command-original01", 1, "timer-original01"),
            )
            database.timerDao().insertCommand(originalPending)
            val roomBefore = requireNotNull(database.timerDao().irohRoom(room.roomId))
            val workspaceBefore = database.timerDao().localWorkspaceSnapshot()
            val operationsBefore = database.timerDao().irohOperations(room.roomId)
            val duplicate = PendingCommandEntity.from(
                command("command-duplicate01", 2, "timer-duplicate01"),
            )
            val invalidSnapshot = workspaceBefore.copy(
                local = workspaceBefore.local.copy(revision = 99),
                commands = listOf(duplicate, duplicate),
            )
            val newRecord = IrohOperationRecord.timer(
                "device-rollback01",
                command("command-new-record", 1, "timer-new-record"),
            )

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    database.timerDao().captureIrohOperations(
                        roomBefore.copy(roomName = "must roll back"),
                        listOf(newRecord.toEntityForTest(room.roomId)),
                        invalidSnapshot,
                    )
                }
            }

            val roomAfter = requireNotNull(database.timerDao().irohRoom(room.roomId))
            assertTrue(roomBefore.encryptedRoomSecret.contentEquals(roomAfter.encryptedRoomSecret))
            assertEquals(roomBefore.copy(encryptedRoomSecret = roomAfter.encryptedRoomSecret), roomAfter)
            assertEquals(workspaceBefore, database.timerDao().localWorkspaceSnapshot())
            assertEquals(operationsBefore, database.timerDao().irohOperations(room.roomId))
        }

    @Test
    fun accountScrubDeletesMalformedRoomsWithoutDecodingSnapshots() = runBlocking {
        database.timerDao().insertState(
            state().copy(ownerUserId = "account-1", userJson = "{\"id\":\"account-1\"}"),
        )
        val room = store.createRoom("Malformed hidden room").first
        database.timerDao().updateIrohRoom(
            room.copy(returnStateJson = "{malformed", roomStateJson = "[malformed"),
        )
        database.timerDao().insertState(
            requireNotNull(database.timerDao().localState()).copy(settingsJson = "{malformed"),
        )

        store.clearAccountData()

        assertTrue(database.timerDao().irohRooms().isEmpty())
        assertTrue(database.timerDao().irohOperations(room.roomId).isEmpty())
        assertEquals(ReplicationMode.OFFLINE.name, database.timerDao().replicationSettings()?.mode)
        assertEquals(null, database.timerDao().replicationSettings()?.activeRoomId)
        val cleared = requireNotNull(database.timerDao().localState())
        assertEquals(null, cleared.ownerUserId)
        assertEquals(null, cleared.userJson)
        IrohJson.strict.decodeFromString<TimerSettings>(cleared.settingsJson)
        Unit
    }

    private fun state() = LocalStateEntity(
        deviceId = "device-1",
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )

    private fun command(id: String, sequence: Long, timerId: String) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = timerId,
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 60_000,
        occurredAt = "2026-01-01T00:01:00Z",
        hlcWallMs = NowMs + sequence,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private fun peer(roomId: String, endpointId: String, ticket: String) = IrohPeerEntity(
        roomId = roomId,
        endpointId = endpointId,
        endpointTicket = ticket,
        deviceId = null,
        displayName = "Peer",
        lastSeenAtMs = null,
    )

    private fun IrohOperationRecord.reference() = IrohInventoryReference(domain, id)

    private fun IrohOperationRecord.toEntityForTest(roomId: String) = IrohOperationEntity(
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

    private fun IrohOperationEntity.toRecordForTest() = IrohOperationRecord(
        domain = IrohDomain.valueOf(domain),
        deviceId = originDeviceId,
        operation = IrohJson.strict.parseToJsonElement(operationJson).jsonObject,
    )

    private data class ReplicationSettingsEntityForTest(
        val mode: String,
        val activeRoomId: String?,
    ) {
        companion object {
            fun iroh(roomId: String) = ReplicationSettingsEntityForTest(
                mode = ReplicationMode.IROH.name,
                activeRoomId = roomId,
            )
        }
    }

    private companion object {
        const val NowMs = 1_767_225_700_000L
    }
}
