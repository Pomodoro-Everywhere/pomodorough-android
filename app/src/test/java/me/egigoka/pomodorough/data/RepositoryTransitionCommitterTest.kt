package me.egigoka.pomodorough.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.storage.FullSyncStorageUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTransitionCommitterTest {
    @Test
    fun timerBatchCommitCannotPublishBeforeAtomicPersistenceCompletes() = runTest {
        val enteredPersistence = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persistence = RecordingTransitionPersistence().apply {
            beforeTimerBatch = {
                enteredPersistence.complete(Unit)
                releasePersistence.await()
            }
        }
        val committer = RepositoryTransitionCommitter(persistence)
        val plan = timerMutationPlan()

        val result = async {
            committer.commit(RepositoryTimerCommandBatchTransition(plan))
        }
        enteredPersistence.await()
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(listOf("timer-batch"), persistence.calls)

        releasePersistence.complete(Unit)
        assertSame(plan, result.await().plan)
    }

    @Test
    fun syncKeepsAckRetainedDiscardedAndDependencyPartitionsExact() = runTest {
        val persistence = RecordingTransitionPersistence()
        val committer = RepositoryTransitionCommitter(persistence)
        val acknowledged = SyncRequest(
            deviceId = "device",
            lastRevision = 2,
            commands = listOf(timerCommand("ack")),
            durationOperations = emptyList(),
        )
        val retained = PendingSyncQueues(
            commands = listOf(timerCommand("retained")),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val update = FullSyncStorageUpdate(
            local = localState(),
            acknowledged = acknowledged,
            acknowledgedDurationOperationIds = listOf("duration-ack"),
            retained = retained,
            retainedCommandDependencies = mapOf("retained" to "parent"),
            discardedCommands = listOf(timerCommand("discarded")),
            discardedCommandDependencies = mapOf("discarded" to "finish"),
        )
        val transition = RepositorySyncTransition(
            update = update,
            application = centralizedApplication(update.local, retained),
            response = syncResponse(revision = 9),
            clockSample = ServerClockSample(1, 2, 3, 4, 5),
        )

        val event = committer.commit(transition)

        assertSame(update, persistence.fullSyncUpdate)
        assertSame(transition.application, event.application)
        assertEquals(listOf("sync"), persistence.calls)
    }

    @Test
    fun bootstrapInstallEventWaitsForAtomicPersistence() = runTest {
        val enteredPersistence = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persistence = RecordingTransitionPersistence().apply {
            beforeBootstrapInstall = {
                enteredPersistence.complete(Unit)
                releasePersistence.await()
            }
        }
        val committer = RepositoryTransitionCommitter(persistence)
        val application = centralizedApplication(
            localState(),
            PendingSyncQueues(
                commands = emptyList(),
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
        )
        val transition = RepositoryBootstrapInstallationTransition(
            application = application,
            response = syncResponse(revision = 5),
            clearLocal = true,
            clockSample = ServerClockSample(1, 2, 3, 4, 5),
        )

        val result = async { committer.commit(transition) }
        enteredPersistence.await()
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(listOf("bootstrap-install"), persistence.calls)

        releasePersistence.complete(Unit)
        val event = result.await()
        assertSame(application, event.application)
        assertEquals(true, persistence.bootstrapClearLocal)
    }

    private fun timerMutationPlan(): TimerCommandMutationPlan {
        val command = timerCommand("command")
        return TimerCommandMutationPlan(
            commands = listOf(command),
            dependencies = mapOf(command.id to "finish"),
            local = localState(),
            settings = TimerSettings(),
            projection = emptyProjection(),
        )
    }
}

private class RecordingTransitionPersistence : RepositoryTransitionPersistence {
    val calls = mutableListOf<String>()
    var beforeTimerBatch: suspend () -> Unit = {}
    var beforeBootstrapInstall: suspend () -> Unit = {}
    var fullSyncUpdate: FullSyncStorageUpdate? = null
    var bootstrapClearLocal: Boolean? = null

    override suspend fun persist(transition: RepositoryTransition<*>) {
        when (transition) {
            is RepositoryBootstrapPreparationTransition -> calls += "bootstrap-prepare"
            is RepositorySyncTransition -> recordSync(transition)
            is RepositoryBootstrapResolutionTransition -> calls += "bootstrap-resolve"
            is RepositoryBootstrapInstallationTransition -> recordBootstrapInstall(transition)
            is RepositoryTimerCommandTransition -> calls += "timer-single"
            is RepositoryTimerCommandBatchTransition -> {
                calls += "timer-batch"
                beforeTimerBatch()
            }
            is RepositoryDurationTransition -> calls += "duration"
            is RepositoryAutoStartTransition -> calls += "auto-start"
            is RepositorySelectedTaskTransition -> calls += "selected-task"
            is RepositoryTaskTransition -> calls += "task"
        }
    }

    private fun recordSync(transition: RepositorySyncTransition) {
        calls += "sync"
        fullSyncUpdate = transition.update
    }

    private suspend fun recordBootstrapInstall(
        transition: RepositoryBootstrapInstallationTransition,
    ) {
        calls += "bootstrap-install"
        bootstrapClearLocal = transition.clearLocal
        beforeBootstrapInstall()
    }
}

private fun localState() = LocalStateEntity(deviceId = "device", settingsJson = "{}")

private fun timerCommand(id: String) = TimerCommand(
    id = id,
    deviceSequence = 1,
    timerId = "timer-$id",
    type = CommandType.Start,
    phase = TimerPhase.Focus,
    plannedDurationMs = 60_000,
    occurredAt = "1970-01-01T00:00:00Z",
    hlcWallMs = 1,
    hlcCounter = 0,
    observedElapsedMs = 0,
)

private fun emptyProjection() = CoreProjectionResult(
    canonicalTimer = null,
    history = emptyList(),
    tasks = emptyList(),
    durationsMs = DurationsMs(),
    autoStartBreaks = false,
    selectedTaskId = null,
    timerOutcomes = emptyMap(),
    winningOperationIds = CoreWinningOperationIds(
        tasks = emptyMap(),
        durations = emptyMap(),
        autoStart = null,
        selectedTask = null,
    ),
)

private fun centralizedApplication(
    local: LocalStateEntity,
    retained: PendingSyncQueues,
) = CentralizedSyncApplication(
    local = local,
    pending = CentralizedReconciledPending(
        local = local,
        queues = retained,
        dependencies = emptyMap(),
        core = emptyReconciliation(local),
    ),
    canonical = CentralizedCanonicalState(
        timer = null,
        history = emptyList(),
        tasks = emptyList(),
        knownTasks = emptyMap(),
    ),
    projected = CentralizedProjectedState(
        queues = retained,
        settings = TimerSettings(),
        projection = emptyProjection(),
    ),
    generatedCommands = GeneratedTimerResolution(
        released = emptyList(),
        discarded = emptyList(),
        discardedSourceTimerIds = emptySet(),
    ),
    conflict = CentralizedConflictTransition.Keep,
)

private fun emptyReconciliation(local: LocalStateEntity) = CoreReconciliationResult(
    revision = local.revision,
    pending = CoreProjectionPending(),
    dependencies = emptyList(),
    promotedTimerOperationIds = emptySet(),
    droppedTimerOperationIds = emptySet(),
    droppedTimerIds = emptySet(),
    base = CoreProjectionBase(),
    projection = emptyProjection(),
)
