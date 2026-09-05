package me.egigoka.pomodorough.unit.positive

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerLocalInitializer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.BootstrapDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.TimerWorkspaceDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PersistenceBoundaryPositiveUnitTest {
    private val json = Json { explicitNulls = false }
    private val strictJson = Json(json) { ignoreUnknownKeys = false }

    @Test
    fun everyPendingOperationRoundTripsWithoutWireFieldLoss() {
        val command = command()
        val task = TaskOperation("task-op", "task", TaskOperationType.Upsert, "Title", At, 12, 1)
        val duration = DurationOperation("duration-op", TimerPhase.LongBreak, 900_000, At, 13, 2)
        val autoStart = AutoStartOperation("auto-op", "device", true, At, 14, 3)
        val selected = SelectedTaskOperation("selected-op", null, At, 15, 4)

        assertEquals(command, PendingCommandEntity.from(command, "finish-op").toModel())
        assertEquals(task, PendingTaskOperationEntity.from(task).toModel())
        assertEquals(duration, PendingDurationOperationEntity.from(duration).toModel())
        assertEquals(autoStart, PendingAutoStartOperationEntity.from(autoStart).toModel())
        assertEquals(selected, PendingSelectedTaskOperationEntity.from(selected).toModel())
        assertEquals("finish-op", PendingCommandEntity.from(command, "finish-op").generatedByFinishCommandId)
    }

    @Test
    fun initializerLoadsEveryQueueAndPreservesGeneratedCommandDependency() = runBlocking {
        val local = localState(user = user())
        val command = PendingCommandEntity.from(command(), "finish-op")
        val task = PendingTaskOperationEntity.from(
            TaskOperation("task-op", "task", TaskOperationType.Upsert, "Title", At, 12, 1),
        )
        val duration = PendingDurationOperationEntity.from(
            DurationOperation("duration-op", TimerPhase.Focus, 1_500_000, At, 13, 2),
        )
        val auto = PendingAutoStartOperationEntity.from(
            AutoStartOperation("auto-op", "device", true, At, 14, 3),
        )
        val selected = PendingSelectedTaskOperationEntity.from(
            SelectedTaskOperation("selected-op", "task", At, 15, 4),
        )
        val resolution = resolution()
        val values = mutableMapOf<String, Any?>(
            "localState" to local,
            "pendingCommands" to listOf(command),
            "pendingTaskOperations" to listOf(task),
            "pendingDurationOperations" to listOf(duration),
            "pendingAutoStartOperations" to listOf(auto),
            "pendingSelectedTaskOperations" to listOf(selected),
            "pendingCommandsCapped" to listOf(command),
            "pendingTaskOperationsCapped" to listOf(task),
            "pendingDurationOperationsCapped" to listOf(duration),
            "pendingAutoStartOperationsCapped" to listOf(auto),
            "pendingSelectedTaskOperationsCapped" to listOf(selected),
            "pendingBootstrapResolution" to resolution,
        )
        var validated: User? = null

        val loaded = initializer(values) { validated = it }.load()

        assertSame(local, loaded.storedLocal)
        assertSame(local, loaded.local)
        assertEquals(listOf(command.toModel()), loaded.commands)
        assertEquals(mapOf(command.id to "finish-op"), loaded.commandDependencies)
        assertEquals(listOf(task.toModel()), loaded.taskOperations)
        assertEquals(listOf(duration.toModel()), loaded.durationOperations)
        assertEquals(listOf(auto.toModel()), loaded.autoStartOperations)
        assertEquals(listOf(selected.toModel()), loaded.selectedTaskOperations)
        assertSame(resolution, loaded.bootstrapResolution)
        assertEquals(user(), loaded.decoded.user)
        assertEquals(user(), validated)
    }

    @Test
    fun initializerCreatesAndPersistsDefaultStateBeforePublishingIt() = runBlocking {
        val values = emptyWorkspaceValues().toMutableMap()
        var inserted: LocalStateEntity? = null
        values["insertState"] = { arguments: Array<out Any?> ->
            inserted = arguments[0] as LocalStateEntity
            Unit
        }

        val loaded = initializer(values) {}.load()

        assertNull(loaded.storedLocal)
        assertSame(inserted, loaded.local)
        assertNotNull(java.util.UUID.fromString(loaded.local.deviceId))
        assertEquals(TimerSettings(), loaded.decoded.settings)
        assertEquals(emptyList<Any>(), loaded.decoded.history)
        assertEquals(emptyList<Any>(), loaded.decoded.tasks)
        assertNull(loaded.decoded.user)
    }

    @Test
    fun initializerDecodesCanonicalTimerHistoryTasksAndKnownTasksTogether() = runBlocking {
        val settings = TimerSettings(selectedPhase = TimerPhase.ShortBreak)
        val history = listOf(me.egigoka.pomodorough.data.HistoryItem(
            id = "history", timerId = "timer", phase = TimerPhase.Focus,
            status = "completed", plannedDurationMs = 60_000,
        ))
        val tasks = listOf(FocusTask("task", "Current"))
        val known = listOf(FocusTask("known", "Known"))
        val timer = me.egigoka.pomodorough.data.CanonicalTimer(
            id = "timer", phase = TimerPhase.Focus, status = "running",
            plannedDurationMs = 60_000, elapsedAtAnchorMs = 1, anchorAt = At,
        )
        val local = LocalStateEntity(
            deviceId = "device",
            settingsJson = json.encodeToString(settings),
            canonicalTimerJson = json.encodeToString(timer),
            historyJson = json.encodeToString(history),
            tasksJson = json.encodeToString(tasks),
            knownTasksJson = json.encodeToString(known),
        )
        val values = emptyWorkspaceValues().toMutableMap().apply { put("localState", local) }

        val decoded = initializer(values) {}.load().decoded

        assertEquals(settings, decoded.settings)
        assertEquals(timer, decoded.canonicalTimer)
        assertEquals(history, decoded.history)
        assertEquals(tasks, decoded.tasks)
        assertEquals(known, decoded.knownTasks)
    }

    private fun initializer(
        values: Map<String, Any?>,
        validate: (User) -> Unit,
    ) = TimerLocalInitializer(
        workspace = proxy(TimerWorkspaceDao::class.java, values),
        bootstrap = proxy(BootstrapDao::class.java, values),
        json = json,
        strictJson = strictJson,
        validateUser = validate,
    )

    private fun localState(user: User? = null) = LocalStateEntity(
        deviceId = "device",
        settingsJson = json.encodeToString(TimerSettings()),
        userJson = user?.let(json::encodeToString),
    )

    private fun command() = TimerCommand(
        "command", 7, "timer", CommandType.Start, TimerPhase.Focus,
        1_500_000, At, 11, 0, 42, "task", "physical",
    )

    private fun user() = User("account", "owner@example.test", "Owner", "https://example.test/a")

    private fun resolution() = PendingBootstrapResolutionEntity(
        requestId = "request", deviceId = "device", expectedRevision = 3,
        strategy = "merge", commandsJson = "[]", taskOperationsJson = "[]",
        durationOperationsJson = "[]", ownerUserId = "account", userJson = "{}",
    )

    private fun emptyWorkspaceValues(): Map<String, Any?> = mapOf(
        "localState" to null,
        "pendingCommands" to emptyList<Any>(),
        "pendingTaskOperations" to emptyList<Any>(),
        "pendingDurationOperations" to emptyList<Any>(),
        "pendingAutoStartOperations" to emptyList<Any>(),
        "pendingSelectedTaskOperations" to emptyList<Any>(),
        "pendingCommandsCapped" to emptyList<Any>(),
        "pendingTaskOperationsCapped" to emptyList<Any>(),
        "pendingDurationOperationsCapped" to emptyList<Any>(),
        "pendingAutoStartOperationsCapped" to emptyList<Any>(),
        "pendingSelectedTaskOperationsCapped" to emptyList<Any>(),
        "pendingBootstrapResolution" to null,
    )

    private companion object {
        const val At = "2026-01-01T00:00:00Z"

        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, values: Map<String, Any?>): T = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, arguments ->
            val value = values[method.name]
            if (value is Function1<*, *>) {
                (value as (Array<out Any?>) -> Any?)(arguments.orEmpty())
            } else {
                value
            }
        } as T
    }
}
