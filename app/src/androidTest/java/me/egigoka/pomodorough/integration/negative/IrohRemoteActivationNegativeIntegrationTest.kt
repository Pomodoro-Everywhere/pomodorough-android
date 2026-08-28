package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohRemoteActivationNegativeIntegrationTest {
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun remoteActivationRejectsUncapturedSelectionAndLeavesRoomRecordAndWorkspaceUntouched() =
        runBlocking {
            val dao = database.timerDao()
            val originalState = state("original", 1)
            val pendingSelection = PendingSelectedTaskOperationEntity(
                id = "selection-pending", taskId = "task-pending", occurredAt = At,
                hlcWallMs = 1, hlcCounter = 0,
            )
            val originalRoom = room("Original")
            dao.insertState(originalState)
            dao.insertSelectedTaskOperation(pendingSelection)
            dao.insertIrohRoom(originalRoom)
            val originalWorkspace = dao.localWorkspaceSnapshot()
            val remoteRecord = operation("remote-operation")
            val replacement = LocalWorkspaceSnapshot(
                local = state("replacement", 9),
                commands = emptyList(), taskOperations = emptyList(),
                durationOperations = emptyList(), autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(), bootstrapResolution = null,
            )

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    dao.insertRemoteIrohRecordsAndActivate(
                        originalRoom.copy(roomName = "Changed"),
                        listOf(remoteRecord),
                        replacement,
                    )
                }
            }

            assertEquals(
                "Local Iroh operations must be captured before applying remote records",
                error.message,
            )
            assertRoomEquals(originalRoom, requireNotNull(dao.irohRoom(RoomId)))
            assertEquals(emptyList<IrohOperationEntity>(), dao.irohOperations(RoomId))
            assertEquals(originalWorkspace, dao.localWorkspaceSnapshot())
        }

    @Test
    fun atomicNewRecordInsertionRollsBackEarlierRecordWhenLaterRecordConflicts() = runBlocking {
        val dao = database.timerDao()
        dao.insertState(state("original", 1))
        val originalRoom = room("Original")
        dao.insertIrohRoom(originalRoom)
        val existing = operation("existing", sequence = 7)
        dao.insertIrohRecordsAtomically(listOf(existing))
        val first = operation("first", sequence = 8)
        val conflicting = operation("second", sequence = 7)

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                dao.insertIrohRecordsAtomically(listOf(first, conflicting))
            }
        }

        assertRoomEquals(originalRoom, requireNotNull(dao.irohRoom(RoomId)))
        assertEquals(listOf(existing), dao.irohOperations(RoomId))
    }

    private fun assertRoomEquals(expected: IrohRoomEntity, actual: IrohRoomEntity) {
        assertEquals(expected.roomId, actual.roomId)
        assertEquals(expected.roomName, actual.roomName)
        assertArrayEquals(expected.encryptedRoomSecret, actual.encryptedRoomSecret)
        assertEquals(expected.returnStateJson, actual.returnStateJson)
        assertEquals(expected.roomStateJson, actual.roomStateJson)
        assertEquals(expected.createdAtMs, actual.createdAtMs)
    }

    private fun state(prefix: String, revision: Long) = LocalStateEntity(
        deviceId = "device-$prefix", revision = revision, settingsJson = "{}",
    )

    private fun room(name: String) = IrohRoomEntity(
        roomId = RoomId, roomName = name, encryptedRoomSecret = byteArrayOf(1),
        returnStateJson = "{}", roomStateJson = "{}", createdAtMs = 1,
    )

    private fun operation(id: String, sequence: Long = 1) = IrohOperationEntity(
        roomId = RoomId, domain = "timer", operationId = id,
        originDeviceId = "remote-device", operationJson = "{}", digest = "digest-$id",
        hlcWallMs = sequence, hlcCounter = 0, deviceSequence = sequence,
    )

    private companion object {
        const val RoomId = "room-atomic"
        const val At = "2026-01-01T00:00:00Z"
    }
}
