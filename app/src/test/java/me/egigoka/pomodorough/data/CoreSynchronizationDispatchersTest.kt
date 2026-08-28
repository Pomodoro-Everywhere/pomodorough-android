package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSynchronizationDispatchersTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }

    @Test
    fun bootstrapPlanUsesRealWasmAndPreservesTypedDecisions() {
        val dispatcher = CoreBootstrapDispatcher(dispatch)

        assertEquals(
            CoreBootstrapPlan.NormalSync,
            dispatcher.plan(
                localOwnerId = "user-1",
                currentUserId = "user-1",
                localHistory = emptyList(),
                remoteHistory = emptyList(),
                hasLocalState = true,
                hasRemoteState = true,
            ),
        )
        assertEquals(
            CoreBootstrapPlan.Choose(1, 1),
            dispatcher.plan(
                localOwnerId = null,
                currentUserId = "user-1",
                localHistory = listOf(history("local")),
                remoteHistory = listOf(history("remote")),
                hasLocalState = true,
                hasRemoteState = true,
            ),
        )
        assertEquals(
            CoreBootstrapPlan.Automatic(BootstrapStrategy.KeepRemote),
            dispatcher.plan(
                localOwnerId = null,
                currentUserId = "user-1",
                localHistory = emptyList(),
                remoteHistory = emptyList(),
                hasLocalState = false,
                hasRemoteState = false,
            ),
        )
        assertEquals(
            CoreBootstrapPlan.Automatic(BootstrapStrategy.ReplaceRemote),
            dispatcher.plan(
                localOwnerId = null,
                currentUserId = "user-1",
                localHistory = listOf(history("local")),
                remoteHistory = emptyList(),
                hasLocalState = true,
                hasRemoteState = false,
            ),
        )
    }

    @Test
    fun reconciliationRebasesRetainedOperationThroughRealWasm() {
        val taskOperation = TaskOperation(
            id = "task-operation-1",
            taskId = "aaf83054-24b2-8c0e-901f-a974147bfe82",
            type = TaskOperationType.Upsert,
            title = "Café",
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_000,
            hlcCounter = 0,
        )
        val result = CoreReconciliationDispatcher(dispatch).rebase(
            local = CoreProjectionPending(
                taskOperations = listOf(DeviceOperation("device-1", taskOperation)),
            ),
            sent = CoreReconciliationSent(),
            response = emptyResponse(),
            dependencies = emptyList(),
        )

        val rebased = result.pending.taskOperations.single().value
        assertTrue(rebased.hlcWallMs >= ServerWallMs)
        assertEquals(taskOperation.id, rebased.id)
        assertEquals(
            listOf(FocusTask("aaf83054-24b2-8c0e-901f-a974147bfe82", "Café")),
            result.projection.tasks,
        )
        assertEquals(7L, result.revision)
    }

    @Test
    fun reconciliationRebasesTaskDeletionThroughRealWasm() {
        val task = FocusTask("aaf83054-24b2-8c0e-901f-a974147bfe82", "Café")
        val operation = TaskOperation(
            id = "task-operation-delete",
            taskId = task.id,
            type = TaskOperationType.Delete,
            title = null,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_000,
            hlcCounter = 0,
        )

        val result = CoreReconciliationDispatcher(dispatch).rebase(
            local = CoreProjectionPending(
                taskOperations = listOf(DeviceOperation("device-1", operation)),
            ),
            sent = CoreReconciliationSent(),
            response = emptyResponse().copy(tasks = listOf(task)),
            dependencies = emptyList(),
        )

        assertEquals(null, result.pending.taskOperations.single().value.title)
        assertTrue(result.projection.tasks.isEmpty())
    }

    @Test
    fun reconciliationRebasesTimerCommandThroughRealWasm() {
        val timer = CanonicalTimer(
            id = "timer-1",
            phase = TimerPhase.Focus,
            status = TimerStatus.Running,
            plannedDurationMs = 1_500_000,
            elapsedAtAnchorMs = 0,
            anchorAt = "2026-01-01T00:00:00Z",
        )
        val command = TimerCommand(
            id = "command-1",
            deviceSequence = 1,
            timerId = timer.id,
            type = CommandType.Pause,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = "2026-01-01T00:05:00Z",
            hlcWallMs = 1_000,
            hlcCounter = 0,
            observedElapsedMs = 300_000,
        )

        val result = CoreReconciliationDispatcher(dispatch).rebase(
            local = CoreProjectionPending(
                commands = listOf(DeviceOperation("device-1", command)),
            ),
            sent = CoreReconciliationSent(),
            response = emptyResponse().copy(canonicalTimer = timer),
            dependencies = emptyList(),
        )

        assertEquals(command.id, result.pending.commands.single().value.id)
        assertEquals(TimerStatus.Paused, result.projection.canonicalTimer?.status)
    }

    @Test
    fun reconciliationResolvesGeneratedBreakThroughRealWasm() {
        val finish = TimerCommand(
            id = "finish-1",
            deviceSequence = 1,
            timerId = "focus-timer",
            type = CommandType.Finish,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_000,
            hlcCounter = 0,
            observedElapsedMs = 1_500_000,
        )
        val generatedStart = TimerCommand(
            id = "generated-start-1",
            deviceSequence = 2,
            timerId = "break-timer",
            type = CommandType.Start,
            phase = TimerPhase.LongBreak,
            plannedDurationMs = 900_000,
            occurredAt = "2026-01-01T00:00:01Z",
            hlcWallMs = 2_000,
            hlcCounter = 0,
            observedElapsedMs = 0,
        )
        val local = CoreProjectionPending(
            commands = listOf(
                DeviceOperation("device-1", finish),
                DeviceOperation("device-1", generatedStart),
            ),
        )
        val dependency = CoreTimerDependency(
            operationId = generatedStart.id,
            dependsOnOperationId = finish.id,
            generatedBreak = true,
            sourceDayStart = "2026-01-01T00:00:00Z",
            sourceDayEnd = "2026-01-02T00:00:00Z",
        )
        val dispatcher = CoreReconciliationDispatcher(dispatch)
        val acceptedResponse = emptyResponse().copy(
            acknowledgements = listOf(Acknowledgement(finish.id, "applied")),
            history = listOf(
                history("focus-history").copy(
                    timerId = finish.timerId,
                    commandId = finish.id,
                    completedAt = finish.occurredAt,
                ),
            ),
        )

        val promoted = dispatcher.rebase(
            local = local,
            sent = CoreReconciliationSent(commands = listOf(finish.id)),
            response = acceptedResponse,
            dependencies = listOf(dependency),
        )

        assertEquals(setOf(generatedStart.id), promoted.promotedTimerOperationIds)
        assertTrue(promoted.dependencies.isEmpty())
        assertEquals(TimerPhase.ShortBreak, promoted.pending.commands.single().value.phase)
        assertEquals(300_000L, promoted.pending.commands.single().value.plannedDurationMs)

        val dropped = dispatcher.rebase(
            local = local,
            sent = CoreReconciliationSent(commands = listOf(finish.id)),
            response = emptyResponse().copy(
                acknowledgements = listOf(Acknowledgement(finish.id, "rejected", "conflict")),
            ),
            dependencies = listOf(dependency),
        )

        assertTrue(dropped.pending.commands.isEmpty())
        assertEquals(setOf(generatedStart.id), dropped.droppedTimerOperationIds)
        assertEquals(setOf(generatedStart.timerId), dropped.droppedTimerIds)
    }

    @Test
    fun bootstrapAdapterDoesNotRecomputeSharedCorePolicy() {
        val automatic = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "auto")
                put("strategy", "merge")
                put("reason", "empty")
            }
        }.plan(
            localOwnerId = "same-user",
            currentUserId = "same-user",
            localHistory = listOf(history("local")),
            remoteHistory = listOf(history("remote")),
            hasLocalState = true,
            hasRemoteState = true,
        )
        val choice = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "choose")
                put("localHistoryCount", 7)
                put("remoteHistoryCount", 9)
            }
        }.plan(null, "user", emptyList(), emptyList(), false, false)
        val normal = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "normal_sync")
                put("reason", "future-compatible-reason")
            }
        }.plan("different-owner", "user", emptyList(), emptyList(), true, true)

        assertEquals(CoreBootstrapPlan.Automatic(BootstrapStrategy.Merge), automatic)
        assertEquals(CoreBootstrapPlan.Choose(7, 9), choice)
        assertEquals(CoreBootstrapPlan.NormalSync, normal)
    }

    @Test
    fun bootstrapAdapterPreservesContractualErrors() {
        val validDispatcher = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "normal_sync")
                put("reason", "same_owner")
            }
        }
        val identityError = assertThrows(CoreProjectionException.InvalidInput::class.java) {
            validDispatcher.plan(null, " ", emptyList(), emptyList(), false, false)
        }
        assertEquals("Shared Core bootstrap identity is invalid", identityError.message)

        val undecodableDispatcher = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "normal_sync")
                put("reason", "same_owner")
                put("unexpected", true)
            }
        }
        val decodeError = assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            undecodableDispatcher.plan(null, "user", emptyList(), emptyList(), false, false)
        }
        assertEquals("Could not decode Shared Core bootstrap plan", decodeError.message)
    }

    @Test
    fun adaptersRejectMalformedAndSemanticallyInvalidOutputs() {
        val invalidBootstrap = CoreBootstrapDispatcher { _, _ ->
            buildJsonObject {
                put("mode", "auto")
                put("strategy", "merge")
            }
        }
        val bootstrapError = assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            invalidBootstrap.plan(null, "user-1", emptyList(), emptyList(), false, false)
        }
        assertEquals("Shared Core returned invalid bootstrap plan", bootstrapError.message)

        val invalidReconciliation = CoreReconciliationDispatcher(
            dispatch = { _, _ -> buildJsonObject {} },
        )
        assertThrows(CoreProjectionException.InvalidOutput::class.java) {
            invalidReconciliation.rebase(
                local = CoreProjectionPending(),
                sent = CoreReconciliationSent(),
                response = emptyResponse(),
                dependencies = emptyList(),
            )
        }
    }

    @Test
    fun reconciliationRejectsMissingAcknowledgementThroughRealWasm() {
        val taskOperation = TaskOperation(
            id = "task-operation-1",
            taskId = "aaf83054-24b2-8c0e-901f-a974147bfe82",
            type = TaskOperationType.Upsert,
            title = "Café",
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_000,
            hlcCounter = 0,
        )

        assertThrows(SharedCoreException.Operation::class.java) {
            CoreReconciliationDispatcher(dispatch).rebase(
                local = CoreProjectionPending(
                    taskOperations = listOf(DeviceOperation("device-1", taskOperation)),
                ),
                sent = CoreReconciliationSent(taskOperations = listOf(taskOperation.id)),
                response = emptyResponse().copy(
                    tasks = listOf(FocusTask(taskOperation.taskId, "Café")),
                ),
                dependencies = emptyList(),
            )
        }
    }

    private fun emptyResponse() = SyncResponse(
        acknowledgements = emptyList(),
        revision = 7,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = ServerWallMs,
        serverHlcCounter = 4,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )

    private fun history(id: String) = HistoryItem(
        id = id,
        timerId = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = "2026-01-01T00:25:00Z",
    )

    private companion object {
        const val ServerWallMs = 1_767_225_600_000L
    }
}
