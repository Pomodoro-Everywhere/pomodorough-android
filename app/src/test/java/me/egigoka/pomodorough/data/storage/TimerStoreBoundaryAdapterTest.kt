package me.egigoka.pomodorough.data.storage

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.PendingSyncQueues
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.CentralizedSyncDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TimerStoreBoundaryAdapterTest {
    private val json = Json { explicitNulls = false }
    private val strict = Json(json) { ignoreUnknownKeys = false }

    @Test
    fun loadWorkspaceConvertsEveryQueueAndLetsCanonicalTaskWinKnownTaskIdentity() = runBlocking {
        val local = state().copy(
            canonicalAutoStartBreaks = true,
            tasksJson = json.encodeToString(listOf(FocusTask("task", "Canonical"))),
            knownTasksJson = json.encodeToString(
                listOf(FocusTask("task", "Stale"), FocusTask("known", "Known")),
            ),
            userJson = json.encodeToString(user()),
        )
        val command = PendingCommandEntity.from(command(), "finish")
        val values = queueValues(local, command)
        val store = TimerStore(proxy(values).first, json, strict) {}

        val loaded = store.loadWorkspace()

        assertSame(local, loaded.local)
        assertEquals(listOf(command.toModel()), loaded.pending.commands)
        assertEquals(mapOf(command.id to "finish"), loaded.commandDependencies)
        assertEquals(listOf(task()), loaded.pending.taskOperations)
        assertEquals(listOf(duration()), loaded.pending.durationOperations)
        assertEquals(listOf(autoStart()), loaded.pending.autoStartOperations)
        assertEquals(listOf(selection()), loaded.pending.selectedTaskOperations)
        assertEquals("Canonical", loaded.knownTasks.getValue("task").title)
        assertEquals("Known", loaded.knownTasks.getValue("known").title)
        assertEquals(true, loaded.canonicalAutoStartBreaks)
        assertEquals(user(), loaded.user)
    }

    @Test
    fun mutationAdaptersPreserveDependenciesAndOptionalSelection() = runBlocking {
        val (dao, calls) = proxy(emptyMap())
        val store = TimerStore(dao, json, strict) {}
        val local = state()
        val command = command()

        store.saveTimerCommand(local, command, "finish")
        store.saveTimerCommands(local, listOf(command), mapOf(command.id to "finish"))
        store.saveTaskOperation(local, task(), selection())
        store.saveDurationOperation(local, duration())
        store.saveAutoStartOperation(local, autoStart())
        store.saveSelectedTaskOperation(local, selection())
        store.saveState(local)
        store.deleteCommands(listOf(PendingCommandEntity.from(command)))
        store.discardBootstrapResolution()

        assertEquals(
            listOf(
                "persistCommand", "persistCommands", "persistTaskOperation",
                "persistDurationOperation", "persistAutoStartOperation",
                "persistSelectedTaskOperation", "updateState", "deleteCommands",
                "deleteBootstrapResolution",
            ),
            calls.map(Call::name),
        )
        val single = calls[0].arguments[0] as PendingCommandEntity
        assertEquals("finish", single.generatedByFinishCommandId)
        val batch = calls[1].arguments[0] as List<*>
        assertEquals("finish", (batch.single() as PendingCommandEntity).generatedByFinishCommandId)
        assertEquals(PendingSelectedTaskOperationEntity.from(selection()), calls[2].arguments[2])
    }

    @Test
    fun syncAndBootstrapAdaptersMapAllDomainsIntoSingleDaoCalls() = runBlocking {
        val (dao, calls) = proxy(emptyMap())
        val store = TimerStore(dao, json, strict) {}
        val queues = queues()
        val resolution = resolution()
        store.saveMutationState(state(), queues, mapOf("command" to "finish"))
        store.prepareBootstrap(
            BootstrapPreparationStorageUpdate(
                state(), queues, mapOf("command" to "finish"), resolution,
            ),
        )
        store.applyBootstrapResolution(
            BootstrapResolutionStorageUpdate(
                local = state(), clearAutoStartOperations = false,
                retainedCommands = listOf(command()),
                retainedCommandDependencies = mapOf("command" to "finish"),
                retainedAutoStartOperations = listOf(autoStart()),
                clearSelectedTaskOperations = false,
                retainedSelectedTaskOperations = listOf(selection()),
            ),
        )
        store.applyFullSync(
            FullSyncStorageUpdate(
                local = state(),
                acknowledged = SyncRequest(
                    "device", 1, listOf(command()), listOf(duration()),
                    listOf(task()), listOf(autoStart()), listOf(selection()),
                ),
                acknowledgedDurationOperationIds = listOf("duration"),
                retained = queues,
                retainedCommandDependencies = mapOf("command" to "finish"),
                discardedCommands = listOf(command().copy(id = "discarded")),
                discardedCommandDependencies = mapOf("discarded" to "discard-finish"),
            ),
        )
        store.clearAccount(state())

        assertEquals(
            listOf(
                "updateMutationState", "persistBootstrapPreparation",
                "applyBootstrapResolution", "applyFullSync", "clearAccount",
            ),
            calls.map(Call::name),
        )
        val mutationCommands = calls[0].arguments[1] as List<*>
        assertEquals("finish", (mutationCommands.single() as PendingCommandEntity).generatedByFinishCommandId)
        val bootstrapCommands = calls[1].arguments[1] as List<*>
        assertEquals("finish", (bootstrapCommands.single() as PendingCommandEntity).generatedByFinishCommandId)
        val resolutionCommands = calls[2].arguments[2] as List<*>
        assertEquals("finish", (resolutionCommands.single() as PendingCommandEntity).generatedByFinishCommandId)
        val discarded = calls[3].arguments[9] as List<*>
        assertEquals("discard-finish", (discarded.single() as PendingCommandEntity).generatedByFinishCommandId)
    }

    @Test
    fun loadWorkspaceRejectsMissingInitializationBeforeReadingQueues() {
        val (dao, calls) = proxy(mapOf("localState" to null))
        val store = TimerStore(dao, json, strict) {}

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { store.loadWorkspace() }
        }

        assertEquals("Local workspace is missing", error.message)
        assertEquals(listOf("localState"), calls.map(Call::name))
    }

    @Test
    fun loadWorkspaceKeepsNullableCanonicalAndAccountFieldsAbsent() = runBlocking {
        val values = queueValues(state(), PendingCommandEntity.from(command(), null)).toMutableMap()
        values["pendingCommands"] = emptyList<Any>()
        values["pendingCommandsCapped"] = emptyList<Any>()
        val store = TimerStore(proxy(values).first, json, strict) {}

        val loaded = store.loadWorkspace()

        assertNull(loaded.canonicalTimer)
        assertNull(loaded.bootstrapResolution)
        assertNull(loaded.user)
        assertEquals(emptyMap<String, String>(), loaded.commandDependencies)
    }

    private fun queueValues(
        local: LocalStateEntity,
        command: PendingCommandEntity,
    ): Map<String, Any?> {
        val task = PendingTaskOperationEntity.from(task())
        val duration = PendingDurationOperationEntity.from(duration())
        val autoStart = PendingAutoStartOperationEntity.from(autoStart())
        val selected = PendingSelectedTaskOperationEntity.from(selection())
        return mapOf(
            "localState" to local,
            "pendingCommands" to listOf(command),
            "pendingTaskOperations" to listOf(task),
            "pendingDurationOperations" to listOf(duration),
            "pendingAutoStartOperations" to listOf(autoStart),
            "pendingSelectedTaskOperations" to listOf(selected),
            "pendingCommandsCapped" to listOf(command),
            "pendingTaskOperationsCapped" to listOf(task),
            "pendingDurationOperationsCapped" to listOf(duration),
            "pendingAutoStartOperationsCapped" to listOf(autoStart),
            "pendingSelectedTaskOperationsCapped" to listOf(selected),
            "pendingBootstrapResolution" to null,
        )
    }

    private fun queues() = PendingSyncQueues(
        listOf(command()), listOf(task()), listOf(duration()), listOf(autoStart()), listOf(selection()),
    )

    private fun state() = LocalStateEntity(
        deviceId = "device", settingsJson = json.encodeToString(TimerSettings()),
    )

    private fun command() = TimerCommand(
        "command", 1, "timer", CommandType.Start, TimerPhase.Focus,
        60_000, At, 1, 0, 0,
    )

    private fun task() = TaskOperation(
        "task-op", "task", TaskOperationType.Upsert, "Task", At, 2, 0,
    )

    private fun duration() = DurationOperation("duration", TimerPhase.Focus, 60_000, At, 3, 0)

    private fun autoStart() = AutoStartOperation("auto", "device", true, At, 4, 0)

    private fun selection() = SelectedTaskOperation("selection", "task", At, 5, 0)

    private fun user() = User("account", "a@example.test", "A", "u")

    private fun resolution() = PendingBootstrapResolutionEntity(
        requestId = "resolution", deviceId = "device", expectedRevision = 0,
        strategy = "merge", commandsJson = "[]", taskOperationsJson = "[]",
        durationOperationsJson = "[]", ownerUserId = "account", userJson = "{}",
    )

    private fun proxy(values: Map<String, Any?>): Pair<CentralizedSyncDao, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val dao = Proxy.newProxyInstance(
            CentralizedSyncDao::class.java.classLoader,
            arrayOf(CentralizedSyncDao::class.java),
        ) { _, method, arguments ->
            val actual = arguments.orEmpty().dropLastWhile {
                it is kotlin.coroutines.Continuation<*>
            }
            calls += Call(method.name, actual)
            values[method.name]
        } as CentralizedSyncDao
        return dao to calls
    }

    private data class Call(val name: String, val arguments: List<Any?>)

    private companion object {
        const val At = "2026-01-01T00:00:00Z"
    }
}
