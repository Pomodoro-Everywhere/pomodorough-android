package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralizedSyncCoordinatorTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch by lazy {
        { operation: String, input: String -> core.dispatch(operation, input) }
    }
    private val coordinator by lazy {
        val projection = CoreProjectionDispatcher(dispatch)
        CentralizedSyncCoordinator(
            json = json,
            bootstrapDispatcher = CoreBootstrapDispatcher(dispatch),
            reconciliationDispatcher = CoreReconciliationDispatcher(dispatch, projection),
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
        )
    }

    @Test
    fun syncPromotesDependentGeneratedBreakAfterFourthCanonicalFocus() {
        val snapshot = generatedBreakSnapshot()
        val attempt = attempt(snapshot)
        val response = response(
            acknowledgement = Acknowledgement(
                commandId = FinishId,
                outcome = "ignored",
                reason = "already completed",
            ),
            history = listOf(
                completedFocus("history-one", "focus-one", "finish-one", "2026-07-20T00:01:00Z"),
                completedFocus("history-two", "focus-two", "finish-two", "2026-07-20T00:02:00Z"),
                completedFocus("history-three", "focus-three", "finish-three", "2026-07-20T00:03:00Z"),
                completedFocus("history-four", SourceTimerId, FinishId, FinishTime),
            ),
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))
        val promoted = result.pending.queues.commands.single()

        assertEquals(GeneratedStartId, promoted.id)
        assertEquals(TimerPhase.LongBreak, promoted.phase)
        assertEquals(900_000L, promoted.plannedDurationMs)
        assertTrue(result.pending.dependencies.isEmpty())
        assertEquals(listOf(promoted), result.generatedCommands.released)
        assertTrue(result.generatedCommands.discarded.isEmpty())
        assertEquals(TimerPhase.LongBreak, result.projected.settings.selectedPhase)
        assertEquals(GeneratedTimerId, result.projected.projection.canonicalTimer?.id)
        assertEquals(GeneratedTimerId, result.local.ownedTimerId)
        assertEquals(ServerWallMs, result.local.hlcWallMs)
        assertEquals(7L, result.local.revision)
        assertEquals(CentralizedConflictTransition.Keep, result.conflict)
    }

    @Test
    fun syncUsesAuthoritativeCompletionPhaseWithoutNativeCadenceRecalculation() {
        val projection = CoreProjectionDispatcher(dispatch)
        val policyCoordinator = CentralizedSyncCoordinator(
            json = json,
            bootstrapDispatcher = CoreBootstrapDispatcher(dispatch),
            reconciliationDispatcher = CoreReconciliationDispatcher(dispatch, projection),
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher { _, _ ->
                completionOutput(TimerPhase.ShortBreak)
            },
            zoneId = ZoneId.of("UTC"),
        )
        val snapshot = generatedBreakSnapshot()
        val attempt = policyCoordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                SyncAttemptIdentity(9, "phase-attempt"), snapshot, ServerWallMs, 10_000,
            ),
        )
        val response = response(
            acknowledgement = Acknowledgement(FinishId, "ignored", "already completed"),
            history = listOf(
                completedFocus("history-one", "focus-one", "finish-one", "2026-07-20T00:01:00Z"),
                completedFocus("history-two", "focus-two", "finish-two", "2026-07-20T00:02:00Z"),
                completedFocus("history-three", "focus-three", "finish-three", "2026-07-20T00:03:00Z"),
                completedFocus("history-four", SourceTimerId, FinishId, FinishTime),
            ),
        )

        val result = policyCoordinator.applySync(applicationInput(snapshot, attempt, response))

        assertEquals(TimerPhase.ShortBreak, result.projected.settings.selectedPhase)
    }

    @Test
    fun ignoredFinishWithoutCanonicalCompletionDropsBreakAndRestoresSourceOwnership() {
        val snapshot = generatedBreakSnapshot()
        val attempt = attempt(snapshot)
        val canonical = sourceTimer()
        val response = response(
            acknowledgement = Acknowledgement(
                commandId = FinishId,
                outcome = "ignored",
                reason = "timer is not active",
            ),
            canonicalTimer = canonical,
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertTrue(result.pending.queues.commands.isEmpty())
        assertEquals(listOf(GeneratedStartId), result.generatedCommands.discarded.map(TimerCommand::id))
        assertEquals(setOf(SourceTimerId), result.generatedCommands.discardedSourceTimerIds)
        assertTrue(result.generatedCommands.released.isEmpty())
        assertEquals(SourceTimerId, result.projected.projection.canonicalTimer?.id)
        assertEquals(SourceTimerId, result.local.ownedTimerId)
        assertEquals(TimerPhase.Focus, result.projected.settings.selectedPhase)
        assertEquals(
            CentralizedConflictTransition.Replace(null),
            result.conflict,
        )
    }

    @Test
    fun legacyKeepRemoteClearsQueuesOmittedFromPersistedRequest() {
        val queues = PendingSyncQueues(
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = listOf(
                AutoStartOperation(
                    id = "auto-start-1",
                    deviceId = "device-1",
                    enabled = true,
                    occurredAt = "2026-07-20T00:00:00Z",
                    hlcWallMs = ServerWallMs,
                    hlcCounter = 0,
                ),
            ),
            selectedTaskOperations = listOf(
                SelectedTaskOperation(
                    id = "selected-task-1",
                    taskId = TaskOneId,
                    occurredAt = "2026-07-20T00:00:00Z",
                    hlcWallMs = ServerWallMs,
                    hlcCounter = 1,
                ),
            ),
        )
        val legacyRequest = BootstrapResolutionRequest(
            requestId = "legacy-keep-remote",
            deviceId = "device-1",
            expectedRevision = 7,
            strategy = BootstrapStrategy.KeepRemote,
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = null,
            selectedTaskOperations = null,
        )

        assertEquals(BootstrapStrategy.KeepRemote, legacyRequest.strategy)
        val resolved = coordinator.resolutionQueues(queues, keepRemote = true)

        assertTrue(resolved.commands.isEmpty())
        assertTrue(resolved.taskOperations.isEmpty())
        assertTrue(resolved.durationOperations.isEmpty())
        assertTrue(resolved.autoStartOperations.isEmpty())
        assertTrue(resolved.selectedTaskOperations.isEmpty())
    }

    @Test
    fun ignoredCommandReasonSurvivesTerminalQueueConvergence() {
        val snapshot = generatedBreakSnapshot().copy(
            queues = PendingSyncQueues(
                commands = listOf(finishCommand()),
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
            dependencies = emptyMap(),
        )
        val attempt = attempt(snapshot)
        val response = response(
            acknowledgement = Acknowledgement(FinishId, "ignored", "ignored outcome"),
            canonicalTimer = sourceTimer(),
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertTrue(result.pending.queues.commands.isEmpty())
        assertEquals(
            CentralizedConflictTransition.Replace("ignored outcome"),
            result.conflict,
        )
    }

    @Test
    fun mixedIgnoredTasksAndRejectedDurationsAggregateInResponseOrder() {
        val tasks = listOf(taskOperation("task-operation-1"), taskOperation("task-operation-2"))
        val durations = listOf(
            durationOperation("duration-operation-1", TimerPhase.Focus),
            durationOperation("duration-operation-2", TimerPhase.ShortBreak),
        )
        val snapshot = generatedBreakSnapshot().copy(
            queues = PendingSyncQueues(
                commands = emptyList(),
                taskOperations = tasks,
                durationOperations = durations,
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
            dependencies = emptyMap(),
            canonicalTimer = null,
        )
        val attempt = attempt(snapshot)
        val response = response(acknowledgement = null).copy(
            taskAcknowledgements = attempt.request.taskOperations.reversed().map {
                TaskAcknowledgement(it.id, "ignored", "superseded")
            },
            durationAcknowledgements = attempt.request.durationOperations.reversed().map {
                DurationAcknowledgement(it.id, "rejected", "conflict")
            },
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertTrue(result.pending.queues.taskOperations.isEmpty())
        assertTrue(result.pending.queues.durationOperations.isEmpty())
        assertEquals(
            CentralizedConflictTransition.Replace(
                "Task: superseded\nTask: superseded\nDuration: conflict\nDuration: conflict",
            ),
            result.conflict,
        )
    }

    @Test
    fun syncAttemptExcludesDependencyChildAndRejectsMissingAcknowledgement() {
        val snapshot = generatedBreakSnapshot()
        val attempt = attempt(snapshot)

        assertEquals(listOf(FinishId), attempt.request.commands.map(TimerCommand::id))
        assertEquals("device-1", attempt.request.deviceId)
        assertEquals(snapshot.local.revision, attempt.request.lastRevision)

        val error = assertThrows(SyncProtocolException::class.java) {
            coordinator.applySync(
                applicationInput(snapshot, attempt, response(acknowledgement = null)),
            )
        }

        assertEquals("Sync returned an invalid command acknowledgement set", error.message)
    }

    @Test
    fun syncRevisionRegressionWinsOverAcknowledgementValidation() {
        val snapshot = generatedBreakSnapshot()
        val attempt = attempt(snapshot)
        val regressed = response(acknowledgement = null).copy(revision = 2)

        val error = assertThrows(SyncProtocolException::class.java) {
            coordinator.applySync(applicationInput(snapshot, attempt, regressed))
        }

        assertEquals("Sync revision regressed from 3 to 2", error.message)
    }

    @Test
    fun bootstrapInstallationRejectsRevisionRegressionBeforeReconciliation() {
        val snapshot = generatedBreakSnapshot()
        val regressed = response(acknowledgement = null).copy(revision = 2)

        val error = assertThrows(SyncProtocolException::class.java) {
            coordinator.applyBootstrapInstallation(
                CentralizedBootstrapInstallationInput(
                    snapshot = snapshot,
                    profile = User("user-1", "user@example.com", "User", ""),
                    response = regressed,
                    clearLocal = false,
                    sampledLocal = snapshot.local,
                    localizedTimer = regressed.canonicalTimer,
                    localizedHistory = regressed.history,
                    projectionNow = Instant.parse(regressed.serverTime),
                ),
            )
        }

        assertEquals("Bootstrap revision regressed from 3 to 2", error.message)
    }

    @Test
    fun bootstrapPlanKeepsRemoteStateForDifferentOwner() {
        val plan = coordinator.bootstrapPlan(
            CentralizedBootstrapPlanningInput(
                localOwnerId = "local-user",
                currentUserId = "remote-user",
                localHistory = listOf(
                    completedFocus("local-history", "local-timer", "local-finish", FinishTime),
                ),
                remoteHistory = emptyList(),
                hasLocalState = true,
                hasRemoteState = false,
            ),
        )

        assertEquals(
            CoreBootstrapPlan.Automatic(BootstrapStrategy.KeepRemote),
            plan,
        )
    }

    private fun generatedBreakSnapshot(): CentralizedSyncSnapshot {
        val settings = TimerSettings(
            selectedPhase = TimerPhase.ShortBreak,
            autoStartBreaks = true,
        )
        val source = sourceTimer()
        return CentralizedSyncSnapshot(
            local = LocalStateEntity(
                deviceId = "device-1",
                deviceSequence = 2,
                hlcWallMs = GeneratedWallMs,
                hlcCounter = 0,
                revision = 3,
                settingsJson = json.encodeToString(settings),
                ownedTimerId = GeneratedTimerId,
            ),
            queues = PendingSyncQueues(
                commands = listOf(finishCommand(), generatedStart()),
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = emptyList(),
                selectedTaskOperations = emptyList(),
            ),
            dependencies = mapOf(GeneratedStartId to FinishId),
            canonicalTimer = source,
            canonicalHistory = emptyList(),
            canonicalTasks = emptyList(),
            canonicalAutoStartBreaks = true,
            knownTasks = emptyMap(),
            settings = settings,
            selectedPhaseGeneration = 4,
        )
    }

    private fun attempt(snapshot: CentralizedSyncSnapshot) = coordinator.prepareSyncAttempt(
        CentralizedSyncAttemptInput(
            identity = SyncAttemptIdentity(9, "test-attempt"),
            snapshot = snapshot,
            sentPhysicalMs = ServerWallMs,
            sentElapsedRealtimeMs = 10_000,
        ),
    )

    private fun applicationInput(
        snapshot: CentralizedSyncSnapshot,
        attempt: SyncAttempt,
        response: SyncResponse,
    ) = CentralizedSyncApplicationInput(
        snapshot = snapshot,
        attempt = attempt,
        response = response,
        sampledLocal = snapshot.local.copy(
            hlcWallMs = ServerWallMs,
            hlcCounter = 3,
        ),
        localizedTimer = response.canonicalTimer,
        localizedHistory = response.history,
        projectionNow = Instant.parse(response.serverTime),
    )

    private fun finishCommand() = TimerCommand(
        id = FinishId,
        deviceSequence = 1,
        timerId = SourceTimerId,
        type = CommandType.Finish,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = FinishTime,
        hlcWallMs = FinishWallMs,
        hlcCounter = 0,
        observedElapsedMs = 1_500_000,
        physicalOccurredAt = FinishTime,
    )

    private fun generatedStart() = TimerCommand(
        id = GeneratedStartId,
        deviceSequence = 2,
        timerId = GeneratedTimerId,
        type = CommandType.Start,
        phase = TimerPhase.ShortBreak,
        plannedDurationMs = 300_000,
        occurredAt = GeneratedTime,
        hlcWallMs = GeneratedWallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
        physicalOccurredAt = GeneratedTime,
    )

    private fun taskOperation(id: String) = TaskOperation(
        id = id,
        taskId = if (id.endsWith("1")) TaskOneId else TaskTwoId,
        type = TaskOperationType.Delete,
        title = null,
        occurredAt = FinishTime,
        hlcWallMs = FinishWallMs,
        hlcCounter = 0,
    )

    private fun durationOperation(id: String, phase: String) = DurationOperation(
        id = id,
        phase = phase,
        durationMs = if (phase == TimerPhase.Focus) 1_800_000 else 420_000,
        occurredAt = FinishTime,
        hlcWallMs = FinishWallMs,
        hlcCounter = 0,
    )

    private fun sourceTimer() = CanonicalTimer(
        id = SourceTimerId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-07-20T00:00:00Z",
    )

    private fun completedFocus(
        id: String,
        timerId: String,
        commandId: String,
        completedAt: String,
    ) = HistoryItem(
        id = id,
        timerId = timerId,
        commandId = commandId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = completedAt,
    )

    private fun response(
        acknowledgement: Acknowledgement?,
        canonicalTimer: CanonicalTimer? = null,
        history: List<HistoryItem> = emptyList(),
    ) = SyncResponse(
        acknowledgements = listOfNotNull(acknowledgement),
        revision = 7,
        canonicalTimer = canonicalTimer,
        history = history,
        serverTime = "2026-07-20T00:06:00Z",
        serverHlcWallMs = ServerWallMs,
        serverHlcCounter = 2,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = true,
    )

    private fun completionOutput(selectedPhase: String) = buildJsonObject {
        put("expired", false)
        put("commandEligible", false)
        put("reserveGeneratedBreak", false)
        put("selectedPhase", selectedPhase)
        put("queueAutoBreak", true)
        put("generatedBreakEligible", false)
        put("sourceAlreadyAccepted", false)
    }

    private companion object {
        const val FinishId = "finish-sent"
        const val GeneratedStartId = "generated-start"
        const val SourceTimerId = "focus-four"
        const val GeneratedTimerId = "break-generated"
        const val TaskOneId = "00000000-0000-4000-8000-000000000001"
        const val TaskTwoId = "00000000-0000-4000-8000-000000000002"
        const val FinishTime = "2026-07-20T00:04:00Z"
        const val GeneratedTime = "2026-07-20T00:05:00Z"
        val FinishWallMs = Instant.parse(FinishTime).toEpochMilli()
        val GeneratedWallMs = Instant.parse(GeneratedTime).toEpochMilli()
        val ServerWallMs = Instant.parse("2026-07-20T00:06:00Z").toEpochMilli()
    }
}
