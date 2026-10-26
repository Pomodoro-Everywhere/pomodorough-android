package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohOperationEntity
import me.egigoka.pomodorough.data.local.IrohRecordsDao
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.IrohRoomMetadataDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R52-A02: room creation retains the centralized safe-base durations.
 * The room projects 35 minutes, but the copied row keeps the centralized
 * 25-minute safe base, so the repository displays and starts 25 minutes.
 * Room construction and projection updates must clear centralized
 * safe-base metadata; the room owns its canonical settings instead.
 */
class R52A02RoomSafeBaseOwnershipTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val persistence by lazy {
        IrohRoomProjectionPersistence(
            rooms = stubRooms(),
            records = stubRecords(),
            sharedCoreDispatch = { operation, input -> core.dispatch(operation, input) },
            currentTimeMillis = { HeadWallMs },
        )
    }

    @Test
    fun genesisStateClearsCentralizedSafeBaseKeepsRoomDurations() {
        val source = sourceWithCentralizedBase(TimerSettings())
        val result = persistence.makeGenesisState(source, genesis35())

        assertRoomDurations(result.local.settingsJson)
        assertSafeBaseCleared(result.local)
        assertTrue(result.commands.isEmpty())
        assertTrue(result.durationOperations.isEmpty())
        assertEquals(HeadWallMs, result.local.hlcWallMs)
        assertEquals(listOf("command-central-1"), source.commands.map { it.id })
    }

    @Test
    fun roomDeviceStateClearsCentralizedSafeBaseKeepsRoomDurations() {
        val source = sourceWithCentralizedBase(TimerSettings(focusMinutes = 35))
        val result = persistence.makeRoomDeviceState(source)

        assertRoomDurations(result.local.settingsJson)
        assertSafeBaseCleared(result.local)
        assertEquals(source.local.deviceSequence, result.local.deviceSequence)
    }

    @Test
    fun roomProjectionUpdateClearsInheritedSafeBase() = runBlocking {
        val roomId = "room-r52a02"
        val stale = sourceWithCentralizedBase(TimerSettings(focusMinutes = 35))
        val room = IrohRoomEntity(
            roomId = roomId,
            roomName = "Room",
            encryptedRoomSecret = ByteArray(32),
            returnStateJson = WorkspaceCodec.encode(stale),
            roomStateJson = WorkspaceCodec.encode(stale),
            createdAtMs = HeadWallMs,
            activated = true,
        )
        val projected = IrohRoomProjectionPersistence(
            rooms = stubRooms(room),
            records = stubRecords(),
            sharedCoreDispatch = { operation, input -> core.dispatch(operation, input) },
            currentTimeMillis = { HeadWallMs },
        ).project(
            roomId = roomId,
            records = listOf(IrohOperationRecord.genesis("device-1", genesis35()).toIrohEntity(roomId)),
            baseSnapshot = stale,
        )

        assertRoomDurations(projected.snapshot.local.settingsJson)
        assertSafeBaseCleared(projected.snapshot.local)
    }

    private fun sourceWithCentralizedBase(settings: TimerSettings): LocalWorkspaceSnapshot {
        val local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 7,
            settingsJson = IrohJson.strict.encodeToString(TimerSettings.serializer(), settings),
            safeBaseDurationsJson = IrohJson.strict.encodeToString(DurationsMs.serializer(), DurationsMs()),
            safeCanonicalHeadWallMs = HeadWallMs,
            safeCanonicalHeadCounter = 0,
        )
        return LocalWorkspaceSnapshot(local = local, commands = listOf(centralizedCommand()))
    }

    private fun centralizedCommand() = PendingCommandEntity(
        id = "command-central-1",
        deviceSequence = 7,
        timerId = "timer-central-1",
        type = "start",
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = HeadWallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private fun genesis35() = IrohGenesis(
        canonicalTimer = null,
        history = emptyList(),
        tasks = emptyList(),
        durationsMs = DurationsMs(focus = 2_100_000, shortBreak = 300_000, longBreak = 900_000),
        autoStartBreaks = false,
        selectedTaskId = null,
        hlcWallMs = HeadWallMs,
        hlcCounter = 0,
    )

    private fun assertRoomDurations(settingsJson: String) {
        val settings = IrohJson.strict.decodeFromString(TimerSettings.serializer(), settingsJson)
        assertEquals(35, settings.focusMinutes)
        assertEquals(2_100_000L, settings.durationMsFor(TimerPhase.Focus))
    }

    private fun assertSafeBaseCleared(local: LocalStateEntity) {
        assertNull(local.safeBaseDurationsJson)
        assertNull(local.safeBaseSelectedTaskId)
        assertNull(local.safeCanonicalHeadWallMs)
        assertNull(local.safeCanonicalHeadCounter)
    }

    private fun stubRooms(room: IrohRoomEntity? = null) = object : IrohRoomMetadataDao {
        override suspend fun irohRooms() = listOfNotNull(room)
        override suspend fun irohRoomCount() = listOfNotNull(room).size
        override suspend fun irohRoomsCapped(limit: Int) = listOfNotNull(room)
        override suspend fun irohRoom(roomId: String) = room?.takeIf { it.roomId == roomId }
        override suspend fun preferredIrohRoom() = room
        override suspend fun insertIrohRoom(room: IrohRoomEntity) = Unit
        override suspend fun updateIrohRoom(room: IrohRoomEntity) = Unit
        override suspend fun deleteIrohRoom(roomId: String) = Unit
        override suspend fun deleteAllIrohRooms() = Unit
        override suspend fun deleteIncompleteIrohRooms() = Unit
    }

    private fun stubRecords() = object : IrohRecordsDao {
        override suspend fun irohOperations(roomId: String) = emptyList<IrohOperationEntity>()
        override suspend fun irohOperationsCapped(roomId: String, limit: Int) =
            emptyList<IrohOperationEntity>()
        override suspend fun irohOperationCount(roomId: String) = 0
        override suspend fun irohOperation(roomId: String, domain: String, operationId: String) = null
        override suspend fun hasIrohGenesis(roomId: String) = 0
        override suspend fun insertIrohOperations(operations: List<IrohOperationEntity>) =
            emptyList<Long>()
        override suspend fun deleteAllIrohOperations() = Unit
        override suspend fun insertNewIrohOperations(operations: List<IrohOperationEntity>) = Unit
    }

    private companion object {
        const val HeadWallMs = 1_767_225_600_000L
    }
}
