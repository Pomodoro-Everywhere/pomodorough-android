package me.egigoka.pomodorough.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceDaoWiringTest {
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun focusedInterfacesShareCompatibilityAccessorDatabase() = runBlocking {
        val aggregate = database.timerDao()
        val workspace: TimerWorkspaceDao = aggregate
        val centralizedSync: CentralizedSyncDao = aggregate
        val settingsDao: ReplicationSettingsDao = aggregate
        val rooms: IrohRoomMetadataDao = aggregate
        val peers: IrohPeersDao = aggregate
        val records: IrohRecordsDao = aggregate
        val inventory: IrohInventoryDao = aggregate
        val conflicts: IrohConflictsDao = aggregate
        val state = LocalStateEntity(deviceId = "device", settingsJson = "{}")
        workspace.insertState(state)
        assertEquals(state, centralizedSync.localState())

        val settings = ReplicationSettingsEntity(mode = "IROH", activeRoomId = RoomId)
        settingsDao.upsertReplicationSettings(settings)
        assertEquals(settings, aggregate.replicationSettings())

        rooms.insertIrohRoom(room(RoomId))
        peers.upsertIrohPeer(peer(RoomId))
        records.insertNewIrohOperations(listOf(operation(RoomId)))
        conflicts.upsertIrohConflict(conflict(RoomId))

        assertEquals(OperationId, inventory.irohOperationPage(
            RoomId,
            afterDomain = null,
            afterId = null,
            limit = 1,
        ).single().operationId)
        assertEquals(PeerId, aggregate.irohPeers(RoomId).single().endpointId)
        assertEquals(OperationId, aggregate.irohConflict(RoomId)?.operationId)
    }

    @Test
    fun joinedRoomTransactionRollsBackRoomWhenPeerInsertFails() = runBlocking {
        val transactions: IrohRoomTransactionsDao = database.timerDao()

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { transactions.prepareJoinedIrohRoom(room(RoomId), peer("missing-room")) }
        }

        assertNull(database.timerDao().irohRoom(RoomId))
    }

    private fun room(roomId: String) = IrohRoomEntity(
        roomId = roomId,
        roomName = "Room",
        encryptedRoomSecret = byteArrayOf(1),
        returnStateJson = "{}",
        roomStateJson = "{}",
        createdAtMs = 1,
    )

    private fun peer(roomId: String) = IrohPeerEntity(
        roomId = roomId,
        endpointId = PeerId,
        endpointTicket = "ticket",
        deviceId = null,
        displayName = null,
        lastSeenAtMs = null,
    )

    private fun operation(roomId: String) = IrohOperationEntity(
        roomId = roomId,
        domain = "timer",
        operationId = OperationId,
        originDeviceId = "remote-device",
        operationJson = "{}",
        digest = "digest",
        hlcWallMs = 1,
        hlcCounter = 0,
        deviceSequence = 1,
    )

    private fun conflict(roomId: String) = IrohConflictEntity(
        roomId = roomId,
        domain = "timer",
        operationId = OperationId,
        localDigest = "digest",
        receivedDigest = "other-digest",
        detectedAtMs = 1,
    )

    private companion object {
        const val RoomId = "room-id"
        const val PeerId = "peer-id"
        const val OperationId = "operation-id"
    }
}
