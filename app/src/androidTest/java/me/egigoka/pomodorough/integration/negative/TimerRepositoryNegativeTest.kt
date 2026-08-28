package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.SelectedTaskAcknowledgement
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.UuidV7
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.integration.TestAuthSession
import me.egigoka.pomodorough.integration.TestRepositoryService
import me.egigoka.pomodorough.integration.awaitState
import me.egigoka.pomodorough.integration.repositoryJson
import me.egigoka.pomodorough.integration.testAutoStartOperation
import me.egigoka.pomodorough.integration.testCommand
import me.egigoka.pomodorough.integration.testDurationOperation
import me.egigoka.pomodorough.integration.testHistory
import me.egigoka.pomodorough.integration.testRepository
import me.egigoka.pomodorough.integration.testState
import me.egigoka.pomodorough.integration.testTimer
import me.egigoka.pomodorough.integration.testUser
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryNegativeTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        TimerAlarmScheduler(context).cancel()
        database.close()
    }

    @Test
    fun terminalCommandsWithoutTimerDoNotMutateQueue() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.finishTimer()
        repository.cancelTimer()
        repository.clearTimer()

        assertNull(repository.state.value.timer)
        assertEquals(0, repository.state.value.pendingCount)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0L, database.timerDao().localState()?.deviceSequence)
    }

    @Test
    fun malformedUuidV7CursorBlocksOnlyNewMutationWithoutChangingState() = runBlocking {
        val initial = testState().copy(lastUuidV7 = "not-a-uuid")
        database.timerDao().insertState(initial)
        val repository = testRepository(context, database.timerDao())

        repository.initialize()
        repository.toggleTimer()

        assertTrue(repository.state.value.ready)
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(initial, database.timerDao().localState())
        assertTrue(repository.state.value.notice?.contains("UUID", ignoreCase = true) == true)
    }

    @Test
    fun uuidV7CursorBehindQueuedMutationBlocksNewMutationWithoutChangingState() = runBlocking {
        val stored = UuidV7.make(timestampMs = 1_000, randomHigh = 0, randomLow = 1)
        val queued = UuidV7.make(timestampMs = 1_000, randomHigh = 0, randomLow = 2)
        val initial = testState().copy(lastUuidV7 = stored.toString())
        val operation = testDurationOperation(
            id = "duration-operation-$queued",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
        )
        database.timerDao().insertState(initial)
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(operation))
        val repository = testRepository(context, database.timerDao())

        repository.initialize()
        repository.toggleTimer()

        assertEquals(initial, database.timerDao().localState())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(operation.id, database.timerDao().pendingDurationOperations().single().id)
        assertTrue(repository.state.value.notice?.contains("behind", ignoreCase = true) == true)
    }

    @Test
    fun generatedBreakBatchRollsBackWhenUuidV7TailExhausts() = runBlocking {
        val cursor = UuidV7.make(
            timestampMs = UuidV7.MaxTimestampMs,
            randomHigh = UuidV7.MaxRandomHigh,
            randomLow = UuidV7.MaxRandomLow - 1,
        )
        val initial = testState(
            timer = testTimer(),
            settings = TimerSettings(autoStartBreaks = true),
        ).copy(lastUuidV7 = cursor.toString())
        database.timerDao().insertState(initial)
        val repository = testRepository(context, database.timerDao())

        repository.initialize()
        repository.finishTimer()

        assertEquals(initial, database.timerDao().localState())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(TimerPhase.Focus, repository.state.value.settings.selectedPhase)
        assertTrue(repository.state.value.notice?.contains("UUID", ignoreCase = true) == true)
    }

    @Test
    fun activeTimerRejectsPhaseAndDurationChanges() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()
        repository.toggleTimer()
        val original = repository.state.value.settings

        repository.selectPhase(TimerPhase.LongBreak)
        repository.changeDuration(TimerPhase.Focus, 20)

        assertEquals(original, repository.state.value.settings)
        assertEquals(1, repository.state.value.pendingCount)
        val persisted = repositoryJson.decodeFromString<TimerSettings>(
            requireNotNull(database.timerDao().localState()).settingsJson,
        )
        assertEquals(original.selectedPhase, persisted.selectedPhase)
        assertEquals(original.autoStartBreaks, persisted.autoStartBreaks)
        assertEquals(original.effectiveDurationsMs(), persisted.effectiveDurationsMs())
    }

    @Test
    fun malformedPersistedJsonIsQuarantinedWithoutChangingRawState() = runBlocking {
        val variants = listOf<(LocalStateEntity) -> LocalStateEntity>(
            { it.copy(canonicalTimerJson = "not-json") },
            { it.copy(historyJson = "not-json") },
            { it.copy(settingsJson = "not-json") },
            { it.copy(tasksJson = "not-json") },
            { it.copy(knownTasksJson = "not-json") },
            { it.copy(userJson = "not-json") },
        )
        variants.forEachIndexed { index, mutate ->
            val caseDatabase = Room.inMemoryDatabaseBuilder(
                context,
                PomodoroughDatabase::class.java,
            ).build()
            try {
                val initial = mutate(testState())
                caseDatabase.timerDao().insertState(initial)
                val service = TestRepositoryService()
                val repository = testRepository(
                    context,
                    caseDatabase.timerDao(),
                    service,
                    TestAuthSession(tokensAvailable = true),
                )

                repository.initialize()
                repository.toggleTimer()
                repository.addTask("Blocked")
                repository.changeDuration(TimerPhase.Focus, 1)

                val state = repository.state.value
                assertTrue("variant $index", state.ready)
                assertEquals("variant $index", AuthStatus.SignedOut, state.authStatus)
                assertTrue(
                    "variant $index",
                    state.conflict?.contains("corrupted", ignoreCase = true) == true,
                )
                assertEquals("variant $index", initial, caseDatabase.timerDao().localState())
                assertTrue("variant $index", caseDatabase.timerDao().pendingCommands().isEmpty())
                assertEquals("variant $index", 0, service.bootstrapCalls)
                assertEquals("variant $index", 0, service.syncCalls)
            } finally {
                caseDatabase.close()
            }
        }
    }

    @Test
    fun malformedPersistedClockIsQuarantinedWithoutChangingRawState() = runBlocking {
        val variants = listOf<(LocalStateEntity) -> LocalStateEntity>(
            { it.copy(serverClockOffsetMs = 1L) },
            { it.copy(serverClockUncertaintyMs = 1L) },
            {
                it.copy(
                    serverClockSamplePhysicalMs = 1L,
                    serverClockSampleElapsedRealtimeMs = 1L,
                )
            },
            {
                it.copy(
                    serverClockOffsetMs = 0L,
                    serverClockUncertaintyMs = 0L,
                    serverClockSamplePhysicalMs = 1L,
                )
            },
            { it.copy(serverClockBootId = "orphaned-boot") },
            { it.copy(serverClockOffsetMs = Long.MAX_VALUE, serverClockUncertaintyMs = 0L) },
            { it.copy(serverClockOffsetMs = 0L, serverClockUncertaintyMs = Long.MAX_VALUE) },
        )
        variants.forEachIndexed { index, mutate ->
            val caseDatabase = Room.inMemoryDatabaseBuilder(
                context,
                PomodoroughDatabase::class.java,
            ).build()
            try {
                val initial = mutate(testState())
                caseDatabase.timerDao().insertState(initial)
                val service = TestRepositoryService()
                val repository = testRepository(
                    context,
                    caseDatabase.timerDao(),
                    service,
                    TestAuthSession(tokensAvailable = true),
                )

                repository.initialize()
                repository.toggleTimer()
                repository.addTask("Blocked")

                val state = repository.state.value
                assertTrue("variant $index", state.ready)
                assertEquals("variant $index", AuthStatus.SignedOut, state.authStatus)
                assertTrue("variant $index", state.conflict?.contains("clock", ignoreCase = true) == true)
                assertEquals("variant $index", initial, caseDatabase.timerDao().localState())
                assertTrue("variant $index", caseDatabase.timerDao().pendingCommands().isEmpty())
                assertTrue("variant $index", caseDatabase.timerDao().pendingTaskOperations().isEmpty())
                assertEquals("variant $index", 0, service.bootstrapCalls)
                assertEquals("variant $index", 0, service.syncCalls)
            } finally {
                caseDatabase.close()
            }
        }
    }

    @Test
    fun rejectedSyncKeepsQueueAndSurfacesConflict() = runBlocking {
        val profile = testUser()
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            syncFailure = ApiException(409, "revision conflict")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("revision conflict", repository.state.value.conflict)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(1, service.syncCalls)
    }

    @Test
    fun rejectedSelectedTaskAcknowledgementCannotSilentlyRevertSelection() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Keep selected"))
        val operation = SelectedTaskOperation(
            id = "selected-task-rejected",
            taskId = task.id,
            occurredAt = "2026-01-01T00:00:00.001Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(
            testState(user = profile).copy(
                tasksJson = repositoryJson.encodeToString(listOf(task)),
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
                selectedTaskId = task.id,
            ),
        )
        database.timerDao().persistSelectedTaskOperation(
            PendingSelectedTaskOperationEntity.from(operation),
            requireNotNull(database.timerDao().localState()),
        )
        val service = TestRepositoryService(profile).apply {
            syncResponse = syncResponse.copy(
                revision = 1,
                tasks = listOf(task),
                selectedTaskId = null,
                selectedTaskAcknowledgements = listOf(
                    SelectedTaskAcknowledgement(operation.id, "rejected", "Selected task was deleted"),
                ),
                serverHlcWallMs = 1_767_225_600_100,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                selectedTaskAcknowledgements = emptyList(),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        assertNull(repository.state.value.selectedTaskId)
        assertEquals("Selected task was deleted", repository.state.value.conflict)
        assertEquals(SyncStatus.Conflict, repository.state.value.syncStatus)
        assertTrue(database.timerDao().pendingSelectedTaskOperations().isEmpty())
    }

    @Test
    fun ignoredSelectedTaskConflictRemainsUntilConcurrentSelectionConverges() = runBlocking {
        val profile = testUser()
        val first = requireNotNull(TaskReducer.taskFromTitle("First selection"))
        val second = requireNotNull(TaskReducer.taskFromTitle("Second selection"))
        val third = requireNotNull(TaskReducer.taskFromTitle("Third selection"))
        val tasks = listOf(first, second, third)
        val initialOperation = SelectedTaskOperation(
            id = "selected-task-ignored",
            taskId = first.id,
            occurredAt = "2026-01-01T00:00:00.001Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(
            testState(user = profile).copy(
                tasksJson = repositoryJson.encodeToString(tasks),
                knownTasksJson = repositoryJson.encodeToString(tasks),
                selectedTaskId = first.id,
            ),
        )
        database.timerDao().persistSelectedTaskOperation(
            PendingSelectedTaskOperationEntity.from(initialOperation),
            requireNotNull(database.timerDao().localState()),
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val holdThird = CompletableDeferred<Unit>()
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(
                tasks = tasks,
                selectedTaskId = null,
            )
            syncHandler = { request ->
                when (syncCalls) {
                    1 -> {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        syncResponse.copy(
                            revision = 1,
                            tasks = tasks,
                            selectedTaskId = null,
                            selectedTaskAcknowledgements = listOf(
                                SelectedTaskAcknowledgement(
                                    request.selectedTaskOperations.single().id,
                                    "ignored",
                                    "Selection was superseded",
                                ),
                            ),
                            serverHlcWallMs = 1_767_225_600_100,
                        )
                    }
                    2 -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        syncResponse.copy(
                            revision = 2,
                            tasks = tasks,
                            selectedTaskId = second.id,
                            selectedTaskAcknowledgements = listOf(
                                SelectedTaskAcknowledgement(
                                    request.selectedTaskOperations.single().id,
                                    "applied",
                                    "",
                                ),
                            ),
                            serverHlcWallMs = 1_767_225_600_200,
                        )
                    }
                    else -> {
                        thirdStarted.complete(Unit)
                        holdThird.await()
                        syncResponse.copy(
                            revision = 3,
                            tasks = tasks,
                            selectedTaskId = third.id,
                            selectedTaskAcknowledgements = listOf(
                                SelectedTaskAcknowledgement(
                                    request.selectedTaskOperations.single().id,
                                    "applied",
                                    "",
                                ),
                            ),
                            serverHlcWallMs = 1_767_225_600_300,
                        )
                    }
                }
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        firstStarted.await()
        repository.selectTask(second.id)
        releaseFirst.complete(Unit)
        secondStarted.await()
        awaitState { repository.state.value.conflict == "Selection was superseded" }
        assertEquals("Selection was superseded", repository.state.value.conflict)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(second.id, repository.state.value.selectedTaskId)

        repository.selectTask(third.id)
        releaseSecond.complete(Unit)
        thirdStarted.await()

        assertEquals("Selection was superseded", repository.state.value.conflict)
        assertEquals(SyncStatus.Conflict, repository.state.value.syncStatus)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(third.id, repository.state.value.selectedTaskId)
        assertEquals(
            third.id,
            database.timerDao().pendingSelectedTaskOperations().single().taskId,
        )

        holdThird.complete(Unit)
        awaitState {
            repository.state.value.pendingCount == 0 && repository.state.value.conflict == null
        }
        assertEquals(third.id, repository.state.value.selectedTaskId)
    }

    @Test
    fun incompleteDurationAcknowledgementSetKeepsPendingOperationAndCanonicalState() = runBlocking {
        val profile = testUser()
        val operation = testDurationOperation(
            id = "duration-1",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
        )
        val localDurations = DurationsMs(focus = operation.durationMs)
        database.timerDao().insertState(
            testState(user = profile, settings = TimerSettings().withDurations(localDurations)),
        )
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(focus = 30 * 60_000L),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = localDurations,
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals(
            "Sync returned an invalid duration acknowledgement set",
            repository.state.value.conflict,
        )
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(operation.id, database.timerDao().pendingDurationOperations().single().id)
        assertEquals(operation.durationMs, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun invalidCanonicalDurationsDoNotReplaceLocalState() = runBlocking {
        val profile = testUser()
        database.timerDao().insertState(testState(user = profile))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(focus = 1_500_001),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                durationsMs = DurationsMs(),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync returned invalid canonical durations", repository.state.value.conflict)
        assertEquals(DurationsMs(), repository.state.value.settings.effectiveDurationsMs())
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun foreignCommandAcknowledgementCannotDeleteInFlightNewCommand() = runBlocking {
        val profile = testUser()
        val sent = testCommand("command-sent", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(sent))
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        var inFlightId: String? = null
        val service = TestRepositoryService(profile).apply {
            syncHandler = {
                syncStarted.complete(Unit)
                releaseSync.await()
                SyncResponse(
                    acknowledgements = listOf(
                        Acknowledgement(sent.id, "applied", ""),
                        Acknowledgement(requireNotNull(inFlightId), "applied", ""),
                    ),
                    revision = 1,
                    canonicalTimer = null,
                    history = emptyList(),
                    durationAcknowledgements = emptyList(),
                    durationsMs = DurationsMs(),
                    taskAcknowledgements = emptyList(),
                    tasks = emptyList(),
                    serverTime = "2026-01-01T00:00:00Z",
                    serverHlcWallMs = 1_767_225_600_100,
                    serverHlcCounter = 0,
                )
            }
            bootstrapResponse = syncResponse
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        syncStarted.await()
        repository.toggleTimer()
        val inFlight = database.timerDao().pendingCommands().last()
        inFlightId = inFlight.id
        releaseSync.complete(Unit)
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }
        delay(100)

        assertEquals(
            "Sync returned an invalid command acknowledgement set",
            repository.state.value.conflict,
        )
        assertEquals(listOf(sent.id, inFlight.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(0L, database.timerDao().localState()?.revision)
        assertEquals(1, service.syncCalls)
    }

    @Test
    fun duplicateTaskAcknowledgementsCannotMutateQueueOrSnapshot() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship"))
        val operation = TaskOperation(
            id = "task-operation-1",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = listOf(
                    TaskAcknowledgement(operation.id, "applied", ""),
                    TaskAcknowledgement(operation.id, "applied", ""),
                ),
                tasks = listOf(task),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync returned an invalid task acknowledgement set", repository.state.value.conflict)
        assertEquals(operation.id, database.timerDao().pendingTaskOperations().single().id)
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun nonMinutePendingDurationIsTerminalWithoutNetworkMutation() = runBlocking {
        val profile = testUser()
        val operation = testDurationOperation(
            id = "duration-invalid-minute",
            phase = TimerPhase.Focus,
            durationMs = 1_500_001,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(operation))
        val service = TestRepositoryService(profile)
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Queued duration operation is invalid", repository.state.value.conflict)
        assertEquals(operation.id, database.timerDao().pendingDurationOperations().single().id)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun cachedOwnerIsNotTrustedWhenProfileVerificationFails() = runBlocking {
        val profile = testUser("cached-user")
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            meFailure = IOException("profile unavailable")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertEquals("profile unavailable", repository.state.value.notice)
        assertEquals(0, service.syncCalls)
        assertEquals(command.id, database.timerDao().pendingCommands().single().id)
    }

    @Test
    fun invalidProfileIsRejectedBeforeBootstrapOrOwnerMutation() = runBlocking {
        val owner = testUser("owner-user")
        val command = testCommand("owner-command", sequence = 1)
        database.timerDao().insertState(testState(user = owner, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(owner.copy(email = "invalid-email"))
        val auth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(context, database.timerDao(), service, auth)

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(!auth.tokensAvailable)
        assertEquals(0, service.bootstrapCalls)
        assertNull(repository.state.value.accountSwitch)
        assertEquals(owner.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
    }

    @Test
    fun networkLogoutFailureClearsAccountQueuesAndSurvivesRestart() = runBlocking {
        assertFailedRevocationClearsAccount(IOException("logout unavailable"), populateQueues = true)
    }

    @Test
    fun http5xxLogoutFailureClearsAccountAndSurvivesRestart() = runBlocking {
        assertFailedRevocationClearsAccount(ApiException(503, "logout unavailable"))
    }

    @Test
    fun tokenClearFailureCannotRestoreOldAccountAfterRestart() = runBlocking {
        val profile = testUser()
        val timer = testTimer()
        database.timerDao().insertState(
            testState(user = profile, timer = timer, history = listOf(testHistory("history-1"))),
        )
        val auth = TestAuthSession(tokensAvailable = true).apply {
            tokenClearFailuresRemaining = 1
        }
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(
                canonicalTimer = timer,
                history = listOf(testHistory("history-1")),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )
        repository.initialize()
        val profileCallsBeforeLogout = service.callOrder.count { it == "me" }

        repository.logout()

        assertEquals(1, auth.logoutCalls)
        assertTrue(auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(database.timerDao().localState()?.ownerUserId)

        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )
        restarted.initialize()

        assertEquals(profileCallsBeforeLogout, service.callOrder.count { it == "me" })
        assertEquals(1, auth.clearCalls)
        assertTrue(!auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, restarted.state.value.authStatus)
        assertNull(restarted.state.value.user)
        assertNull(restarted.state.value.timer)
    }

    private suspend fun assertFailedRevocationClearsAccount(
        failure: Throwable,
        populateQueues: Boolean = false,
    ) {
        val profile = testUser()
        val timer = testTimer()
        val state = testState(
            user = profile,
            timer = timer,
            history = listOf(testHistory("history-1")),
            deviceSequence = 1,
        )
        database.timerDao().insertState(state)
        if (populateQueues) {
            val task = requireNotNull(TaskReducer.taskFromTitle("Queued task"))
            database.timerDao().insertCommand(
                PendingCommandEntity.from(testCommand("queued-command", sequence = 1)),
            )
            database.timerDao().insertTaskOperation(
                PendingTaskOperationEntity.from(
                    TaskOperation(
                        id = "queued-task",
                        taskId = task.id,
                        type = TaskOperationType.Upsert,
                        title = task.title,
                        occurredAt = "2026-01-01T00:00:00Z",
                        hlcWallMs = 1_767_225_600_001,
                        hlcCounter = 0,
                    ),
                ),
            )
            database.timerDao().upsertDurationOperation(
                PendingDurationOperationEntity.from(
                    testDurationOperation(
                        id = "queued-duration",
                        phase = TimerPhase.Focus,
                        durationMs = 1_800_000,
                    ),
                ),
            )
            database.timerDao().insertAutoStartOperation(
                PendingAutoStartOperationEntity.from(
                    testAutoStartOperation("queued-auto-start", enabled = true),
                ),
            )
            database.timerDao().insertSelectedTaskOperation(
                PendingSelectedTaskOperationEntity.from(
                    SelectedTaskOperation(
                        id = "queued-selected-task",
                        taskId = task.id,
                        occurredAt = "2026-01-01T00:00:00Z",
                        hlcWallMs = 1_767_225_600_002,
                        hlcCounter = 0,
                    ),
                ),
            )
        }
        val auth = TestAuthSession(tokensAvailable = true).apply {
            logoutFailure = failure
        }
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(
                canonicalTimer = timer,
                history = listOf(testHistory("history-1")),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )
        repository.initialize()
        val profileCallsBeforeLogout = service.callOrder.count { it == "me" }

        repository.logout()

        assertEquals(1, auth.logoutCalls)
        assertTrue(!auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertEquals(0, repository.state.value.pendingCount)
        assertNull(database.timerDao().localState()?.ownerUserId)
        assertNull(database.timerDao().localState()?.userJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertTrue(database.timerDao().pendingSelectedTaskOperations().isEmpty())

        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )
        restarted.initialize()

        assertEquals(profileCallsBeforeLogout, service.callOrder.count { it == "me" })
        assertEquals(AuthStatus.SignedOut, restarted.state.value.authStatus)
        assertNull(restarted.state.value.user)
        assertNull(restarted.state.value.timer)
    }

    @Test
    fun authenticationFailureDuringSyncSignsOutWithoutDeletingQueue() = runBlocking {
        val profile = testUser()
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            syncFailure = AuthenticationRequired("Session expired")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.authStatus == AuthStatus.SignedOut }

        assertNull(repository.state.value.user)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertNotNull(database.timerDao().localState()?.ownerUserId)
    }
}
