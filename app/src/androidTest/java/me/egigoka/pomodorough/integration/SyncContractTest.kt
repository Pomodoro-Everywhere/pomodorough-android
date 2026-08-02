package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AutoStartAcknowledgement
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncContractTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = newDatabase()
    }

    @After
    fun tearDown() {
        TimerAlarmScheduler(context).cancel()
        database.close()
    }

    @Test
    fun lowerRevisionResponseIsRejectedAtomically() = runBlocking {
        val localTimer = testTimer("local-timer", status = TimerStatus.Paused)
        val serverTimer = testTimer("server-timer", status = TimerStatus.Running)
        val profile = testUser()
        database.timerDao().insertState(testState(profile, localTimer, revision = 5))
        val service = revisionService(profile, localTimer, serverTimer, responseRevision = 4)
        val repository = signedInRepository(service)

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync revision regressed from 5 to 4", repository.state.value.conflict)
        assertEquals(localTimer, repository.state.value.timer)
        assertEquals(5L, database.timerDao().localState()?.revision)
        assertEquals(repositoryJson.encodeToString(localTimer), database.timerDao().localState()?.canonicalTimerJson)
    }

    @Test
    fun equalRevisionResponseIsAccepted() = runBlocking {
        assertRevisionAccepted(responseRevision = 5)
    }

    @Test
    fun higherRevisionResponseIsAccepted() = runBlocking {
        assertRevisionAccepted(responseRevision = 6)
    }

    @Test
    fun everyKnownAcknowledgementOutcomeIsTerminalForEveryQueueKind() = runBlocking {
        for (kind in AckKind.entries) {
            for (outcome in knownOutcomes) {
                resetDatabase()
                val fixture = seedQueue(kind)
                val service = TestRepositoryService(fixture.profile).apply {
                    bootstrapResponse = response(
                        revision = 0,
                        timer = fixture.localTimer,
                        tasks = fixture.canonicalTasks,
                    )
                    syncHandler = { request ->
                        response(
                            revision = 1,
                            timer = fixture.serverTimer,
                            tasks = fixture.canonicalTasks,
                            commandAcks = request.commands.map {
                                Acknowledgement(it.id, outcome, outcomeReason(outcome))
                            },
                            taskAcks = request.taskOperations.map {
                                TaskAcknowledgement(it.id, outcome, outcomeReason(outcome))
                            },
                            durationAcks = request.durationOperations.map {
                                DurationAcknowledgement(it.id, outcome, outcomeReason(outcome))
                            },
                            autoStartAcks = request.autoStartOperations.map {
                                AutoStartAcknowledgement(it.id, outcome, outcomeReason(outcome))
                            },
                        )
                    }
                }
                val repository = signedInRepository(service)

                repository.initialize()
                awaitState {
                    service.syncCalls == 1 &&
                        (repository.state.value.pendingCount == 0 ||
                            repository.state.value.syncStatus == SyncStatus.Conflict)
                }

                val case = "kind=$kind outcome=$outcome"
                assertEquals(case, 0, repository.state.value.pendingCount)
                assertEquals(case, 1L, database.timerDao().localState()?.revision)
                assertEquals(case, fixture.serverTimer, repository.state.value.timer)
                assertQueuesEmpty(case)
                if (outcome == "applied") {
                    assertEquals(case, null, repository.state.value.conflict)
                } else {
                    assertEquals(case, outcomeReason(outcome), repository.state.value.conflict)
                }
            }
        }
    }

    @Test
    fun unknownOutcomeRejectsWholeResponseWithoutDequeuingOrReplacingSnapshot() = runBlocking {
        val fixture = seedAllQueues()
        val service = TestRepositoryService(fixture.profile).apply {
            bootstrapResponse = response(
                revision = 0,
                timer = fixture.localTimer,
                tasks = fixture.canonicalTasks,
            )
            syncHandler = { request ->
                response(
                    revision = 1,
                    timer = fixture.serverTimer,
                    tasks = fixture.canonicalTasks,
                    commandAcks = request.commands.map { Acknowledgement(it.id, "applied", "") },
                    taskAcks = request.taskOperations.map {
                        TaskAcknowledgement(it.id, "applied", "")
                    },
                    durationAcks = request.durationOperations.map {
                        DurationAcknowledgement(it.id, "unknown", "invalid")
                    },
                    autoStartAcks = request.autoStartOperations.map {
                        AutoStartAcknowledgement(it.id, "rejected", "terminal")
                    },
                )
            }
        }
        val repository = signedInRepository(service)

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync returned an invalid duration acknowledgement", repository.state.value.conflict)
        assertEquals(fixture.localTimer, repository.state.value.timer)
        assertEquals(4, repository.state.value.pendingCount)
        assertEquals(0L, database.timerDao().localState()?.revision)
        assertEquals(1, database.timerDao().pendingCommands().size)
        assertEquals(1, database.timerDao().pendingTaskOperations().size)
        assertEquals(1, database.timerDao().pendingDurationOperations().size)
        assertEquals(1, database.timerDao().pendingAutoStartOperations().size)
    }

    @Test
    fun exactAcknowledgementSetsAggregateMixedOutcomesAndClearAfterConvergence() = runBlocking {
        val fixture = seedTwoOfEveryQueueKind()
        val service = TestRepositoryService(fixture.profile).apply {
            bootstrapResponse = response(
                revision = 0,
                timer = fixture.localTimer,
                tasks = fixture.canonicalTasks,
            )
            syncHandler = { request ->
                response(
                    revision = 1,
                    timer = fixture.serverTimer,
                    tasks = fixture.canonicalTasks,
                    commandAcks = request.commands.reversed().map {
                        Acknowledgement(it.id, "applied", "")
                    },
                    taskAcks = request.taskOperations.reversed().map {
                        TaskAcknowledgement(it.id, "ignored", "superseded")
                    },
                    durationAcks = request.durationOperations.reversed().map {
                        DurationAcknowledgement(it.id, "rejected", "conflict")
                    },
                    autoStartAcks = request.autoStartOperations.reversed().map {
                        AutoStartAcknowledgement(it.id, "applied", "")
                    },
                )
            }
        }
        val repository = signedInRepository(service)

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        assertEquals(1L, database.timerDao().localState()?.revision)
        assertEquals(fixture.serverTimer, repository.state.value.timer)
        assertEquals(
            "Task: superseded\nTask: superseded\nDuration: conflict\nDuration: conflict",
            repository.state.value.conflict,
        )
        assertQueuesEmpty("reversed exact acknowledgements")

        repository.refresh()
        awaitState { service.syncCalls == 2 && repository.state.value.conflict == null }

        assertEquals(SyncStatus.Synced, repository.state.value.syncStatus)
    }

    @Test
    fun corruptPendingQueuePreflightRejectsEveryKindBeforeNetwork() = runBlocking {
        for (kind in AckKind.entries) {
            resetDatabase()
            val profile = testUser()
            val now = System.currentTimeMillis()
            database.timerDao().insertState(testState(profile, deviceSequence = 1))
            when (kind) {
                AckKind.Command -> database.timerDao().insertCommand(
                    PendingCommandEntity.from(
                        testCommand("command-corrupt", 1).copy(phase = "invalid"),
                    ),
                )
                AckKind.Task -> database.timerDao().insertTaskOperation(
                    PendingTaskOperationEntity.from(
                        taskOperation("task-operation-corrupt", "Task corrupt", 1).copy(
                            type = "invalid",
                        ),
                    ),
                )
                AckKind.Duration -> database.timerDao().upsertDurationOperation(
                    PendingDurationOperationEntity.from(
                        testDurationOperation(
                            "duration-corrupt",
                            TimerPhase.Focus,
                            1_500_001,
                            wallMs = now,
                        ).copy(occurredAt = java.time.Instant.ofEpochMilli(now).toString()),
                    ),
                )
                AckKind.AutoStart -> database.timerDao().insertAutoStartOperation(
                    PendingAutoStartOperationEntity.from(
                        testAutoStartOperation(
                            "not-a-uuid",
                            enabled = true,
                            wallMs = now,
                        ).copy(occurredAt = java.time.Instant.ofEpochMilli(now).toString()),
                    ),
                )
            }
            val service = TestRepositoryService(profile).apply {
                bootstrapResponse = response(revision = 0, timer = null)
            }
            val repository = signedInRepository(service)

            repository.initialize()
            awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

            assertEquals("kind=$kind", 0, service.syncCalls)
            assertEquals("kind=$kind", 1, repository.state.value.pendingCount)
        }
    }

    private suspend fun assertRevisionAccepted(responseRevision: Long) {
        val localTimer = testTimer("local-timer", status = TimerStatus.Paused)
        val serverTimer = testTimer("server-timer", status = TimerStatus.Running)
        val profile = testUser()
        database.timerDao().insertState(testState(profile, localTimer, revision = 5))
        val service = revisionService(profile, localTimer, serverTimer, responseRevision)
        val repository = signedInRepository(service)

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.syncStatus == SyncStatus.Synced }

        assertEquals(serverTimer, repository.state.value.timer)
        assertEquals(responseRevision, database.timerDao().localState()?.revision)
        assertEquals(repositoryJson.encodeToString(serverTimer), database.timerDao().localState()?.canonicalTimerJson)
    }

    private fun revisionService(
        profile: User,
        localTimer: CanonicalTimer,
        serverTimer: CanonicalTimer,
        responseRevision: Long,
    ) = TestRepositoryService(profile).apply {
        bootstrapResponse = response(revision = 5, timer = localTimer)
        syncResponse = response(revision = responseRevision, timer = serverTimer)
    }

    private suspend fun seedQueue(kind: AckKind): QueueFixture {
        val fixture = baseFixture()
        when (kind) {
            AckKind.Command -> database.timerDao().insertCommand(
                PendingCommandEntity.from(
                    testCommand("command-1", 1, fixture.localTimer.id, CommandType.Pause),
                ),
            )
            AckKind.Task -> database.timerDao().insertTaskOperation(
                PendingTaskOperationEntity.from(taskOperation("task-operation-1", "Task one", 1)),
            )
            AckKind.Duration -> database.timerDao().upsertDurationOperation(
                PendingDurationOperationEntity.from(
                    testDurationOperation("duration-1", TimerPhase.Focus, 30 * 60_000L),
                ),
            )
            AckKind.AutoStart -> database.timerDao().insertAutoStartOperation(
                PendingAutoStartOperationEntity.from(
                    testAutoStartOperation(
                        "00000000-0000-4000-8000-000000000001",
                        enabled = true,
                    ),
                ),
            )
        }
        return fixture
    }

    private suspend fun seedAllQueues(): QueueFixture {
        val fixture = baseFixture()
        database.timerDao().insertCommand(
            PendingCommandEntity.from(
                testCommand("command-1", 1, fixture.localTimer.id, CommandType.Pause),
            ),
        )
        database.timerDao().insertTaskOperation(
            PendingTaskOperationEntity.from(taskOperation("task-operation-1", "Task one", 1)),
        )
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(
                testDurationOperation("duration-1", TimerPhase.Focus, 30 * 60_000L),
            ),
        )
        database.timerDao().insertAutoStartOperation(
            PendingAutoStartOperationEntity.from(
                testAutoStartOperation(
                    "00000000-0000-4000-8000-000000000001",
                    enabled = true,
                ),
            ),
        )
        return fixture
    }

    private suspend fun seedTwoOfEveryQueueKind(): QueueFixture {
        val fixture = baseFixture()
        listOf(
            testCommand("command-1", 1, fixture.localTimer.id, CommandType.Pause),
            testCommand("command-2", 2, "other-timer", CommandType.Pause),
        ).forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        listOf(
            taskOperation("task-operation-1", "Task one", 1),
            taskOperation("task-operation-2", "Task two", 2),
        ).forEach {
            database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(it))
        }
        listOf(
            testDurationOperation("duration-1", TimerPhase.Focus, 30 * 60_000L),
            testDurationOperation("duration-2", TimerPhase.ShortBreak, 7 * 60_000L),
        ).forEach {
            database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(it))
        }
        listOf(
            testAutoStartOperation(
                "00000000-0000-4000-8000-000000000001",
                enabled = true,
            ),
            testAutoStartOperation(
                "00000000-0000-4000-8000-000000000002",
                enabled = false,
                wallMs = 1_767_225_600_001,
            ),
        ).forEach {
            database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(it))
        }
        return fixture
    }

    private suspend fun baseFixture(): QueueFixture {
        val profile = testUser()
        val localTimer = testTimer("local-timer", status = TimerStatus.Paused)
        val serverTimer = testTimer("server-timer", status = TimerStatus.Running)
        val tasks = listOf(
            requireNotNull(TaskReducer.taskFromTitle("Task one")),
            requireNotNull(TaskReducer.taskFromTitle("Task two")),
        )
        database.timerDao().insertState(
            testState(profile, localTimer).copy(
                tasksJson = repositoryJson.encodeToString(tasks),
                knownTasksJson = repositoryJson.encodeToString(tasks),
            ),
        )
        return QueueFixture(profile, localTimer, serverTimer, tasks)
    }

    private fun taskOperation(id: String, title: String, order: Long): TaskOperation {
        val task = requireNotNull(TaskReducer.taskFromTitle(title))
        return TaskOperation(
            id = id,
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_000 + order,
            hlcCounter = 0,
        )
    }

    private fun response(
        revision: Long,
        timer: CanonicalTimer?,
        tasks: List<FocusTask> = emptyList(),
        commandAcks: List<Acknowledgement> = emptyList(),
        taskAcks: List<TaskAcknowledgement> = emptyList(),
        durationAcks: List<DurationAcknowledgement> = emptyList(),
        autoStartAcks: List<AutoStartAcknowledgement> = emptyList(),
    ) = SyncResponse(
        acknowledgements = commandAcks,
        revision = revision,
        canonicalTimer = timer,
        history = emptyList(),
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = 1_767_225_600_000 + revision,
        serverHlcCounter = 0,
        durationAcknowledgements = durationAcks,
        durationsMs = DurationsMs(),
        taskAcknowledgements = taskAcks,
        tasks = tasks,
        autoStartAcknowledgements = autoStartAcks,
        autoStartBreaks = false,
    )

    private fun signedInRepository(service: TestRepositoryService) = testRepository(
        context,
        database.timerDao(),
        service,
        TestAuthSession(tokensAvailable = true),
    )

    private suspend fun assertQueuesEmpty(message: String) {
        assertTrue(message, database.timerDao().pendingCommands().isEmpty())
        assertTrue(message, database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(message, database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(message, database.timerDao().pendingAutoStartOperations().isEmpty())
    }

    private fun resetDatabase() {
        database.close()
        database = newDatabase()
    }

    private fun newDatabase() =
        Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()

    private fun outcomeReason(outcome: String) = if (outcome == "applied") "" else "$outcome outcome"

    private data class QueueFixture(
        val profile: User,
        val localTimer: CanonicalTimer,
        val serverTimer: CanonicalTimer,
        val canonicalTasks: List<FocusTask>,
    )

    private enum class AckKind { Command, Task, Duration, AutoStart }

    private companion object {
        val knownOutcomes = listOf("applied", "ignored", "rejected")
    }
}
