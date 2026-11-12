package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingTaskDeletionMarker.Companion.SignedOutWorkspaceOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX round 2: a pending task deletion is durable. After the user deletes a task, the task never
 * comes back and can never be acted on, unless the user presses Undo. The repository owns the
 * undo window and a marker in preferences; the next process replays the marker and fails closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingTaskDeletionDurableTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun deletedTaskHidesWithoutLeavingTheWorkspaceWhileTheWindowIsOpen() = runBlocking {
        val workspace = workspace()
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")

        repository.requestTaskDeletion(task.id)

        assertEquals(PendingTaskDeletion(task.id, "Write report"), repository.pendingTaskDeletion.value)
        assertEquals("nothing leaves the workspace before the window ends", listOf(task), repository.state.value.tasks)
        assertEquals(setOf(task.id), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    @Test
    fun killDuringTheWindowThenRestartKeepsTheTaskDeleted() = runBlocking {
        val workspace = workspace()
        val killed = workspace.startProcess()
        val task = killed.seedTask("Write report")
        val kept = killed.seedTask("Read mail")
        killed.requestTaskDeletion(task.id)

        val restarted = workspace.startProcess()

        assertEquals(listOf(kept), restarted.state.value.tasks)
        assertEquals("the replayed delete clears the marker", emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    @Test
    fun undoThenRestartKeepsTheTask() = runBlocking {
        val workspace = workspace()
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        first.requestTaskDeletion(task.id)

        first.undoTaskDeletion()

        assertNull(first.pendingTaskDeletion.value)
        assertEquals("Undo clears the marker", emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
        assertEquals(listOf(task), workspace.startProcess().state.value.tasks)
    }

    // Batch B rework: a marker of another owner is kept for that owner's next
    // sign-in, and it deletes nothing in this workspace.
    @Test
    fun markerOfAnotherOwnerWaitsForThatOwnerAndDeletesNothing() = runBlocking {
        val workspace = workspace()
        val task = workspace.startProcess().seedTask("Write report")
        workspace.writeMarker(task.id, "account-of-someone-else")

        val restarted = workspace.startProcess()

        assertEquals(listOf(task), restarted.state.value.tasks)
        assertEquals(setOf(task.id), workspace.markerTaskIds("account-of-someone-else"))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    @Test
    fun markerOfAMissingTaskIsDroppedAndDeletesNothing() = runBlocking {
        val workspace = workspace()
        val task = workspace.startProcess().seedTask("Write report")
        workspace.writeMarker("task-that-is-gone", SignedOutWorkspaceOwner)

        val restarted = workspace.startProcess()

        assertEquals(listOf(task), restarted.state.value.tasks)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    @Test
    fun startingTheTimerCompletesTheDeletionFirst() = runBlocking {
        val workspace = workspace()
        val repository = workspace.startProcess()
        val task = repository.seedSelectedTask("Write report")
        repository.requestTaskDeletion(task.id)

        repository.toggleTimer()

        assertEquals(emptyList<FocusTask>(), repository.state.value.tasks)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertNull("the deleted task is not the focus task", repository.state.value.timer?.taskId)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    // The notification's Skip next reaches selectPhase; the screen reaches the others.
    @Test
    fun changingTheTimerCompletesTheDeletionFirst() = runBlocking {
        val entryPoints = listOf<Pair<String, suspend TimerRepository.() -> Unit>>(
            "finish" to { finishTimer() },
            "cancel" to { cancelAndClearTimer() },
            "clear" to { clearTimer() },
            "phase" to { selectPhase(TimerPhase.ShortBreak) },
        )
        val deletedTaskLeft = entryPoints.map { (name, enter) ->
            val workspace = workspace()
            val repository = workspace.startProcess()
            val task = repository.seedSelectedTask("Write report")
            repository.requestTaskDeletion(task.id)
            repository.enter()
            name to (repository.state.value.tasks.any { it.id == task.id } || workspace.markerTaskIds(SignedOutWorkspaceOwner).isNotEmpty())
        }

        assertEquals(entryPoints.map { it.first to false }, deletedTaskLeft)
    }

    @Test
    fun selectingAddingAndRenamingCompleteTheDeletionFirst() = runBlocking {
        val entryPoints = listOf<Pair<String, suspend TimerRepository.(FocusTask) -> Unit>>(
            "select" to { deleted -> selectTask(deleted.id) },
            "add" to { addTask("Read mail") },
            "rename" to { deleted -> renameTask(deleted.id, "Write the report") },
        )
        val deletedTaskLeft = entryPoints.map { (name, enter) ->
            val workspace = workspace()
            val repository = workspace.startProcess()
            val task = repository.seedTask("Write report")
            repository.requestTaskDeletion(task.id)
            repository.enter(task)
            val state = repository.state.value
            val present = state.tasks.any { it.id == task.id || it.title == "Write the report" }
            Triple(name, present, state.selectedTaskId == task.id)
        }

        assertEquals(
            listOf(Triple("select", false, false), Triple("add", false, false), Triple("rename", false, false)),
            deletedTaskLeft,
        )
    }

    @Test
    fun signOutDuringTheWindowDeletesThroughTheNormalPathBeforeTheReset() = runBlocking {
        val workspace = workspace(User("account-1", "a1@example.test", "A", ""))
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)
        var queuedAtSignOut = emptyList<String>()
        var tasksAtSignOut = listOf(task)
        workspace.auth.onPrepareLogout = {
            queuedAtSignOut = workspace.queuedTaskOperationTypes()
            tasksAtSignOut = repository.state.value.tasks
        }

        repository.logout()

        assertEquals(emptyList<FocusTask>(), tasksAtSignOut)
        assertTrue("the delete is queued work: $queuedAtSignOut", TaskOperationType.Delete in queuedAtSignOut)
        assertNull(repository.pendingTaskDeletion.value)
        // Batch B rework: sign-out discards the queue, so A keeps its marker for its next sign-in.
        assertEquals(setOf(task.id), workspace.markerTaskIds("account-1"))
    }

    private fun workspace(account: User? = null) = PendingDeletionWorkspace(account).also(workspaces::add)
}

internal suspend fun TimerRepository.seedTask(title: String): FocusTask {
    check(addTask(title)) { "seed task was not saved" }
    return state.value.tasks.first { it.title == title }
}

internal suspend fun TimerRepository.seedSelectedTask(title: String): FocusTask {
    val task = seedTask(title)
    selectTask(task.id)
    check(state.value.selectedTaskId == task.id) { "seed task was not selected" }
    return task
}
