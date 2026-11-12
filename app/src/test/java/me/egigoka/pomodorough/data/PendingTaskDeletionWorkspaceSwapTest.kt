package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingDeletionWorkspace.CredentialProvider
import me.egigoka.pomodorough.data.PendingTaskDeletionMarker.Companion.SignedOutWorkspaceOwner
import me.egigoka.pomodorough.data.iroh.IrohConflictEvidence
import me.egigoka.pomodorough.data.iroh.IrohDomain
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
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
 * UX round 2, S1 and S1b: the undo window belongs to the workspace it was opened in. Home and a
 * room hold the same title, so they hold the same task identity. Every workspace swap settles the
 * window first, in the old workspace. The task is never deleted in the other workspace, and it
 * never comes back silently: a refused delete shows it again with a notice before the swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingTaskDeletionWorkspaceSwapTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun enteringARoomQueuesTheHomeDeleteAtHome() = runBlocking {
        val (workspace, route) = workspaceWithRoute(ReplicationMode.CENTRALIZED)
        val repository = workspace.startProcess(route)
        val task = seedTaskInBothWorkspaces(repository, route)
        repository.requestTaskDeletion(task.id)

        repository.createIrohRoom("Design desk")

        assertNull("the window settled before the swap", repository.pendingTaskDeletion.value)
        repository.completePendingTaskDeletion()
        assertKeptOnScreen(workspace, repository, task)
        assertTrue("the delete is queued at home", route.away.queuesDelete(task))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
        repository.leaveIrohRoom()
        assertFalse("home shows its delete", repository.state.value.tasks.any { it.id == task.id })
    }

    @Test
    fun leavingARoomQueuesTheRoomDeleteInTheRoom() = runBlocking {
        val (workspace, route) = workspaceWithRoute(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        val task = seedTaskInBothWorkspaces(repository, route)
        repository.requestTaskDeletion(task.id)

        repository.leaveIrohRoom()

        assertNull("the window settled before the swap", repository.pendingTaskDeletion.value)
        repository.completePendingTaskDeletion()
        assertKeptOnScreen(workspace, repository, task)
        assertTrue("the delete is queued in the room", route.away.queuesDelete(task))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
        repository.createIrohRoom("Design desk")
        assertFalse("the room shows its delete", repository.state.value.tasks.any { it.id == task.id })
    }

    // The forced setMode of prepareSignIn swaps the room for home before the account takes it over.
    @Test
    fun signingInFromARoomQueuesTheDeleteInTheRoom() = runBlocking {
        val (workspace, route) = workspaceWithRoute(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        val task = seedTaskInBothWorkspaces(repository, route)
        repository.requestTaskDeletion(task.id)
        workspace.service.account = AccountA
        workspace.service.resolutionOffline = true

        repository.signIn(CredentialProvider)

        val takeover = workspace.service.resolutionRequests.single()
        assertFalse(
            "the takeover carries no delete: ${takeover.taskOperations}",
            takeover.taskOperations.any { it.taskId == task.id && it.type == TaskOperationType.Delete },
        )
        assertKeptOnScreen(workspace, repository, task)
        assertTrue("the delete is queued in the room", route.away.queuesDelete(task))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // A room conflict blocks the room's mutations, so the delete is refused before the swap.
    @Test
    fun refusedDeleteBeforeTheSwapShowsTheTaskAgainAndDeletesNothing() = runBlocking {
        val (workspace, route) = workspaceWithRoute(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        val task = seedTaskInBothWorkspaces(repository, route)
        repository.requestTaskDeletion(task.id)
        route.reportConflict(RoomConflict)
        awaitDeletionCondition("conflict published") { repository.state.value.network.conflict != null }

        repository.leaveIrohRoom()

        assertNull("the window settled before the swap", repository.pendingTaskDeletion.value)
        assertEquals("Could not delete “Write report”. The task is back in your list.", repository.state.value.notice)
        repository.completePendingTaskDeletion()
        assertKeptOnScreen(workspace, repository, task)
        assertFalse("the room never queued the refused delete", route.away.queuesDelete(task))
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
        repository.createIrohRoom("Design desk")
        assertTrue("the room shows the task again", repository.state.value.tasks.any { it.id == task.id })
    }

    // Identity is the title hash, so the copy holds the very same task in the other workspace.
    private suspend fun seedTaskInBothWorkspaces(
        repository: TimerRepository,
        route: WorkspaceSwapReplication,
    ): FocusTask {
        val task = repository.seedTask("Write report")
        // A selected task makes the takeover preparation fail ("invalid base selected task
        // identity"); that is a separate finding, so these tests leave the task unselected.
        repository.selectTask(null)
        route.copyCurrentToAway()
        return task
    }

    private suspend fun assertKeptOnScreen(
        workspace: PendingDeletionWorkspace,
        repository: TimerRepository,
        task: FocusTask,
    ) {
        val shown = repository.state.value.tasks
        assertTrue("the other workspace keeps its task: $shown", shown.any { it.id == task.id })
        val queued = workspace.queuedTaskOperationTypes()
        assertFalse("the other workspace never deletes it: $queued", TaskOperationType.Delete in queued)
    }

    private fun LocalWorkspaceSnapshot?.queuesDelete(task: FocusTask): Boolean =
        this?.taskOperations.orEmpty().any { it.taskId == task.id && it.type == TaskOperationType.Delete }

    private fun workspaceWithRoute(initial: ReplicationMode): Pair<PendingDeletionWorkspace, WorkspaceSwapReplication> {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        return workspace to workspace.workspaceSwap(initial)
    }

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
        val RoomConflict = IrohConflictEvidence(IrohDomain.task, "operation-1", "local", "received", 1L)
    }
}
