package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingDeletionWorkspace.CredentialProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX round 2, leftover replay: an owner that leaves the account (logout, account switch or local
 * reset) keeps its delete marker, and its next sign-in replays it against the server copy. That
 * can be long after the delete, so each outcome is visible: a finished delete names the task, a
 * refused one shows the task again with a notice, and a task that is already gone ends the marker
 * without a notice. Reset runs only while mutations are blocked, so it cannot release the marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingTaskDeletionLeftoverReplayTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun committedReplayAfterTheResetNamesTheDeletedTask() = runBlocking {
        val (workspace, task) = markerThatOutlivesTheReset(onServer = true)

        val repository = signInAgain(workspace)

        assertFalse(repository.state.value.tasks.any { it.id == task.id })
        assertTrue(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(finishedLaterNotice(task), repository.state.value.notice)
    }

    @Test
    fun committedReplayAfterTheLogoutNamesTheDeletedTask() = runBlocking {
        val workspace = workspace()
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        first.requestTaskDeletion(task.id)
        first.logout()
        assertEquals(setOf(task.id), workspace.markerTaskIds(AccountA.id))

        val repository = signInAgain(workspace)

        assertFalse(repository.state.value.tasks.any { it.id == task.id })
        assertTrue(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(finishedLaterNotice(task), repository.state.value.notice)
    }

    // Two markers finish in one replay. The notice holds one message, so it counts both deletes;
    // neither ends silently. The second marker stands for one left by an earlier session.
    @Test
    fun committedReplayOfTwoMarkersCountsBothDeletedTasks() = runBlocking {
        val workspace = workspace()
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        val earlier = first.seedTask("Read mail")
        workspace.service.tasks[AccountA.id] = listOf(task, earlier)
        first.requestTaskDeletion(task.id)
        first.logout()
        workspace.writeMarker(earlier.id, AccountA.id)
        assertEquals(setOf(task.id, earlier.id), workspace.markerTaskIds(AccountA.id))

        val repository = signInAgain(workspace)

        assertEquals(emptyList<FocusTask>(), repository.state.value.tasks.filter { it.id in setOf(task.id, earlier.id) })
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(
            "Finished deleting 2 tasks you deleted on this device earlier.",
            repository.state.value.notice,
        )
    }

    @Test
    fun committedReplayAfterTheAccountSwitchNamesTheDeletedTask() = runBlocking {
        val workspace = workspace()
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        first.requestTaskDeletion(task.id)
        workspace.service.account = AccountB
        first.signIn(CredentialProvider)
        first.confirmAccountSwitch()
        assertEquals(AccountB, first.state.value.user)
        assertEquals("B's session keeps A's marker", setOf(task.id), workspace.markerTaskIds(AccountA.id))

        val second = workspace.startProcess()
        workspace.service.account = AccountA
        second.signIn(CredentialProvider)
        second.confirmAccountSwitch()

        assertEquals(AccountA, second.state.value.user)
        assertFalse(second.state.value.tasks.any { it.id == task.id })
        assertTrue(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(finishedLaterNotice(task), second.state.value.notice)
    }

    @Test
    fun refusedReplayAfterTheResetShowsTheTaskWithTheNotice() = runBlocking {
        val (workspace, task) = markerThatOutlivesTheReset(onServer = true)
        workspace.refusedDeleteTaskIds = setOf(task.id)

        val repository = signInAgain(workspace)

        assertTrue(repository.state.value.tasks.any { it.id == task.id })
        assertFalse(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals(refusedNotice(task), repository.state.value.notice)
    }

    // One replay refuses one marker and finishes another. The notice holds one message, so the
    // refused sentence and the finished sentence share it, one per line; neither replaces the other.
    @Test
    fun refusedAndCommittedMarkersInOneReplayBothReachTheNotice() = runBlocking {
        val workspace = workspace()
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        val refused = first.seedTask("Read mail")
        workspace.service.tasks[AccountA.id] = listOf(task, refused)
        first.requestTaskDeletion(task.id)
        first.logout()
        workspace.writeMarker(refused.id, AccountA.id)
        workspace.refusedDeleteTaskIds = setOf(refused.id)

        val repository = signInAgain(workspace)

        val shown = repository.state.value.tasks.map(FocusTask::id)
        assertFalse("the finished delete removed the task: $shown", task.id in shown)
        assertTrue("the refused task is back: $shown", refused.id in shown)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        assertEquals("${refusedNotice(refused)}\n${finishedLaterNotice(task)}", repository.state.value.notice)
    }

    @Test
    fun alreadyGoneReplayAfterTheResetEndsTheMarkerQuietly() = runBlocking {
        val (workspace, task) = markerThatOutlivesTheReset(onServer = false)

        val repository = signInAgain(workspace)

        assertFalse(repository.state.value.tasks.any { it.id == task.id })
        assertFalse(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        val notice = repository.state.value.notice
        assertNotEquals(refusedNotice(task), notice)
        assertNotEquals(finishedLaterNotice(task), notice)
    }

    /**
     * A signed-in owner opens the window, and the process dies with the marker on disk. The next
     * process cannot read the credential store, which blocks mutations and requires the reset.
     */
    private suspend fun markerThatOutlivesTheReset(onServer: Boolean): Pair<PendingDeletionWorkspace, FocusTask> {
        val workspace = workspace().also { it.service.account = AccountA }
        val killed = workspace.startProcess()
        assertEquals(AuthStatus.SignedIn, killed.state.value.authStatus)
        val task = killed.seedTask("Write report")
        if (onServer) workspace.service.tasks[AccountA.id] = listOf(task)
        killed.requestTaskDeletion(task.id)
        workspace.auth.unreadable = true

        val blocked = workspace.startProcess()
        blocked.resetLocalAccount()

        assertEquals(AuthStatus.SignedOut, blocked.state.value.authStatus)
        assertEquals("the reset keeps A's marker", setOf(task.id), workspace.markerTaskIds(AccountA.id))
        return workspace to task
    }

    private suspend fun signInAgain(workspace: PendingDeletionWorkspace): TimerRepository {
        val repository = workspace.startProcess()
        workspace.service.account = AccountA
        repository.signIn(CredentialProvider)
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        return repository
    }

    private fun workspace() = PendingDeletionWorkspace(AccountA).also(workspaces::add)

    private fun finishedLaterNotice(task: FocusTask) =
        "Finished deleting “${task.title}”, which you deleted on this device earlier."

    private fun refusedNotice(task: FocusTask) = "Could not delete “${task.title}”. The task is back in your list."

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
        val AccountB = User("account-b", "b@example.test", "B", "")
    }
}
