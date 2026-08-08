package me.egigoka.pomodorough.data.iroh

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohRoomStoreTest {
    private lateinit var database: PomodoroughDatabase
    private lateinit var store: IrohRoomStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        store = IrohRoomStore(
            database.timerDao(),
            IrohSecretVault(context),
            currentTimeMillis = { NowMs },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomSwitchPreservesLatestRoomLocalSelectionsAndRestoresCentralWorkspace() = runBlocking {
        val task = requireNotNull(TaskReducer.taskFromTitle("Write tests"))
        val central = state().copy(
            revision = 9,
            historyJson = IrohJson.strict.encodeToString(
                listOf(history("central-history", "2026-01-01T00:00:10Z")),
            ),
            tasksJson = IrohJson.strict.encodeToString(listOf(task)),
            knownTasksJson = IrohJson.strict.encodeToString(listOf(task)),
        )
        database.timerDao().insertState(central)
        store.createRoom("Design desk")
        val roomLocal = requireNotNull(database.timerDao().localState()).copy(
            selectedTaskId = task.id,
            settingsJson = IrohJson.strict.encodeToString(
                TimerSettings(selectedPhase = TimerPhase.LongBreak),
            ),
        )
        database.timerDao().updateState(roomLocal)

        store.captureLocalOperations()
        store.setMode(ReplicationMode.OFFLINE)
        assertEquals(central, database.timerDao().localState())

        store.setMode(ReplicationMode.IROH)
        val restored = requireNotNull(database.timerDao().localState())
        assertEquals(task.id, restored.selectedTaskId)
        assertEquals(
            TimerPhase.LongBreak,
            IrohJson.strict.decodeFromString<TimerSettings>(restored.settingsJson).selectedPhase,
        )
        assertEquals(0L, restored.revision)
    }

    @Test
    fun roomGenesisProjectsDeadlineAndSortsHistoryCanonically() = runBlocking {
        val older = history("timer-old", "2026-01-01T00:00:10Z")
        val newer = history("timer-new", "2026-01-01T00:00:20Z")
        database.timerDao().insertState(
            state().copy(
                canonicalTimerJson = IrohJson.strict.encodeToString(
                    CanonicalTimer(
                        id = "timer-live",
                        phase = TimerPhase.Focus,
                        status = TimerStatus.Running,
                        plannedDurationMs = 60_000,
                        elapsedAtAnchorMs = 30_000,
                        anchorAt = "2026-01-01T00:00:00Z",
                    ),
                ),
                historyJson = IrohJson.strict.encodeToString(listOf(older, newer)),
            ),
        )

        store.createRoom(null)

        val local = requireNotNull(database.timerDao().localState())
        val timer = IrohJson.strict.decodeFromString<CanonicalTimer>(local.canonicalTimerJson!!)
        val history = IrohJson.strict.decodeFromString<List<HistoryItem>>(local.historyJson)
        assertEquals(TimerStatus.Completed, timer.status)
        assertEquals(listOf("timer-live", "timer-new", "timer-old"), history.map { it.timerId })
        assertEquals(null, history.first().commandId)
    }

    @Test
    fun partialJoinPreparationWithoutGenesisIsIdempotentAndKeepsOriginalReturnWorkspace() =
        runBlocking {
        val original = state().copy(revision = 7)
        database.timerDao().insertState(original)
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Shared room",
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret,
        )
        val first = store.prepareJoinedRoom(invite, "endpoint-1")
        database.timerDao().updateState(original.copy(revision = 99))

        val second = store.prepareJoinedRoom(invite, "endpoint-1")

        assertTrue(first.second)
        assertFalse(second.second)
        assertEquals(first.first.returnStateJson, second.first.returnStateJson)
        assertNotEquals(
            99L,
            WorkspaceCodec.decode(second.first.returnStateJson).local.revision,
        )
    }

    @Test
    fun rejoiningCompletedInactiveRoomRefreshesReturnWorkspace() = runBlocking {
        val original = state().copy(revision = 7)
        database.timerDao().insertState(original)
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Shared room",
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret,
        )
        store.prepareJoinedRoom(invite, "endpoint-1")
        store.insertRemoteRecords(
            invite.roomId,
            listOf(
                IrohOperationRecord.genesis(
                    "device-2",
                    IrohGenesis(null, emptyList(), emptyList(), DurationsMs(), false, 0, 0),
                ),
            ),
        )
        store.activateJoinedRoom(invite.roomId)
        store.setMode(ReplicationMode.OFFLINE)
        val current = original.copy(revision = 99)
        database.timerDao().updateState(current)

        val prepared = store.prepareJoinedRoom(invite, "endpoint-1").first
        store.activateJoinedRoom(invite.roomId)
        store.leaveActiveRoom()

        assertEquals(99L, WorkspaceCodec.decode(prepared.returnStateJson).local.revision)
        assertEquals(current, database.timerDao().localState())
    }

    @Test
    fun rejoiningCurrentlyActiveRoomKeepsOriginalReturnWorkspace() = runBlocking {
        val original = state().copy(revision = 7)
        database.timerDao().insertState(original)
        val created = store.createRoom("Active room").first
        val secret = requireNotNull(store.activeRoomSecret())
        val invite = IrohRoomInvite(
            roomId = created.roomId,
            roomName = created.roomName,
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret.copyOf(),
        )
        secret.fill(0)
        val before = requireNotNull(database.timerDao().irohRoom(created.roomId)).returnStateJson

        val prepared = store.prepareJoinedRoom(invite, "endpoint-1").first
        invite.roomSecret.fill(0)

        assertEquals(before, prepared.returnStateJson)
        assertEquals(7L, WorkspaceCodec.decode(prepared.returnStateJson).local.revision)
    }

    @Test
    fun duplicateDeviceSequenceAbortsEntireRemoteBatch() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        val first = IrohOperationRecord.timer(
            "device-2",
            command("command-a", 1, "timer-one", CommandType.Start),
        )
        val second = IrohOperationRecord.timer(
            "device-2",
            command("command-b", 1, "timer-two", CommandType.Start),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.insertRemoteRecords(room.roomId, listOf(first, second)) }
        }

        assertEquals(
            listOf("genesis"),
            database.timerDao().irohOperations(room.roomId).map { it.operationId },
        )
    }

    @Test
    fun generatedStartWaitsForSourceCompletionBeforeEnteringImmutableLog() = runBlocking {
        val running = CanonicalTimer(
            id = "timer-focus",
            phase = TimerPhase.Focus,
            status = TimerStatus.Running,
            plannedDurationMs = 60_000,
            elapsedAtAnchorMs = 0,
            anchorAt = "2026-01-01T00:00:00Z",
        )
        database.timerDao().insertState(
            state().copy(canonicalTimerJson = IrohJson.strict.encodeToString(running)),
        )
        val room = store.createRoom(null).first
        val finish = command("finish-001", 1, "timer-focus", CommandType.Finish)
        val generated = command("start-0001", 2, "timer-break", CommandType.Start).copy(
            phase = TimerPhase.ShortBreak,
            plannedDurationMs = 300_000,
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(finish))
        database.timerDao().insertCommand(PendingCommandEntity.from(generated, finish.id))

        store.captureLocalOperations()

        val operations = database.timerDao().irohOperations(room.roomId)
        assertTrue(operations.any { it.operationId == finish.id })
        assertTrue(operations.any { it.operationId == generated.id })
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    @Test
    fun genesisAttributesLocallyOwnedTimerWithoutPendingStart() = runBlocking {
        val timer = CanonicalTimer(
            id = "timer-owned0001",
            phase = TimerPhase.Focus,
            status = TimerStatus.Paused,
            plannedDurationMs = 60_000,
            elapsedAtAnchorMs = 10_000,
            anchorAt = "2026-01-01T00:01:00Z",
        )
        database.timerDao().insertState(
            state().copy(
                ownedTimerId = timer.id,
                canonicalTimerJson = IrohJson.strict.encodeToString(timer),
            ),
        )

        store.createRoom(null)

        val projected = IrohJson.strict.decodeFromString<CanonicalTimer>(
            requireNotNull(database.timerDao().localState()).canonicalTimerJson!!,
        )
        assertEquals("device-1", projected.startedByDeviceId)
    }

    @Test
    fun resumeRestoresOriginalStarterAndAttributesLatestIntent() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        val startA = IrohOperationRecord.timer(
            "device-a0001",
            command("command-start-a", 1, "timer-a0001", CommandType.Start).copy(hlcCounter = 1),
        )
        val startB = IrohOperationRecord.timer(
            "device-b0001",
            command("command-start-b", 1, "timer-b0001", CommandType.Start).copy(hlcCounter = 2),
        )
        val resumeA = IrohOperationRecord.timer(
            "device-c0001",
            command("command-resume-a", 1, "timer-a0001", CommandType.Resume).copy(hlcCounter = 3),
        )

        store.insertRemoteRecords(room.roomId, listOf(startA, startB, resumeA))
        store.refreshProjection(room.roomId)

        val projected = IrohJson.strict.decodeFromString<CanonicalTimer>(
            requireNotNull(database.timerDao().localState()).canonicalTimerJson!!,
        )
        assertEquals("timer-a0001", projected.id)
        assertEquals("device-a0001", projected.startedByDeviceId)
        assertEquals("device-c0001", projected.lastIntent?.deviceId)
    }

    @Test
    fun genesisHistoryResumeUsesGenesisOriginAsStarter() = runBlocking {
        database.timerDao().insertState(state())
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            IrohProtocolV1.roomId(secret),
            null,
            "endpoint-ticket-placeholder",
            secret,
        )
        store.prepareJoinedRoom(invite, "endpoint-1")
        val seeded = HistoryItem(
            id = "timer-seeded01",
            timerId = "timer-seeded01",
            phase = TimerPhase.Focus,
            status = TimerStatus.Superseded,
            plannedDurationMs = 60_000,
            endedAt = "2026-01-01T00:01:00Z",
        )
        val genesis = IrohOperationRecord.genesis(
            "device-genesis1",
            IrohGenesis(null, listOf(seeded), emptyList(), DurationsMs(), false, 0, 0),
        )
        val resume = IrohOperationRecord.timer(
            "device-resume01",
            command("command-resume-seeded", 1, seeded.timerId, CommandType.Resume),
        )

        store.insertRemoteRecords(invite.roomId, listOf(genesis, resume))
        store.activateJoinedRoom(invite.roomId)

        val projected = IrohJson.strict.decodeFromString<CanonicalTimer>(
            requireNotNull(database.timerDao().localState()).canonicalTimerJson!!,
        )
        assertEquals("device-genesis1", projected.startedByDeviceId)
        assertEquals("device-resume01", projected.lastIntent?.deviceId)
    }

    @Test
    fun accountClearScrubsCentralReturnWorkspaceWithoutDeletingRoomLog() = runBlocking {
        val account = state().copy(
            userJson = "{\"id\":\"user-1\"}",
            ownerUserId = "user-1",
            revision = 8,
            historyJson = IrohJson.strict.encodeToString(
                listOf(history("account-history", "2026-01-01T00:01:00Z")),
            ),
        )
        database.timerDao().insertState(account)
        val room = store.createRoom(null).first

        store.clearAccountData()
        store.leaveActiveRoom()

        val restored = requireNotNull(database.timerDao().localState())
        assertEquals(null, restored.ownerUserId)
        assertEquals(null, restored.userJson)
        assertEquals(0L, restored.revision)
        assertEquals("[]", restored.historyJson)
        assertTrue(database.timerDao().irohOperations(room.roomId).isNotEmpty())
    }

    private fun state() = LocalStateEntity(
        deviceId = "device-1",
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )

    private fun history(timerId: String, endedAt: String) = HistoryItem(
        id = timerId,
        timerId = timerId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 60_000,
        completedAt = endedAt,
        endedAt = endedAt,
    )

    private fun command(id: String, sequence: Long, timerId: String, type: String) = TimerCommand(
        id = id,
        deviceSequence = sequence,
        timerId = timerId,
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = 60_000,
        occurredAt = "2026-01-01T00:01:00Z",
        hlcWallMs = NowMs + sequence,
        hlcCounter = 0,
        observedElapsedMs = 60_000,
    )

    private companion object {
        const val NowMs = 1_767_225_700_000L
    }
}
