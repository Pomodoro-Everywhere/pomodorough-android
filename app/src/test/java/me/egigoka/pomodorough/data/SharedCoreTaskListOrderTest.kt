package me.egigoka.pomodorough.data

import android.app.Application
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.core.SharedCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PWA-16: the task list order is a Shared Core decision (pomodorough-core TASK_LIST_ORDER.md).
 * The repository shows the task list in the order that the Core projection returns, whatever rule
 * produced that order. It has no task order of its own, so a new Core order needs no app change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedCoreTaskListOrderTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    // Not in order under the UTF-8 byte rule, and not under the natural case-folded rule.
    private val titles = listOf("Bulk task 10", "apple", "Bulk task 2", "Banana")

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun shownTaskListIsTheBundledSharedCoreOrder() = runBlocking {
        val workspace = workspace()
        val repository = workspace.startProcess()
        val added = titles.map { repository.seedTask(it) }
        val coreOrder = bundledCoreOrder(workspace, added)

        assertEquals(added.toSet(), coreOrder.toSet())
        assertNotEquals("Core must decide the order, not the order of the adds", added, coreOrder)
        assertShownOrder(coreOrder, repository)
        assertShownOrder(coreOrder, workspace.startProcess())
    }

    @Test
    fun shownTaskListKeepsACoreOrderThatNoLocalRuleProduces() = runBlocking {
        val workspace = workspace()
        workspace.projectionOutputRewrite = ::withReversedTasks
        val repository = workspace.startProcess()
        val added = titles.map { repository.seedTask(it) }
        val returnedOrder = bundledCoreOrder(workspace, added).reversed()

        assertShownOrder(returnedOrder, repository)
        assertShownOrder(returnedOrder, workspace.startProcess())
    }

    private fun assertShownOrder(expected: List<FocusTask>, repository: TimerRepository) {
        val state = repository.state.value
        assertEquals(expected.map(FocusTask::title), state.tasks.map(FocusTask::title))
        assertEquals(expected, state.tasks)
        assertEquals("task summaries follow the task list", expected, state.taskSummaries.map(TaskDailySummary::task))
    }

    /** The order in which the bundled Core lists [tasks] in a projection output. */
    private fun bundledCoreOrder(workspace: PendingDeletionWorkspace, tasks: List<FocusTask>): List<FocusTask> {
        val core = SharedCore.fromAssets(workspace.context.assets)
        return CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }
            .apply(CoreProjectionBase(tasks = tasks), CoreProjectionPending(), Instant.parse("2026-01-01T00:00:00Z"))
            .tasks
    }

    private fun withReversedTasks(output: JsonElement): JsonElement {
        val tasks = output.jsonObject.getValue("tasks").jsonArray
        return JsonObject(output.jsonObject + ("tasks" to JsonArray(tasks.reversed())))
    }

    private fun workspace() = PendingDeletionWorkspace().also(workspaces::add)
}
