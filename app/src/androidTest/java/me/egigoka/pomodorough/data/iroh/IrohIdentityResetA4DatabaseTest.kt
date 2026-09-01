package me.egigoka.pomodorough.data.iroh

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohConflictEntity
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohIdentityResetA4DatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var store: IrohRoomStore
    private lateinit var storageId: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        storageId = UUID.randomUUID().toString()
        store = IrohRoomStore(
            database.timerDao(),
            IrohSecretVault(context, storageId),
            SharedCore.fromAssets(context.assets)::dispatch,
            currentTimeMillis = { NowMs },
        )
    }

    @After
    fun tearDown() {
        database.close()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "pomodorough-iroh-secret-key-v1-$storageId"
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val preferences = context.getSharedPreferences("pomodorough_iroh-$storageId", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun confirmedResetDeletesRoomGraphAndRestoresPreviousWorkspace() = runBlocking {
        val previous = state(revision = 17)
        database.timerDao().insertState(previous)
        val room = store.createRoom("Reset scope").first
        addRoomGraph(room.roomId)

        store.resetIdentityData()

        assertTrue(database.timerDao().irohRooms().isEmpty())
        assertTrue(database.timerDao().irohOperations(room.roomId).isEmpty())
        assertTrue(database.timerDao().irohPeers(room.roomId).isEmpty())
        assertEquals(null, database.timerDao().irohConflict(room.roomId))
        assertEquals(previous, database.timerDao().localWorkspaceSnapshot().local)
        assertEquals(offlineSettings(), database.timerDao().replicationSettings())
    }

    @Test
    fun resetTransactionRollsBackRoomGraphSettingsAndWorkspaceOnFailure() = runBlocking {
        database.timerDao().insertState(state(revision = 4))
        val room = store.createRoom("Rollback scope").first
        addRoomGraph(room.roomId)
        val roomBefore = requireNotNull(database.timerDao().irohRoom(room.roomId))
        val settingsBefore = database.timerDao().replicationSettings()
        val workspaceBefore = database.timerDao().localWorkspaceSnapshot()
        val operationsBefore = database.timerDao().irohOperations(room.roomId)
        val duplicate = PendingCommandEntity.from(command("duplicate", 41))
        val invalid = workspaceBefore.copy(commands = listOf(duplicate, duplicate))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.timerDao().resetIrohIdentity(
                    listOf(room.roomId),
                    invalid,
                    offlineSettings(),
                )
            }
        }

        val roomAfter = requireNotNull(database.timerDao().irohRoom(room.roomId))
        assertArrayEquals(roomBefore.encryptedRoomSecret, roomAfter.encryptedRoomSecret)
        assertEquals(roomBefore.copy(encryptedRoomSecret = roomAfter.encryptedRoomSecret), roomAfter)
        assertEquals(settingsBefore, database.timerDao().replicationSettings())
        assertEquals(workspaceBefore, database.timerDao().localWorkspaceSnapshot())
        assertEquals(operationsBefore, database.timerDao().irohOperations(room.roomId))
        assertEquals(1, database.timerDao().irohPeers(room.roomId).size)
        assertNotNull(database.timerDao().irohConflict(room.roomId))
    }

    private suspend fun addRoomGraph(roomId: String) {
        database.timerDao().upsertIrohPeer(
            IrohPeerEntity(roomId, "endpoint-a4", "ticket-a4", null, "Peer", null),
        )
        database.timerDao().upsertIrohConflict(
            IrohConflictEntity(roomId, "timer", "operation-a4", "local", "remote", NowMs),
        )
    }

    private fun state(revision: Long) = LocalStateEntity(
        deviceId = "device-a4",
        revision = revision,
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )

    private fun command(id: String, sequence: Long) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = "timer-$id",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 60_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = NowMs + sequence,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private fun offlineSettings() = ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name)

    private companion object {
        const val NowMs = 1_767_225_700_000L
    }
}
