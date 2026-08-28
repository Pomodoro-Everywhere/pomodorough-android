package me.egigoka.pomodorough.data.iroh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.core.SharedCore
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
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var store: IrohRoomStore
    private lateinit var core: SharedCore
    private var failProjection = false
    private var persistentDatabaseName: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        core = SharedCore.fromAssets(context.assets)
        failProjection = false
        store = newStore()
    }

    @After
    fun tearDown() {
        database.close()
        persistentDatabaseName?.let(context::deleteDatabase)
    }

    @Test
    fun roomSwitchPreservesLatestRoomLocalSelectionsAndRestoresCentralWorkspace() = runBlocking {
        val task = requireNotNull(TaskReducer.taskFromTitle("Write tests"))
        val central = state().copy(
            revision = 9,
            historyJson = IrohJson.strict.encodeToString(
                listOf(
                    history(
                        "history-central01",
                        "timer-central01",
                        "2026-01-01T00:00:10Z",
                    ),
                ),
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
    fun roomCreationPreservesDistinctHistoryIdsAndSortsHistoryCanonically() = runBlocking {
        val older = history("history-old01", "timer-old", "2026-01-01T00:00:10Z")
        val newer = history("history-new01", "timer-new", "2026-01-01T00:00:20Z")
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
        assertEquals(newer.id, history.single { it.timerId == newer.timerId }.id)
        assertEquals(older.id, history.single { it.timerId == older.timerId }.id)
        assertTrue(history.none { it.pending })
        assertEquals(null, history.first().commandId)
    }

    @Test
    fun roomPersistencePreservesDistinctHistoryIdInGenesisAndRoomSnapshot() = runBlocking {
        val seeded = history(
            "history-persist01",
            "timer-persist01",
            "2026-01-01T00:00:20Z",
        )
        database.timerDao().insertState(
            state().copy(historyJson = IrohJson.strict.encodeToString(listOf(seeded))),
        )

        val room = store.createRoom("Persistent history").first

        val genesisEntity = database.timerDao().irohOperations(room.roomId)
            .single { it.domain == IrohDomain.genesis.name }
        val genesis = IrohJson.strict.decodeFromString<IrohGenesis>(genesisEntity.operationJson)
        val roomSnapshot = WorkspaceCodec.decode(
            requireNotNull(database.timerDao().irohRoom(room.roomId)).roomStateJson,
        )
        val persistedHistory = IrohJson.strict.decodeFromString<List<HistoryItem>>(
            roomSnapshot.local.historyJson,
        )
        assertEquals(seeded.id, genesis.history.single().id)
        assertEquals(seeded.timerId, genesis.history.single().timerId)
        assertEquals(seeded.id, persistedHistory.single().id)
        assertEquals(seeded.timerId, persistedHistory.single().timerId)
    }

    @Test
    fun roomProjectionPreservesHistoryIdWhenDisplacedTimerReactivatesAndIsDisplacedAgain() =
        runBlocking {
            database.timerDao().insertState(state())
            val secret = ByteArray(32) { it.toByte() }
            val invite = IrohRoomInvite(
                roomId = IrohProtocolV1.roomId(secret),
                roomName = "History projection",
                endpointTicket = "endpoint-ticket-placeholder",
                roomSecret = secret,
            )
            store.prepareJoinedRoom(invite, "endpoint-1")
            val current = CanonicalTimer(
                id = "timer-current01",
                phase = TimerPhase.Focus,
                status = TimerStatus.Paused,
                plannedDurationMs = 60_000,
                elapsedAtAnchorMs = 10_000,
                anchorAt = "2026-01-01T00:00:30Z",
            )
            val displaced = history(
                "history-reactivated01",
                "timer-reactivated01",
                "2026-01-01T00:00:20Z",
            ).copy(status = TimerStatus.Superseded, completedAt = null)
            val genesis = IrohOperationRecord.genesis(
                "device-genesis1",
                IrohGenesis(
                    current,
                    listOf(displaced),
                    emptyList(),
                    DurationsMs(),
                    false,
                    null,
                    0,
                    0,
                ),
            )
            val resume = IrohOperationRecord.timer(
                "device-remote01",
                command(
                    "command-reactivate01",
                    1,
                    displaced.timerId,
                    CommandType.Resume,
                ).copy(
                    occurredAt = "2026-01-01T00:01:20Z",
                    observedElapsedMs = 5_000,
                ),
            )

            store.insertRemoteRecords(invite.roomId, listOf(genesis, resume))
            store.activateJoinedRoom(invite.roomId)

            val reactivated = IrohJson.strict.decodeFromString<CanonicalTimer>(
                requireNotNull(database.timerDao().localState()).canonicalTimerJson!!,
            )
            assertEquals(displaced.timerId, reactivated.id)
            val replacement = IrohOperationRecord.timer(
                "device-remote01",
                command(
                    "command-replacement01",
                    2,
                    "timer-replacement01",
                    CommandType.Start,
                ).copy(
                    occurredAt = "2026-01-01T00:01:30Z",
                    observedElapsedMs = 0,
                ),
            )

            store.insertRemoteRecords(invite.roomId, listOf(replacement))

            val local = requireNotNull(database.timerDao().localState())
            val projectedTimer = IrohJson.strict.decodeFromString<CanonicalTimer>(
                local.canonicalTimerJson!!,
            )
            val projectedHistory = IrohJson.strict.decodeFromString<List<HistoryItem>>(
                local.historyJson,
            )
            val displacedAgain = projectedHistory.single { it.timerId == displaced.timerId }
            assertEquals("timer-replacement01", projectedTimer.id)
            assertEquals(displaced.id, displacedAgain.id)
            assertEquals(TimerStatus.Superseded, displacedAgain.status)
            assertEquals(1, projectedHistory.count { it.timerId == displaced.timerId })
        }

    @Test
    fun leaveAndRejoinPreserveDistinctHistoryId() = runBlocking {
        val seeded = history(
            "history-rejoin01",
            "timer-rejoin01",
            "2026-01-01T00:00:20Z",
        )
        database.timerDao().insertState(
            state().copy(historyJson = IrohJson.strict.encodeToString(listOf(seeded))),
        )
        val room = store.createRoom("Rejoin history").first

        store.leaveActiveRoom()
        store.setMode(ReplicationMode.IROH)

        val rejoined = IrohJson.strict.decodeFromString<List<HistoryItem>>(
            requireNotNull(database.timerDao().localState()).historyJson,
        ).single()
        val persisted = IrohJson.strict.decodeFromString<List<HistoryItem>>(
            WorkspaceCodec.decode(
                requireNotNull(database.timerDao().irohRoom(room.roomId)).roomStateJson,
            ).local.historyJson,
        ).single()
        assertEquals(seeded.id, rejoined.id)
        assertEquals(seeded.timerId, rejoined.timerId)
        assertEquals(seeded.id, persisted.id)
        assertEquals(seeded.timerId, persisted.timerId)
    }

    @Test
    fun processRestartPreservesDistinctHistoryIdAcrossRoomReactivation() = runBlocking {
        usePersistentDatabase(PersistentDatabaseName)
        val seeded = history(
            "history-restart01",
            "timer-restart01",
            "2026-01-01T00:00:20Z",
        )
        database.timerDao().insertState(
            state().copy(historyJson = IrohJson.strict.encodeToString(listOf(seeded))),
        )
        val room = store.createRoom("Restart history").first
        store.setMode(ReplicationMode.OFFLINE)

        restartPersistentDatabase()
        store.setMode(ReplicationMode.IROH)

        assertEquals(room.roomId, store.activeRoom()?.roomId)
        val restarted = IrohJson.strict.decodeFromString<List<HistoryItem>>(
            requireNotNull(database.timerDao().localState()).historyJson,
        ).single()
        val persisted = IrohJson.strict.decodeFromString<List<HistoryItem>>(
            WorkspaceCodec.decode(
                requireNotNull(database.timerDao().irohRoom(room.roomId)).roomStateJson,
            ).local.historyJson,
        ).single()
        assertEquals(seeded.id, restarted.id)
        assertEquals(seeded.timerId, restarted.timerId)
        assertEquals(seeded.id, persisted.id)
        assertEquals(seeded.timerId, persisted.timerId)
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
    fun persistedLegacyGenesisWithoutSelectedTaskActivatesWithNullSelection() = runBlocking {
        val original = state().copy(selectedTaskId = "stale-local-selection")
        database.timerDao().insertState(original)
        val secret = ByteArray(32) { it.toByte() }
        val invite = IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Legacy room",
            endpointTicket = "endpoint-ticket-placeholder",
            roomSecret = secret,
        )
        store.prepareJoinedRoom(invite, "endpoint-1")
        val operation = IrohJson.strict.parseToJsonElement(
            """{"canonicalTimer":null,"history":[],"tasks":[],"durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},"autoStartBreaks":false,"hlcWallMs":0,"hlcCounter":0}""",
        ).jsonObject
        val record = IrohOperationRecord(IrohDomain.genesis, "device-legacy1", operation)
        database.timerDao().insertIrohOperations(
            listOf(
                me.egigoka.pomodorough.data.local.IrohOperationEntity(
                    roomId = invite.roomId,
                    domain = IrohDomain.genesis.name,
                    operationId = "genesis",
                    originDeviceId = record.deviceId,
                    operationJson = operation.toString(),
                    digest = record.digest(),
                    hlcWallMs = 0,
                    hlcCounter = 0,
                    deviceSequence = null,
                ),
            ),
        )

        store.activateJoinedRoom(invite.roomId)

        assertEquals(null, database.timerDao().localState()?.selectedTaskId)
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
                    IrohGenesis(null, emptyList(), emptyList(), DurationsMs(), false, null, 0, 0),
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
    fun coreProjectionFailureRollsBackLocalCapture() = runBlocking {
        database.timerDao().insertState(state())
        val room = store.createRoom(null).first
        val pending = PendingCommandEntity.from(
            command("command-rollback", 1, "timer-rollback", CommandType.Start),
        )
        database.timerDao().insertCommand(pending)
        val localBefore = requireNotNull(database.timerDao().localState())
        val roomBefore = requireNotNull(database.timerDao().irohRoom(room.roomId))
        val operationsBefore = database.timerDao().irohOperations(room.roomId)
        failProjection = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.captureLocalOperations() }
        }

        assertEquals(localBefore, database.timerDao().localState())
        assertEquals(listOf(pending), database.timerDao().pendingCommands())
        val roomAfter = requireNotNull(database.timerDao().irohRoom(room.roomId))
        assertTrue(roomBefore.encryptedRoomSecret.contentEquals(roomAfter.encryptedRoomSecret))
        assertEquals(
            roomBefore.copy(encryptedRoomSecret = roomAfter.encryptedRoomSecret),
            roomAfter,
        )
        assertEquals(operationsBefore, database.timerDao().irohOperations(room.roomId))
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
            id = "history-seeded01",
            timerId = "timer-seeded01",
            phase = TimerPhase.Focus,
            status = TimerStatus.Superseded,
            plannedDurationMs = 60_000,
            endedAt = "2026-01-01T00:01:00Z",
        )
        val genesis = IrohOperationRecord.genesis(
            "device-genesis1",
            IrohGenesis(null, listOf(seeded), emptyList(), DurationsMs(), false, null, 0, 0),
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
    fun selectedTaskDeletionEmitsExplicitNullIntoIrohLogAndPreservesHistoryIdentity() = runBlocking {
        val task = requireNotNull(TaskReducer.taskFromTitle("Delete selected Iroh task"))
        val taskHistory = history(
            "history-task01",
            "timer-task-history",
            "2026-01-01T00:01:00Z",
        ).copy(taskId = task.id)
        database.timerDao().insertState(
            state().copy(
                historyJson = IrohJson.strict.encodeToString(listOf(taskHistory)),
                tasksJson = IrohJson.strict.encodeToString(listOf(task)),
                knownTasksJson = IrohJson.strict.encodeToString(listOf(task)),
                selectedTaskId = task.id,
            ),
        )
        val room = store.createRoom(null).first
        val deletion = TaskOperation(
            id = "task-operation-delete-iroh",
            taskId = task.id,
            type = TaskOperationType.Delete,
            title = null,
            occurredAt = "2026-01-01T00:01:40Z",
            hlcWallMs = NowMs,
            hlcCounter = 1,
        )
        val clearSelection = SelectedTaskOperation(
            id = "selected-task-clear-iroh",
            taskId = null,
            occurredAt = "2026-01-01T00:01:40Z",
            hlcWallMs = NowMs,
            hlcCounter = 2,
        )
        val local = requireNotNull(database.timerDao().localState())
        database.timerDao().persistTaskOperation(
            PendingTaskOperationEntity.from(deletion),
            local.copy(
                selectedTaskId = null,
                hlcWallMs = NowMs,
                hlcCounter = 2,
            ),
            PendingSelectedTaskOperationEntity.from(clearSelection),
        )

        store.captureLocalOperations()

        val selectedRecord = database.timerDao().irohOperations(room.roomId)
            .single { it.domain == IrohDomain.selectedTask.name }
        assertTrue(IrohJson.strict.parseToJsonElement(selectedRecord.operationJson).jsonObject["taskId"] is kotlinx.serialization.json.JsonNull)
        val projected = requireNotNull(database.timerDao().localState())
        assertEquals(null, projected.selectedTaskId)
        val projectedHistory = IrohJson.strict.decodeFromString<List<HistoryItem>>(projected.historyJson)
        assertEquals(taskHistory.id, projectedHistory.single().id)
        assertEquals(taskHistory.timerId, projectedHistory.single().timerId)
        assertEquals(task.id, projectedHistory.single().taskId)
    }

    @Test
    fun accountClearScrubsEveryRoomSnapshotWhenIrohIsInactive() = runBlocking {
        val account = state().copy(
            userJson = "{\"id\":\"user-1\"}",
            ownerUserId = "user-1",
            revision = 8,
        )
        database.timerDao().insertState(account)
        val first = store.createRoom("First").first
        store.setMode(ReplicationMode.OFFLINE)
        val second = store.createRoom("Second").first
        store.setMode(ReplicationMode.CENTRALIZED)

        store.clearAccountData()

        assertEquals(null, database.timerDao().localState()?.ownerUserId)
        listOf(first.roomId, second.roomId).forEach { roomId ->
            val room = requireNotNull(database.timerDao().irohRoom(roomId))
            listOf(room.returnStateJson, room.roomStateJson).forEach { encoded ->
                val snapshot = WorkspaceCodec.decode(encoded)
                assertEquals(null, snapshot.local.ownerUserId)
                assertEquals(null, snapshot.local.userJson)
            }
        }
    }

    @Test
    fun accountClearScrubsCentralReturnWorkspaceWithoutDeletingRoomLog() = runBlocking {
        val account = state().copy(
            userJson = "{\"id\":\"user-1\"}",
            ownerUserId = "user-1",
            revision = 8,
            historyJson = IrohJson.strict.encodeToString(
                listOf(
                    history(
                        "history-account01",
                        "timer-account01",
                        "2026-01-01T00:01:00Z",
                    ),
                ),
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

    private fun newStore() = IrohRoomStore(
        database.timerDao(),
        IrohSecretVault(context),
        { operation, input ->
            if (failProjection && operation == "projection.apply.v2") {
                throw IllegalStateException("projection unavailable")
            }
            core.dispatch(operation, input)
        },
        currentTimeMillis = { NowMs },
    )

    private fun usePersistentDatabase(name: String) {
        database.close()
        context.deleteDatabase(name)
        persistentDatabaseName = name
        database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        store = newStore()
    }

    private fun restartPersistentDatabase() {
        val name = requireNotNull(persistentDatabaseName)
        database.close()
        database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        store = newStore()
    }

    private fun state() = LocalStateEntity(
        deviceId = "device-1",
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )

    private fun history(id: String, timerId: String, endedAt: String) = HistoryItem(
        id = id,
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
        observedElapsedMs = if (type == CommandType.Start) 0 else 60_000,
    )

    private companion object {
        const val NowMs = 1_767_225_700_000L
        const val PersistentDatabaseName = "iroh-history-identity-p1-3.db"
    }
}
