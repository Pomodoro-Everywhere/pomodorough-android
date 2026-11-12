package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingDeletionWorkspace.CredentialProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX round 2, batch B rework2: a task is never deleted silently later. When the owner creates a
 * task whose identity matches a kept delete marker, the create supersedes the old delete, and no
 * later replay removes the task. Identity is the title hash, so the same title is the same task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingTaskDeletionSupersedeTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    // The takeover request fails offline, so the server copy never has the task.
    @Test
    fun recreatingTheTitleAfterSignOutSurvivesTheNextSignIn() = runBlocking {
        val workspace = workspace(AccountA)
        val task = deleteThenSignOut(workspace)
        workspace.service.resolutionOffline = true
        val restarted = recreateSignedOut(workspace, task)

        restarted.signIn(CredentialProvider)

        val takeover = workspace.service.resolutionRequests.single()
        assertFalse(
            "the takeover carries no delete: ${takeover.taskOperations}",
            takeover.taskOperations.any { it.taskId == task.id && it.type == TaskOperationType.Delete },
        )
        assertEquals(listOf(task.id), restarted.state.value.tasks.map(FocusTask::id))
        assertEquals("the create superseded A's delete", emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(listOf(task.id), workspace.startProcess().state.value.tasks.map(FocusTask::id))
    }

    // The server copy already holds the task again, so the sign-in replay finds it present.
    @Test
    fun recreatingTheTitleSurvivesWhenTheServerCopyHasItAgain() = runBlocking {
        val workspace = workspace(AccountA)
        val task = deleteThenSignOut(workspace)
        val restarted = recreateSignedOut(workspace, task)

        restarted.signIn(CredentialProvider)

        val state = restarted.state.value
        assertEquals(state.notice, AuthStatus.SignedIn, state.authStatus)
        assertNull("the takeover finished: ${state.historyResolution?.error}", state.historyResolution)
        assertEquals(listOf(task.id), state.tasks.map(FocusTask::id))
        assertNull("the owner cleared the selection before the takeover", state.selectedTaskId)
        assertEquals("the server keeps the task", listOf(task.id), workspace.service.tasks[AccountA.id]?.map(FocusTask::id))
        assertNull("the server applied the cleared selection", workspace.service.selectedTaskIds[AccountA.id])
        assertFalse(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(listOf(task.id), workspace.startProcess().state.value.tasks.map(FocusTask::id))
    }

    // The undo window is open, so the delete is still in memory and no leftover marker waits. The
    // owner adds the same title: the delete completes first, and the add creates the task again.
    @Test
    fun addingTheTitleDuringTheUndoWindowCompletesTheDeleteThenCreatesTheTask() = runBlocking {
        val workspace = workspace()
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)

        assertTrue(repository.addTask("Write report"))

        assertNull(repository.pendingTaskDeletion.value)
        assertEquals(listOf(task.id), repository.state.value.tasks.map(FocusTask::id))
        assertEquals(task.id, repository.state.value.selectedTaskId)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(PendingTaskDeletionMarker.SignedOutWorkspaceOwner))
        val queued = workspace.queuedTaskOperationTypes()
        assertTrue("the delete precedes the create: $queued", TaskOperationType.Delete in queued)
        assertEquals("the create is the last word: $queued", TaskOperationType.Upsert, queued.last())
        assertEquals(listOf(task.id), workspace.startProcess().state.value.tasks.map(FocusTask::id))
    }

    // A kill after the save and before the marker left: the next process finds the intent and the
    // task, so the marker leaves and the task stays.
    @Test
    fun killAfterTheSaveOfAMarkedTaskKeepsTheTask() = runBlocking {
        val workspace = workspace()
        val task = workspace.startProcess().seedTask("Write report")
        val marker = PendingTaskDeletionMarker(task.id, PendingTaskDeletionMarker.SignedOutWorkspaceOwner)
        workspace.markers.write(marker)
        assertTrue(workspace.markers.beginSupersede(marker))

        val restarted = workspace.startProcess()

        assertEquals(listOf(task), restarted.state.value.tasks)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(marker.workspaceOwner))
        assertEquals(emptySet<String>(), workspace.markers.superseding(marker.workspaceOwner))
    }

    // A kill after the intent and before the save: the task is absent, so only the intent leaves.
    // An account's absent task keeps its marker until the server copy decides.
    @Test
    fun killBeforeTheSaveOfAMarkedTaskKeepsTheMarker() = runBlocking {
        val workspace = workspace(AccountA)
        workspace.startProcess()
        val marker = PendingTaskDeletionMarker("task-never-saved", AccountA.id)
        workspace.markers.write(marker)
        assertTrue(workspace.markers.beginSupersede(marker))

        workspace.startProcess()

        assertEquals(setOf(marker.taskId), workspace.markerTaskIds(AccountA.id))
        assertEquals(emptySet<String>(), workspace.markers.superseding(AccountA.id))
    }

    // An ordinary save of a task without a marker writes no intent.
    @Test
    fun saveWithoutAMarkerWritesNoIntent() = runBlocking {
        val workspace = workspace()
        val marker = PendingTaskDeletionMarker("task-1", PendingTaskDeletionMarker.SignedOutWorkspaceOwner)

        assertFalse(workspace.markers.beginSupersede(marker))
        assertEquals(emptySet<String>(), workspace.markers.superseding(marker.workspaceOwner))
    }

    // A, signed in with the task on the server, deletes it and signs out: the queue goes with the
    // account data, so A keeps its marker. The process then dies.
    private suspend fun deleteThenSignOut(workspace: PendingDeletionWorkspace): FocusTask {
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        first.requestTaskDeletion(task.id)
        first.logout()
        assertEquals(setOf(task.id), workspace.markerTaskIds(AccountA.id))
        return task
    }

    // The next process starts signed out, and A creates the same title there.
    private suspend fun recreateSignedOut(workspace: PendingDeletionWorkspace, task: FocusTask): TimerRepository {
        val restarted = workspace.startProcess()
        assertEquals(AuthStatus.SignedOut, restarted.state.value.authStatus)
        assertEquals(emptyList<FocusTask>(), restarted.state.value.tasks)
        assertTrue(restarted.addTask(task.title))
        assertEquals(listOf(task.id), restarted.state.value.tasks.map(FocusTask::id))
        // A selected task makes the takeover preparation fail ("invalid base selected task
        // identity"); that is a separate finding, so these tests cover the marker only.
        restarted.selectTask(null)
        workspace.service.account = AccountA
        return restarted
    }

    private fun workspace(account: User? = null) = PendingDeletionWorkspace(account).also(workspaces::add)

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
    }
}
