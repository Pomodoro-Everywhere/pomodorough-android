package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimerSyncValidationBehaviorTest {
    @Test
    fun completeCanonicalResponseAcceptsEveryCanonicalDomainAndAcknowledgement() {
        TimerSyncValidation.validateCanonicalResponse(completeResponse(), "sync")
    }

    @Test
    fun readOnlyCanonicalResponseRejectsAcknowledgements() {
        val error = assertThrows(SyncProtocolException::class.java) {
            TimerSyncValidation.validateCanonicalResponse(
                completeResponse(),
                "bootstrap",
                requireEmptyAcknowledgements = true,
            )
        }

        assertEquals("bootstrap returned acknowledgements for a read-only request", error.message)
    }

    @Test
    fun canonicalEnvelopeRejectsRevisionDurationTimeAndClockViolations() {
        val valid = completeResponse().withoutAcknowledgements()
        val invalid = listOf(
            valid.copy(revision = -1L),
            valid.copy(durationsMs = valid.durationsMs.copy(focus = 60_001L)),
            valid.copy(serverTime = "not-an-instant"),
            valid.copy(serverHlcWallMs = 0L),
        )

        invalid.forEach { response -> assertCanonicalRejected(response) }
    }

    @Test
    fun canonicalTimerRejectsIdentityStateDurationTimeTaskAndIntentViolations() {
        val response = completeResponse().withoutAcknowledgements()
        val timer = requireNotNull(response.canonicalTimer)
        val invalidTimers = listOf(
            timer.copy(id = ""),
            timer.copy(phase = "unknown"),
            timer.copy(status = "unknown"),
            timer.copy(plannedDurationMs = 1L),
            timer.copy(elapsedAtAnchorMs = timer.plannedDurationMs + 1L),
            timer.copy(anchorAt = "invalid"),
            timer.copy(taskId = "not-a-uuid"),
            timer.copy(lastIntent = timer.lastIntent?.copy(type = "unknown")),
            timer.copy(lastIntent = timer.lastIntent?.copy(commandId = "")),
            timer.copy(lastIntent = timer.lastIntent?.copy(occurredAt = "invalid")),
        )

        invalidTimers.forEach { timerValue ->
            assertCanonicalRejected(response.copy(canonicalTimer = timerValue))
        }
    }

    @Test
    fun canonicalHistoryRejectsDuplicatePendingAndMalformedTerminalRecords() {
        val response = completeResponse().withoutAcknowledgements()
        val completed = response.history[0]
        val cancelled = response.history[1]
        val invalidHistories = listOf(
            listOf(completed, cancelled.copy(id = completed.id)),
            listOf(completed, cancelled.copy(timerId = completed.timerId)),
            listOf(completed, cancelled.copy(commandId = completed.commandId)),
            listOf(completed.copy(pending = true), cancelled),
            listOf(completed.copy(completedAt = null), cancelled),
            listOf(completed.copy(endedAt = "invalid"), cancelled),
            listOf(completed, cancelled.copy(endedAt = null)),
            listOf(completed, cancelled.copy(completedAt = "invalid")),
            listOf(completed, cancelled.copy(taskId = "not-a-uuid")),
        )

        invalidHistories.forEach { history ->
            assertCanonicalRejected(response.copy(history = history))
        }
    }

    @Test
    fun canonicalTasksAndAcknowledgementsRejectMalformedOrDuplicateValues() {
        val response = completeResponse().withoutAcknowledgements()
        val task = response.tasks.single()
        val invalid = listOf(
            response.copy(tasks = listOf(task, task)),
            response.copy(tasks = listOf(task.copy(id = "not-a-uuid"))),
            response.copy(selectedTaskId = "not-a-uuid"),
            response.copy(acknowledgements = listOf(Acknowledgement("", "applied"))),
            response.copy(taskAcknowledgements = listOf(TaskAcknowledgement("task-op", "unknown"))),
            response.copy(durationAcknowledgements = listOf(DurationAcknowledgement("duration-op", "unknown"))),
            response.copy(autoStartAcknowledgements = listOf(AutoStartAcknowledgement("auto-op", "unknown"))),
            response.copy(selectedTaskAcknowledgements = listOf(SelectedTaskAcknowledgement("select-op", "unknown"))),
        )

        invalid.forEach(::assertCanonicalRejected)
    }

    @Test
    fun everyPendingQueueAcceptsValidOperations() {
        TimerSyncValidation.validatePendingQueues(completeQueues(), DeviceId)
    }

    @Test
    fun everyPendingQueueFailsClosedWithAQueueSpecificProtocolError() {
        val queues = completeQueues()
        val invalid = listOf(
            "Queued timer command is invalid" to queues.copy(
                commands = listOf(command().copy(observedElapsedMs = 1L)),
            ),
            "Queued task operation is invalid" to queues.copy(
                taskOperations = listOf(taskOperation().copy(title = null)),
            ),
            "Queued duration operation is invalid" to queues.copy(
                durationOperations = listOf(durationOperation().copy(durationMs = 60_001L)),
            ),
            "Queued auto-start operation is invalid" to queues.copy(
                autoStartOperations = listOf(autoStartOperation().copy(deviceId = "other-device")),
            ),
            "Queued selected-task operation is invalid" to queues.copy(
                selectedTaskOperations = listOf(selectedTaskOperation().copy(taskId = "not-a-uuid")),
            ),
        )

        invalid.forEach { (message, values) ->
            val error = assertThrows(SyncProtocolException::class.java) {
                TimerSyncValidation.validatePendingQueues(values, DeviceId)
            }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun persistedMutationRangesAcceptCompleteClockSampleAndUniqueQueues() {
        val local = localState().copy(
            serverClockOffsetMs = 10L,
            serverClockUncertaintyMs = 20L,
            serverClockSamplePhysicalMs = WallMs,
            serverClockSampleElapsedRealtimeMs = 30L,
            serverClockBootId = "boot-id",
        )

        TimerSyncValidation.validatePersistedMutationRanges(local, completeQueues())
    }

    @Test
    fun persistedMutationRangesRejectPartialClockSamplesAndDuplicateQueueIdentity() {
        val partial = localState().copy(serverClockOffsetMs = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            TimerSyncValidation.validatePersistedMutationRanges(partial, completeQueues())
        }

        val queues = completeQueues()
        val duplicate = queues.copy(commands = listOf(command(), command().copy(timerId = "timer-other")))
        assertThrows(IllegalArgumentException::class.java) {
            TimerSyncValidation.validatePersistedMutationRanges(localState(), duplicate)
        }
    }

    @Test
    fun bootstrapResolutionAcceptsCompletePayloadAndRejectsEnvelopeOrDuplicateOperations() {
        val request = resolutionRequest()
        TimerSyncValidation.validateResolutionEnvelope(request, DeviceId)

        listOf(
            request.copy(requestId = ""),
            request.copy(deviceId = "other-device"),
            request.copy(expectedRevision = -1L),
            request.copy(commands = listOf(command(), command().copy(timerId = "timer-other"))),
            request.copy(taskOperations = listOf(taskOperation(), taskOperation())),
            request.copy(durationOperations = listOf(durationOperation(), durationOperation())),
            request.copy(autoStartOperations = listOf(autoStartOperation(), autoStartOperation())),
            request.copy(selectedTaskOperations = listOf(selectedTaskOperation(), selectedTaskOperation())),
            request.copy(
                selectedTaskOperations = listOf(selectedTaskOperation().copy(taskId = "not-a-uuid")),
            ),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                TimerSyncValidation.validateResolutionEnvelope(invalid, DeviceId)
            }
        }
    }

    @Test
    fun bootstrapResolutionEnforcesEachCollectionLimitAt4096() {
        val empty = resolutionRequest().copy(
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = emptyList(),
            selectedTaskOperations = emptyList(),
        )
        val commands = List(4_097) { index ->
            command().copy(
                id = "command-$index",
                deviceSequence = (index + 1).toLong(),
            )
        }
        val taskOperations = List(4_097) { index ->
            taskOperation().copy(
                id = "task-operation-$index",
                taskId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                type = TaskOperationType.Delete,
                title = null,
            )
        }
        val durationOperations = List(4_097) { index ->
            durationOperation().copy(id = "duration-operation-$index")
        }
        val autoStartOperations = List(4_097) { index ->
            autoStartOperation().copy(id = "auto-start-operation-$index")
        }
        val selectedTaskOperations = List(4_097) { index ->
            selectedTaskOperation().copy(id = "selected-task-operation-$index")
        }
        val accepted = listOf(
            empty.copy(commands = commands.take(4_096)),
            empty.copy(taskOperations = taskOperations.take(4_096)),
            empty.copy(durationOperations = durationOperations.take(4_096)),
            empty.copy(autoStartOperations = autoStartOperations.take(4_096)),
            empty.copy(selectedTaskOperations = selectedTaskOperations.take(4_096)),
        )
        val rejected = listOf(
            empty.copy(commands = commands),
            empty.copy(taskOperations = taskOperations),
            empty.copy(durationOperations = durationOperations),
            empty.copy(autoStartOperations = autoStartOperations),
            empty.copy(selectedTaskOperations = selectedTaskOperations),
        )

        accepted.forEach { TimerSyncValidation.validateResolutionEnvelope(it, DeviceId) }
        rejected.forEach { request ->
            assertThrows(IllegalArgumentException::class.java) {
                TimerSyncValidation.validateResolutionEnvelope(request, DeviceId)
            }
        }
    }

    @Test
    fun profileValidationAcceptsInternationalIdentityAndRejectsUnsafeFields() {
        TimerSyncValidation.validateUser(
            User("account-1", "focus@example.test", "Zoë", "https://example.test/avatar.png"),
        )
        val invalid = listOf(
            User("", "focus@example.test", "Name", ""),
            User(" account ", "focus@example.test", "Name", ""),
            User("account\u0000", "focus@example.test", "Name", ""),
            User("account-1", "missing-at", "Name", ""),
            User("account-1", "two@@example.test", "Name", ""),
            User("account-1", " focus@example.test", "Name", ""),
            User("account-1", "focus@example.test", "Name\n", ""),
            User("account-1", "focus@example.test", "Name", "http://example.test/avatar.png"),
            User("account-1", "focus@example.test", "Name", "https:///missing-host"),
        )

        invalid.forEach { user ->
            assertThrows(ProfileProtocolException::class.java) {
                TimerSyncValidation.validateUser(user)
            }
        }
    }

    @Test
    fun acknowledgementSetRequiresExactIdentityWithoutDuplicatesOrOmissions() {
        TimerSyncValidation.validateAcknowledgementSet(setOf("one", "two"), listOf("two", "one"), "command")

        listOf(listOf("one"), listOf("one", "one"), listOf("one", "three")).forEach { values ->
            assertThrows(SyncProtocolException::class.java) {
                TimerSyncValidation.validateAcknowledgementSet(setOf("one", "two"), values, "command")
            }
        }
    }

    private fun assertCanonicalRejected(response: SyncResponse) {
        assertThrows(SyncProtocolException::class.java) {
            TimerSyncValidation.validateCanonicalResponse(response, "sync")
        }
    }

    private fun completeResponse(): SyncResponse {
        val task = focusTask()
        return SyncResponse(
            acknowledgements = listOf(Acknowledgement("command-1", "applied")),
            revision = 1L,
            canonicalTimer = canonicalTimer(task.id),
            history = listOf(completedHistory(task.id), cancelledHistory()),
            serverTime = At,
            serverHlcWallMs = WallMs,
            serverHlcCounter = 1L,
            durationAcknowledgements = listOf(DurationAcknowledgement("duration-1", "ignored")),
            durationsMs = DurationsMs(),
            taskAcknowledgements = listOf(TaskAcknowledgement("task-1", "rejected")),
            tasks = listOf(task),
            autoStartAcknowledgements = listOf(AutoStartAcknowledgement("auto-1", "applied")),
            selectedTaskAcknowledgements = listOf(SelectedTaskAcknowledgement("select-1", "ignored")),
            selectedTaskId = task.id,
        )
    }

    private fun SyncResponse.withoutAcknowledgements() = copy(
        acknowledgements = emptyList(),
        durationAcknowledgements = emptyList(),
        taskAcknowledgements = emptyList(),
        autoStartAcknowledgements = emptyList(),
        selectedTaskAcknowledgements = emptyList(),
    )

    private fun canonicalTimer(taskId: String) = CanonicalTimer(
        id = "timer-0001",
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000L,
        elapsedAtAnchorMs = 10L,
        anchorAt = At,
        taskId = taskId,
        lastIntent = TimerIntent(CommandType.Start, "command-1", At),
    )

    private fun completedHistory(taskId: String) = HistoryItem(
        id = "history-1",
        timerId = "timer-1",
        commandId = "finish-1",
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000L,
        completedAt = At,
        taskId = taskId,
    )

    private fun cancelledHistory() = HistoryItem(
        id = "history-2",
        timerId = "timer-2",
        commandId = "cancel-2",
        phase = TimerPhase.ShortBreak,
        status = TimerStatus.Cancelled,
        plannedDurationMs = 300_000L,
        endedAt = At,
    )

    private fun completeQueues() = PendingSyncQueues(
        commands = listOf(command()),
        taskOperations = listOf(taskOperation()),
        durationOperations = listOf(durationOperation()),
        autoStartOperations = listOf(autoStartOperation()),
        selectedTaskOperations = listOf(selectedTaskOperation()),
    )

    private fun command() = TimerCommand(
        id = "command-0001",
        deviceSequence = 1L,
        timerId = "timer-0001",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000L,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 0L,
        observedElapsedMs = 0L,
        taskId = focusTask().id,
        physicalOccurredAt = At,
    )

    private fun taskOperation(): TaskOperation {
        val task = focusTask()
        return TaskOperation("task-op-1", task.id, TaskOperationType.Upsert, task.title, At, WallMs, 1L)
    }

    private fun durationOperation() = DurationOperation(
        "duration-op-1", TimerPhase.Focus, 1_500_000L, At, WallMs, 2L,
    )

    private fun autoStartOperation() = AutoStartOperation(
        "auto-op-1", DeviceId, true, At, WallMs, 3L,
    )

    private fun selectedTaskOperation() = SelectedTaskOperation(
        "selected-op-1", focusTask().id, At, WallMs, 4L,
    )

    private fun focusTask(): FocusTask = requireNotNull(TaskReducer.taskFromTitle("Write coverage"))

    private fun localState() = LocalStateEntity(
        deviceId = DeviceId,
        deviceSequence = 1L,
        hlcWallMs = WallMs,
        hlcCounter = 4L,
        revision = 1L,
        settingsJson = "{}",
    )

    private fun resolutionRequest() = BootstrapResolutionRequest(
        requestId = "resolution-1",
        deviceId = DeviceId,
        expectedRevision = 1L,
        strategy = BootstrapStrategy.Merge,
        commands = listOf(command()),
        taskOperations = listOf(taskOperation()),
        durationOperations = listOf(durationOperation()),
        autoStartOperations = listOf(autoStartOperation()),
        selectedTaskOperations = listOf(selectedTaskOperation()),
    )

    private companion object {
        const val DeviceId = "device-0001"
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
    }
}
