package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SelectedTaskAcknowledgement
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustedTimeContractTest {
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
    fun sequenceOverflowFailsBeforeAnyDurableMutation() = runBlocking {
        val maximum = SyncWireBounds.MaxSafeInteger
        val initial = testState(deviceSequence = maximum).copy(hlcWallMs = NowMs)
        database.timerDao().insertState(initial)
        val repository = repository()
        repository.initialize()

        repository.toggleTimer()

        assertTrue(repository.state.value.notice?.contains("Device sequence overflow") == true)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(initial, database.timerDao().localState())
    }

    @Test
    fun malformedPhysicalOccurrenceIsRepairedBeforeStrictValidation() = runBlocking {
        val legacy = testCommand("legacy-command", 1).copy(
            occurredAt = java.time.Instant.ofEpochMilli(NowMs).toString(),
            hlcWallMs = NowMs + 60 * 60_000L,
            physicalOccurredAt = "not-an-instant",
        )
        database.timerDao().insertState(testState(deviceSequence = 1).copy(hlcWallMs = NowMs))
        database.timerDao().insertCommand(PendingCommandEntity.from(legacy))
        val repository = testRepository(
            context,
            database.timerDao(),
            online = false,
            currentTimeMillis = { NowMs },
        )

        repository.initialize()

        val repaired = database.timerDao().pendingCommands().single()
        assertEquals(NowMs, repaired.hlcWallMs)
        assertEquals(legacy.occurredAt, repaired.physicalOccurredAt)
        assertTrue(repository.state.value.conflict != "Local clock or sequence is outside the synchronization range.")
    }

    @Test
    fun staleBootstrapChoiceRefreshesTrustedSampleBeforeResolutionCapture() = runBlocking {
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(history = listOf(testHistory("local-choice"))))
        val service = TestRepositoryService(profile).apply {
            bootstrapHandler = {
                trustedResponse(revision = 0).copy(
                    history = listOf(testHistory("remote-choice")),
                    serverTime = java.time.Instant.ofEpochMilli(physicalNow).toString(),
                    serverHlcWallMs = physicalNow,
                )
            }
            resolveHandler = { trustedResponse(revision = 1) }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        awaitState { repository.state.value.historyResolution != null }
        physicalNow += SyncWireBounds.MaxClockSkewMs + 1
        elapsedNow += SyncWireBounds.MaxClockSkewMs + 1
        repository.resolveHistory(BootstrapStrategy.KeepRemote)
        awaitState { service.resolveCalls == 1 }

        assertEquals(2, service.bootstrapCalls)
        assertEquals(BootstrapStrategy.KeepRemote, service.resolutionRequests.single().strategy)
    }

    @Test
    fun bootstrapResolutionRejectsWallJumpDuringRequestWithoutPersistingSample() = runBlocking {
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(history = listOf(testHistory("local-resolution"))))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0).copy(
                history = listOf(testHistory("remote-resolution")),
            )
            resolveHandler = {
                physicalNow += 60 * 60_000L
                elapsedNow += 1_000L
                trustedResponse(revision = 1)
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )

        repository.initialize()
        awaitState { repository.state.value.historyResolution != null }
        repository.resolveHistory(BootstrapStrategy.KeepRemote)
        awaitState { repository.state.value.historyResolution?.submitting == false }

        assertEquals(1, service.resolveCalls)
        assertTrue(repository.state.value.historyResolution?.error?.contains("uncertainty") == true)
        assertEquals(0L, database.timerDao().localState()?.revision)
        assertEquals(0L, database.timerDao().localState()?.serverClockOffsetMs)
        assertEquals(0L, database.timerDao().localState()?.serverClockUncertaintyMs)
        assertTrue(database.timerDao().pendingBootstrapResolution() != null)
    }

    @Test
    fun temporaryRetainedClockSkewDoesNotCorruptRepository() = runBlocking {
        val initial = testState().copy(
            hlcWallMs = NowMs + SyncWireBounds.MaxClockSkewMs + 1,
            hlcCounter = 7,
        )
        database.timerDao().insertState(initial)
        val repository = repository()
        repository.initialize()

        repository.changeDuration(TimerPhase.Focus, 1)

        assertTrue(repository.state.value.notice?.contains("Retained hybrid clock") == true)
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertEquals(initial, database.timerDao().localState())

        repository.setAutoStart(true)

        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertEquals(initial, database.timerDao().localState())
    }

    @Test
    fun exactFiveMinuteRetainedClockBoundaryIsAccepted() = runBlocking {
        val initial = testState().copy(
            hlcWallMs = NowMs + SyncWireBounds.MaxClockSkewMs,
            hlcCounter = 4,
        )
        database.timerDao().insertState(initial)
        val repository = repository()
        repository.initialize()

        repository.changeDuration(TimerPhase.Focus, 1)

        val operation = database.timerDao().pendingDurationOperations().single().toModel()
        assertEquals(initial.hlcWallMs, operation.hlcWallMs)
        assertEquals(5L, operation.hlcCounter)
        assertEquals(26 * 60_000L, operation.durationMs)
    }

    @Test
    fun generatedFinishAndBreakReserveExactMaximumHeadroomAtomically() = runBlocking {
        val maximum = SyncWireBounds.MaxSafeInteger
        val timer = testTimer(elapsedMs = 1_500_000, anchorAt = "2000-01-01T00:00:00Z")
        database.timerDao().insertState(
            testState(
                timer = timer,
                settings = TimerSettings(autoStartBreaks = true),
                deviceSequence = maximum - 2,
            ).copy(
                hlcWallMs = NowMs,
                hlcCounter = maximum - 2,
            ),
        )
        val repository = repository()
        repository.initialize()

        assertTrue(repository.finishExpiredTimer())

        val commands = database.timerDao().pendingCommands().map(PendingCommandEntity::toModel)
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(listOf(maximum - 1, maximum), commands.map { it.deviceSequence })
        assertEquals(listOf(maximum - 1, maximum), commands.map { it.hlcCounter })
        assertEquals(maximum, database.timerDao().localState()?.deviceSequence)
        assertEquals(maximum, database.timerDao().localState()?.hlcCounter)
    }

    @Test
    fun generatedFinishAndBreakOverflowWritesNeitherCommand() = runBlocking {
        val maximum = SyncWireBounds.MaxSafeInteger
        val timer = testTimer(elapsedMs = 1_500_000, anchorAt = "2000-01-01T00:00:00Z")
        val initial = testState(
            timer = timer,
            settings = TimerSettings(autoStartBreaks = true),
            deviceSequence = maximum - 1,
        ).copy(hlcWallMs = NowMs)
        database.timerDao().insertState(initial)
        val repository = repository()
        repository.initialize()

        assertTrue(!repository.finishExpiredTimer())

        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(initial, database.timerDao().localState())
        assertEquals(timer, repository.state.value.timer)
    }

    @Test
    fun generatedFinishAndBreakCounterOverflowWritesNeitherCommand() = runBlocking {
        val maximum = SyncWireBounds.MaxSafeInteger
        val timer = testTimer(elapsedMs = 1_500_000, anchorAt = "2000-01-01T00:00:00Z")
        val initial = testState(
            timer = timer,
            settings = TimerSettings(autoStartBreaks = true),
        ).copy(
            hlcWallMs = NowMs,
            hlcCounter = maximum - 1,
        )
        database.timerDao().insertState(initial)
        val repository = repository()
        repository.initialize()

        assertTrue(!repository.finishExpiredTimer())

        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(initial, database.timerDao().localState())
        assertEquals(timer, repository.state.value.timer)
    }

    @Test
    fun serverOffsetSupportsFastDeviceClockMutationRestartAndWallJump() = runBlocking {
        assertOffsetLifecycle(deviceSkewMs = 60 * 60_000L)
    }

    @Test
    fun serverOffsetSupportsSlowDeviceClockMutationRestartAndWallJump() = runBlocking {
        assertOffsetLifecycle(deviceSkewMs = -60 * 60_000L)
    }

    @Test
    fun bootstrapSampleRebasesOnceBeforeCapturingImmutableResolution() = runBlocking {
        val profile = testUser()
        val futureMs = NowMs + 60 * 60_000L
        val futureOperation = testDurationOperation(
            id = "future-duration",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
            wallMs = futureMs,
        ).copy(occurredAt = java.time.Instant.ofEpochMilli(futureMs).toString())
        database.timerDao().insertState(
            testState().copy(hlcWallMs = futureMs),
        )
        database.timerDao().upsertDurationOperation(
            me.egigoka.pomodorough.data.local.PendingDurationOperationEntity.from(futureOperation),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            resolveFailure = java.io.IOException("preserve captured request")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { futureMs },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()
        awaitState { service.resolveCalls == 1 }

        val captured = service.resolutionRequests.single().durationOperations.single()
        val persisted = database.timerDao().pendingDurationOperations().single().toModel()
        val saved = requireNotNull(database.timerDao().pendingBootstrapResolution())
        assertEquals(futureOperation.id, captured.id)
        assertEquals(captured, persisted)
        assertEquals(
            repositoryJson.encodeToString(listOf(captured)),
            saved.durationOperationsJson,
        )
        assertTrue(captured.hlcWallMs in NowMs - SyncWireBounds.MaxClockSkewMs..
            NowMs + SyncWireBounds.MaxClockSkewMs)
        assertEquals(captured.hlcWallMs, java.time.Instant.parse(captured.occurredAt).toEpochMilli())
    }

    @Test
    fun cachedResolutionResponseCannotRegressFreshBootstrapClockSample() = runBlocking {
        val profile = testUser()
        val freshTask = requireNotNull(
            me.egigoka.pomodorough.domain.TaskReducer.taskFromTitle("Fresh canonical task"),
        )
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val operation = testDurationOperation(
            id = "cached-response-duration",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
            wallMs = NowMs,
        )
        database.timerDao().insertState(testState().copy(hlcWallMs = NowMs))
        database.timerDao().upsertDurationOperation(
            me.egigoka.pomodorough.data.local.PendingDurationOperationEntity.from(operation),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            resolveFailure = java.io.IOException("lost response")
        }
        var repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        awaitState { service.resolveCalls == 1 }
        val captured = service.resolutionRequests.single()

        physicalNow += 60_000L
        elapsedNow += 60_000L
        service.bootstrapResponse = trustedResponse(
            revision = 2,
            tasks = listOf(freshTask),
        ).copy(
            serverTime = java.time.Instant.ofEpochMilli(NowMs + 60_000L).toString(),
            serverHlcWallMs = NowMs + 60_000L,
        )
        service.resolveFailure = null
        service.resolveHandler = { request ->
            trustedResponse(
                revision = 1,
                durationAcks = request.durationOperations.map {
                    DurationAcknowledgement(it.id, "applied", "")
                },
            )
        }
        repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        awaitState { service.bootstrapCalls == 2 }
        repository.resolveHistory(BootstrapStrategy.Merge)
        awaitState { service.resolveCalls == 2 }
        assertEquals(captured, service.resolutionRequests.last())
        assertEquals(2L, database.timerDao().localState()?.revision)
        assertEquals(listOf(freshTask), repository.state.value.tasks)

        repository.setAutoStart(true)
        val next = database.timerDao().pendingAutoStartOperations().single().toModel()
        assertTrue(java.time.Instant.parse(next.occurredAt).toEpochMilli() >= NowMs + 60_000L)
        assertTrue(next.hlcWallMs >= NowMs + 60_000L)
    }

    @Test
    fun exhaustedTimerSequenceDoesNotBlockTaskDomainOrNormalSync() = runBlocking {
        val maximum = SyncWireBounds.MaxSafeInteger
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(
            testState(profile, deviceSequence = maximum).copy(
                hlcWallMs = NowMs,
                serverClockOffsetMs = 0,
                serverClockUncertaintyMs = 0,
                serverClockSamplePhysicalMs = NowMs,
                serverClockSampleElapsedRealtimeMs = elapsedNow,
                serverClockBootId = "test-boot",
            ),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            syncHandler = { request ->
                trustedResponse(
                    revision = request.lastRevision + 1,
                    taskAcks = request.taskOperations.map {
                        TaskAcknowledgement(it.id, "applied", "")
                    },
                ).copy(
                    tasks = me.egigoka.pomodorough.domain.TaskReducer.replay(emptyList(), request.taskOperations),
                    selectedTaskId = request.selectedTaskOperations.lastOrNull()?.taskId,
                    selectedTaskAcknowledgements = request.selectedTaskOperations.map {
                        SelectedTaskAcknowledgement(it.id, "applied", "")
                    },
                )
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        awaitState { service.syncCalls == 1 }

        repository.toggleTimer()
        repository.addTask("Still works")

        awaitState { service.syncCalls >= 2 && repository.state.value.pendingCount == 0 }
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(repository.state.value.tasks.any { it.title == "Still works" })
        assertTrue(repository.state.value.conflict != "Local clock or sequence is outside the synchronization range.")
    }

    @Test
    fun requestMidpointPersistsOffsetAndCeilingHalfRttUncertainty() = runBlocking {
        var physicalNow = NowMs + 60 * 60_000L
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapHandler = {
                physicalNow += 20_001L
                elapsedNow += 20_001L
                trustedResponse(revision = 0).copy(
                    serverTime = java.time.Instant.ofEpochMilli(NowMs + 10_001L).toString(),
                    serverHlcWallMs = NowMs + 10_001L,
                )
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            online = false,
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )

        repository.initialize()

        val state = requireNotNull(database.timerDao().localState())
        assertEquals(-(60 * 60_000L - 1L), state.serverClockOffsetMs)
        assertEquals(10_001L, state.serverClockUncertaintyMs)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun requestWallJumpContributesToUncertaintyAndRejectsSample() = runBlocking {
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapHandler = {
                physicalNow += 60 * 60_000L + 1_000L
                elapsedNow += 1_000L
                trustedResponse(revision = 0)
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )

        repository.initialize()

        assertTrue(repository.state.value.notice?.contains("uncertainty") == true)
        assertNull(database.timerDao().localState()?.serverClockOffsetMs)
    }

    @Test
    fun bootstrapRebaseAlignsCommandHlcWithDeviceSequence() = runBlocking {
        val first = testCommand("sequence-first", 1).copy(
            hlcWallMs = NowMs + 100,
            physicalOccurredAt = "2026-01-01T00:00:00Z",
        )
        val second = testCommand("sequence-second", 2, type = CommandType.Pause).copy(
            hlcWallMs = NowMs,
            physicalOccurredAt = "2026-01-01T00:00:00Z",
        )
        database.timerDao().insertState(testState(deviceSequence = 2).copy(hlcWallMs = NowMs + 100))
        database.timerDao().insertCommand(PendingCommandEntity.from(first))
        database.timerDao().insertCommand(PendingCommandEntity.from(second))
        val service = TestRepositoryService().apply {
            bootstrapResponse = trustedResponse(revision = 0)
            resolveFailure = java.io.IOException("inspect captured request")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.resolveCalls == 1 }

        val captured = service.resolutionRequests.single().commands.sortedBy { it.deviceSequence }
        assertEquals(listOf(1L, 2L), captured.map { it.deviceSequence })
        assertTrue(
            captured[1].hlcWallMs > captured[0].hlcWallMs ||
                captured[1].hlcWallMs == captured[0].hlcWallMs &&
                captured[1].hlcCounter > captured[0].hlcCounter,
        )
    }

    @Test
    fun excessiveClockUncertaintyRejectsSampleWithoutPersistingIt() = runBlocking {
        var physicalNow = NowMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapHandler = {
                physicalNow += 60_002L
                elapsedNow += 60_002L
                trustedResponse(revision = 0)
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )

        repository.initialize()

        assertTrue(repository.state.value.notice?.contains("uncertainty") == true)
        assertNull(database.timerDao().localState()?.serverClockOffsetMs)
        assertNull(database.timerDao().localState()?.serverClockUncertaintyMs)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun serverHlcMustAgreeWithServerTime() = runBlocking {
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0).copy(
                serverHlcWallMs = NowMs + SyncWireBounds.MaxClockSkewMs + 1,
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { NowMs },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()

        assertTrue(repository.state.value.notice?.contains("disagrees") == true)
        assertNull(database.timerDao().localState()?.serverClockOffsetMs)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun unsafeLocalRequestTimingRejectsSampleWithoutMutation() = runBlocking {
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { Long.MAX_VALUE },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()

        assertTrue(repository.state.value.notice?.contains("timing") == true)
        assertNull(database.timerDao().localState()?.serverClockOffsetMs)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun trustedOperationTimeDoesNotShiftLocalTimerPhysicalAnchor() = runBlocking {
        val deviceNow = NowMs + 60 * 60_000L
        database.timerDao().insertState(
            testState().copy(
                serverClockOffsetMs = -60 * 60_000L,
                serverClockUncertaintyMs = 0,
                serverClockSamplePhysicalMs = deviceNow,
                serverClockSampleElapsedRealtimeMs = 10_000L,
                serverClockBootId = "test-boot",
            ),
        )
        val repository = testRepository(
            context,
            database.timerDao(),
            currentTimeMillis = { deviceNow },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()
        repository.toggleTimer()

        val command = database.timerDao().pendingCommands().single()
        assertEquals(NowMs, java.time.Instant.parse(command.occurredAt).toEpochMilli())
        assertEquals(deviceNow, java.time.Instant.parse(command.physicalOccurredAt).toEpochMilli())
        assertEquals(deviceNow, java.time.Instant.parse(repository.state.value.timer?.anchorAt).toEpochMilli())
    }

    @Test
    fun acceptedSyncDurablyRebasesUnsentSuffixBeforeRetry() = runBlocking {
        val profile = testUser()
        val sent = testDurationOperation(
            "sent-duration",
            TimerPhase.Focus,
            26 * 60_000L,
            NowMs,
        )
        database.timerDao().insertState(testState(profile).copy(hlcWallMs = NowMs))
        database.timerDao().upsertDurationOperation(
            me.egigoka.pomodorough.data.local.PendingDurationOperationEntity.from(sent),
        )
        val firstSyncStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirstSync = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val nextServerMs = NowMs + 60 * 60_000L
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    firstSyncStarted.complete(Unit)
                    releaseFirstSync.await()
                    trustedResponse(
                        revision = 1,
                        durationAcks = listOf(DurationAcknowledgement(sent.id, "applied", "")),
                    ).copy(
                        serverTime = java.time.Instant.ofEpochMilli(nextServerMs).toString(),
                        serverHlcWallMs = nextServerMs,
                    )
                } else {
                    throw me.egigoka.pomodorough.data.api.ApiException(
                        409,
                        "stop after trusted rebase verification",
                    )
                }
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { NowMs },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()
        firstSyncStarted.await()
        repository.setAutoStart(true)
        val retainedId = database.timerDao().pendingAutoStartOperations().single().id
        releaseFirstSync.complete(Unit)
        awaitState { repository.state.value.conflict == "stop after trusted rebase verification" }

        val stored = database.timerDao().pendingAutoStartOperations().single().toModel()
        val retried = service.syncRequests[1].autoStartOperations.single()
        assertEquals(retainedId, stored.id)
        assertEquals(stored, retried)
        assertTrue(stored.hlcWallMs in nextServerMs - SyncWireBounds.MaxClockSkewMs..
            nextServerMs + SyncWireBounds.MaxClockSkewMs)
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
    }

    @Test
    fun restartUsesElapsedContinuityAndInvalidatesAnchorAfterReboot() = runBlocking {
        database.timerDao().insertState(
            testState().copy(
                serverClockOffsetMs = -60 * 60_000L,
                serverClockUncertaintyMs = 3,
                serverClockSamplePhysicalMs = NowMs + 60 * 60_000L,
                serverClockSampleElapsedRealtimeMs = 10_000L,
                serverClockBootId = "test-boot",
            ),
        )
        var repository = testRepository(
            context,
            database.timerDao(),
            currentTimeMillis = { NowMs + 8 * 60 * 60_000L },
            elapsedRealtimeMillis = { 11_000L },
        )
        repository.initialize()
        repository.setAutoStart(true)
        assertEquals(
            NowMs + 1_000L,
            java.time.Instant.parse(
                database.timerDao().pendingAutoStartOperations().single().occurredAt,
            ).toEpochMilli(),
        )

        database.timerDao().deleteAllAutoStartOperations()
        repository = testRepository(
            context,
            database.timerDao(),
            currentTimeMillis = { NowMs + 2 * 60 * 60_000L },
            elapsedRealtimeMillis = { 100L },
            bootId = { "new-boot" },
        )
        repository.initialize()
        val stored = requireNotNull(database.timerDao().localState())
        assertNull(stored.serverClockOffsetMs)
        assertNull(stored.serverClockUncertaintyMs)
        assertNull(stored.serverClockSamplePhysicalMs)
        assertNull(stored.serverClockSampleElapsedRealtimeMs)
    }

    @Test
    fun canonicalDeadlineAndHistoryUseWireToPhysicalDelta() = runBlocking {
        val deviceSkewMs = 60 * 60_000L
        val profile = testUser()
        val start = testCommand("start-physical", 1).copy(
            physicalOccurredAt = java.time.Instant.ofEpochMilli(NowMs + deviceSkewMs).toString(),
        )
        database.timerDao().insertState(testState(profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(start))
        val wireDeadlineMs = NowMs + start.plannedDurationMs
        val canonical = testTimer(
            id = start.timerId,
            status = me.egigoka.pomodorough.data.TimerStatus.Completed,
            elapsedMs = start.plannedDurationMs,
            anchorAt = java.time.Instant.ofEpochMilli(wireDeadlineMs).toString(),
        ).copy(lastIntent = me.egigoka.pomodorough.data.TimerIntent(
            CommandType.Start,
            start.id,
            start.occurredAt,
        ))
        val history = HistoryItem(
            id = "history-physical",
            timerId = start.timerId,
            commandId = start.id,
            phase = TimerPhase.Focus,
            status = me.egigoka.pomodorough.data.TimerStatus.Completed,
            plannedDurationMs = start.plannedDurationMs,
            completedAt = java.time.Instant.ofEpochMilli(wireDeadlineMs).toString(),
            endedAt = java.time.Instant.ofEpochMilli(wireDeadlineMs).toString(),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            syncResponse = trustedResponse(revision = 1).copy(
                canonicalTimer = canonical,
                history = listOf(history),
                acknowledgements = listOf(
                    me.egigoka.pomodorough.data.Acknowledgement(start.id, "applied", ""),
                ),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { NowMs + deviceSkewMs },
            elapsedRealtimeMillis = { 10_000L },
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        val physicalDeadlineMs = wireDeadlineMs + deviceSkewMs
        assertEquals(
            physicalDeadlineMs,
            java.time.Instant.parse(repository.state.value.timer?.anchorAt).toEpochMilli(),
        )
        assertEquals(
            physicalDeadlineMs,
            java.time.Instant.parse(repository.state.value.history.single().completedAt).toEpochMilli(),
        )
    }

    @Test
    fun claimedAutomaticCompletionUsesRunAnchorDeltaInsteadOfLateFinishDelta() = runBlocking {
        val profile = testUser()
        val startDeltaMs = 60 * 60_000L
        val finishDeltaMs = 2 * 60 * 60_000L
        val durationMs = 60_000L
        val finishWireMs = NowMs + durationMs
        val start = testCommand("claim-start", 1).copy(
            plannedDurationMs = durationMs,
            physicalOccurredAt = java.time.Instant.ofEpochMilli(NowMs + startDeltaMs).toString(),
        )
        val finish = testCommand("claim-finish", 2, type = CommandType.Finish).copy(
            plannedDurationMs = durationMs,
            occurredAt = java.time.Instant.ofEpochMilli(finishWireMs).toString(),
            hlcWallMs = finishWireMs,
            physicalOccurredAt = java.time.Instant.ofEpochMilli(finishWireMs + finishDeltaMs).toString(),
        )
        database.timerDao().insertState(
            testState(profile, deviceSequence = 2).copy(hlcWallMs = finishWireMs),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(start))
        database.timerDao().insertCommand(PendingCommandEntity.from(finish))
        val wireDeadlineMs = NowMs + durationMs
        val canonical = testTimer(
            id = start.timerId,
            status = me.egigoka.pomodorough.data.TimerStatus.Completed,
            elapsedMs = durationMs,
            anchorAt = java.time.Instant.ofEpochMilli(wireDeadlineMs).toString(),
        ).copy(lastIntent = me.egigoka.pomodorough.data.TimerIntent(
            CommandType.Finish,
            finish.id,
            finish.occurredAt,
        ))
        val history = HistoryItem(
            id = "claim-history",
            timerId = start.timerId,
            commandId = finish.id,
            phase = TimerPhase.Focus,
            status = me.egigoka.pomodorough.data.TimerStatus.Completed,
            plannedDurationMs = durationMs,
            completedAt = canonical.anchorAt,
            endedAt = canonical.anchorAt,
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            syncResponse = trustedResponse(revision = 1).copy(
                canonicalTimer = canonical,
                history = listOf(history),
                acknowledgements = listOf(
                    me.egigoka.pomodorough.data.Acknowledgement(start.id, "applied", ""),
                    me.egigoka.pomodorough.data.Acknowledgement(finish.id, "applied", ""),
                ),
            )
        }
        var repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { NowMs + finishDeltaMs },
        )

        repository.initialize()
        awaitState { repository.state.value.pendingCount == 0 }

        val expectedPhysicalDeadline = wireDeadlineMs + startDeltaMs
        assertEquals(
            expectedPhysicalDeadline,
            java.time.Instant.parse(repository.state.value.timer?.anchorAt).toEpochMilli(),
        )
        assertEquals(
            expectedPhysicalDeadline,
            java.time.Instant.parse(repository.state.value.history.single().completedAt).toEpochMilli(),
        )

        val repeatedService = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 1).copy(
                canonicalTimer = canonical,
                history = listOf(history),
                serverTime = java.time.Instant.ofEpochMilli(NowMs + durationMs).toString(),
                serverHlcWallMs = NowMs + durationMs,
            )
        }
        repository = testRepository(
            context,
            database.timerDao(),
            repeatedService,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { NowMs + 3 * 60 * 60_000L },
            elapsedRealtimeMillis = { 100L },
            bootId = { "restarted-boot" },
        )

        repository.initialize()
        awaitState { repeatedService.bootstrapCalls == 1 }

        assertEquals(
            expectedPhysicalDeadline,
            java.time.Instant.parse(repository.state.value.timer?.anchorAt).toEpochMilli(),
        )
        assertEquals(
            expectedPhysicalDeadline,
            java.time.Instant.parse(repository.state.value.history.single().completedAt).toEpochMilli(),
        )
    }

    private suspend fun assertOffsetLifecycle(deviceSkewMs: Long) {
        var physicalNow = NowMs + deviceSkewMs
        var elapsedNow = 10_000L
        val profile = testUser()
        database.timerDao().insertState(testState(profile))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = trustedResponse(revision = 0)
            syncHandler = { request ->
                trustedResponse(
                    revision = request.lastRevision + 1,
                    durationAcks = request.durationOperations.map {
                        DurationAcknowledgement(it.id, "applied", "")
                    },
                )
            }
        }
        var repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        awaitState { service.syncCalls == 1 }
        repository.changeDuration(TimerPhase.Focus, 1)
        awaitState { service.syncCalls == 2 }

        val first = service.syncRequests.last().durationOperations.single()
        assertTrue(kotlin.math.abs(java.time.Instant.parse(first.occurredAt).toEpochMilli() - NowMs) <= 1)
        assertEquals(NowMs, first.hlcWallMs)
        val storedSample = requireNotNull(database.timerDao().localState())
        assertEquals(-deviceSkewMs, storedSample.serverClockOffsetMs)

        elapsedNow = 11_000L
        physicalNow += if (deviceSkewMs > 0) -2 * 60 * 60_000L else 2 * 60 * 60_000L
        repository = testRepository(
            context,
            database.timerDao(),
            currentTimeMillis = { physicalNow },
            elapsedRealtimeMillis = { elapsedNow },
        )
        repository.initialize()
        repository.setAutoStart(true)

        val afterRestart = database.timerDao().pendingAutoStartOperations().single().toModel()
        val restartTrustedMs = NowMs + 1_000L
        assertEquals(restartTrustedMs, java.time.Instant.parse(afterRestart.occurredAt).toEpochMilli())
        assertEquals(restartTrustedMs, afterRestart.hlcWallMs)

        physicalNow += 8 * 60 * 60_000L
        elapsedNow += 1_000
        repository.addTask("Wall jump safe")
        val taskOperation = database.timerDao().pendingTaskOperations().single().toModel()
        assertEquals(restartTrustedMs + 1_000, java.time.Instant.parse(taskOperation.occurredAt).toEpochMilli())
    }

    private fun trustedResponse(
        revision: Long,
        durationAcks: List<DurationAcknowledgement> = emptyList(),
        taskAcks: List<TaskAcknowledgement> = emptyList(),
        tasks: List<FocusTask> = emptyList(),
    ) = SyncResponse(
        acknowledgements = emptyList(),
        revision = revision,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = java.time.Instant.ofEpochMilli(NowMs).toString(),
        serverHlcWallMs = NowMs,
        serverHlcCounter = 0,
        durationAcknowledgements = durationAcks,
        durationsMs = DurationsMs(),
        taskAcknowledgements = taskAcks,
        tasks = tasks,
    )

    private fun repository() = testRepository(
        context = context,
        dao = database.timerDao(),
        currentTimeMillis = { NowMs },
    )

    private companion object {
        const val NowMs = 1_767_225_600_000L
    }
}
