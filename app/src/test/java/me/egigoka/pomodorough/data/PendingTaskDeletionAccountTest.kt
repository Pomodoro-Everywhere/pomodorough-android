package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingDeletionWorkspace.CredentialProvider
import me.egigoka.pomodorough.data.PendingTaskDeletionMarker.Companion.SignedOutWorkspaceOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UX round 2, batch B rework: one rule for every account path. A deleted task never comes back
 * silently, and it is never deleted silently later. Session expiry and an account switch queue
 * the delete in the old owner's durable queue first; an owner that leaves keeps its marker until
 * it signs in again; a refused completion shows the task again with a notice and forgets it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingTaskDeletionAccountTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    // F2: the 401 of a restore reaches handleAuthenticationRequired while the window is open.
    @Test
    fun sessionExpiryDuringTheWindowQueuesTheDeleteBeforeTheReset() = runBlocking {
        val workspace = workspace(AccountA)
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)
        workspace.service.profileExpired = true

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertDeletedInQueue(workspace, repository, task)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // F2: the 401 of a sync reaches expireSyncAuthentication while the window is open.
    @Test
    fun syncExpiryDuringTheWindowQueuesTheDeleteBeforeTheReset() = runBlocking {
        val workspace = workspace(AccountA, online = true).also { it.service.account = AccountA }
        val repository = workspace.startProcess()
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)
        workspace.service.syncExpired = true

        repository.refresh()

        awaitDeletionCondition("sync expiry") { repository.state.value.authStatus == AuthStatus.SignedOut }
        assertDeletedInQueue(workspace, repository, task)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // F1, accepted enqueue: the delete reaches A's Room queue before the switch prompt opens.
    @Test
    fun signingInAsAnotherAccountQueuesTheDeleteInTheOldOwnersQueue() = runBlocking {
        val workspace = workspace(AccountA)
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)
        workspace.service.account = AccountB

        repository.signIn(CredentialProvider)

        assertNotNull("the switch prompt is open", repository.state.value.accountSwitch)
        assertDeletedInQueue(workspace, repository, task)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals("confirm discards this queue, so A keeps its marker", setOf(task.id), workspace.markerTaskIds(AccountA.id))
    }

    // F1, refused or discarded enqueue: A's marker survives B's session and replays for A.
    @Test
    fun oldOwnersMarkerSurvivesTheOtherSessionAndReplaysWhenItSignsInAgain() = runBlocking {
        val workspace = workspace(AccountA)
        val first = workspace.startProcess()
        val task = first.seedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        first.requestTaskDeletion(task.id)
        workspace.service.account = AccountB
        first.signIn(CredentialProvider)
        first.confirmAccountSwitch()
        assertEquals(AccountB, first.state.value.user)

        val second = workspace.startProcess()

        assertEquals(AuthStatus.SignedIn, second.state.value.authStatus)
        assertEquals("B's session keeps A's marker", setOf(task.id), workspace.markerTaskIds(AccountA.id))
        workspace.service.account = AccountA
        second.signIn(CredentialProvider)
        second.confirmAccountSwitch()
        assertEquals(AccountA, second.state.value.user)
        assertDeletedInQueue(workspace, second, task)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // F3: Start during a sign-in refuses the delete. The task comes back with a notice, and no
    // later replay deletes it, even after a timer ran on it and the process restarted.
    @Test
    fun refusedCompletionShowsTheTaskAgainAndNeverDeletesItLater() = runBlocking {
        val workspace = workspace(AccountA)
        val repository = workspace.startProcess()
        val task = repository.seedSelectedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        workspace.service.account = AccountA
        repository.requestTaskDeletion(task.id)
        val gate = CompletableDeferred<Unit>().also { workspace.auth.signInGate = it }
        val signingIn = launch { repository.signIn(CredentialProvider) }
        awaitDeletionCondition("sign-in started") { repository.state.value.authStatus == AuthStatus.SigningIn }

        repository.toggleTimer()

        val refused = repository.state.value
        assertEquals(listOf(task), refused.tasks)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals("Could not delete “Write report”. The task is back in your list.", refused.notice)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
        gate.complete(Unit)
        signingIn.join()
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        repository.selectTask(task.id)
        repository.toggleTimer()
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertEquals(task.id, repository.state.value.timer?.taskId)
        repository.initialize()
        assertEquals("a replay in this process keeps the task", listOf(task), repository.state.value.tasks)
        assertEquals(task.id, repository.state.value.timer?.taskId)
        // This fixture does not restore a running timer in the next process, with or without a
        // delete, so the restart checks the task and the queue only.
        val restarted = workspace.startProcess()
        assertEquals(listOf(task), restarted.state.value.tasks)
        assertFalse(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
    }

    // Rule 7: sign-out during the window, then sign in again; the server still holds the task.
    @Test
    fun signOutDuringTheWindowThenSignInKeepsTheTaskDeleted() = runBlocking {
        val workspace = workspace(AccountA)
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        workspace.service.tasks[AccountA.id] = listOf(task)
        repository.requestTaskDeletion(task.id)

        repository.logout()
        workspace.service.account = AccountA
        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertDeletedInQueue(workspace, repository, task)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // L1: a kill during the window of a signed-in owner.
    @Test
    fun killDuringTheWindowOfASignedInOwnerKeepsTheTaskDeleted() = runBlocking {
        val workspace = workspace(AccountA).also { it.service.account = AccountA }
        val killed = workspace.startProcess()
        assertEquals(AuthStatus.SignedIn, killed.state.value.authStatus)
        val task = killed.seedTask("Write report")
        val kept = killed.seedTask("Read mail")
        killed.requestTaskDeletion(task.id)

        val restarted = workspace.startProcess()

        assertEquals(AuthStatus.SignedIn, restarted.state.value.authStatus)
        assertEquals(listOf(kept), restarted.state.value.tasks)
        assertTrue(TaskOperationType.Delete in workspace.queuedTaskOperationTypes())
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // F4 mutation site 1: reset ends the window before it resets the workspace.
    @Test
    fun resetEndsTheWindowAndTheTaskStaysDeleted() = runBlocking {
        val workspace = workspace()
        val task = workspace.startProcess().seedTask("Write report")
        workspace.auth.active = true
        workspace.auth.failingClears = 1
        val repository = workspace.startProcess()
        repository.requestTaskDeletion(task.id)

        repository.resetLocalAccount()

        assertNull(repository.pendingTaskDeletion.value)
        assertFalse(repository.state.value.tasks.any { it.id == task.id })
    }

    // F4 mutation site 2: account deletion queues the delete before the account goes.
    @Test
    fun deletingTheAccountQueuesTheDeleteFirst() = runBlocking {
        val workspace = workspace(AccountA).also { it.service.account = AccountA }
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        repository.requestTaskDeletion(task.id)
        var queuedAtDeletion = emptyList<String>()
        workspace.auth.onDeleteAccount = { queuedAtDeletion = workspace.queuedTaskOperationTypes() }

        repository.deleteAccount("DELETE")

        assertTrue("the delete is queued work: $queuedAtDeletion", TaskOperationType.Delete in queuedAtDeletion)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals(emptySet<String>(), workspace.markerTaskIds(AccountA.id))
    }

    // F4 mutation site 3: confirm ends a window opened while the prompt was open; A keeps its marker.
    @Test
    fun confirmingTheSwitchEndsAWindowOpenedDuringThePrompt() = runBlocking {
        val workspace = workspace(AccountA)
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        workspace.service.account = AccountB
        repository.signIn(CredentialProvider)
        assertNotNull(repository.state.value.accountSwitch)
        repository.requestTaskDeletion(task.id)

        repository.confirmAccountSwitch()

        assertEquals(AccountB, repository.state.value.user)
        assertNull(repository.pendingTaskDeletion.value)
        assertEquals("A's marker waits for A", setOf(task.id), workspace.markerTaskIds(AccountA.id))
    }

    // L7: a signed-out leftover marker is completed for the local owner before an account takes over.
    @Test
    fun accountTakeoverCompletesALeftoverSignedOutDeletionFirst() = runBlocking {
        val workspace = workspace()
        val repository = workspace.startProcess()
        val task = repository.seedTask("Write report")
        // A selected task makes the takeover preparation fail in Core ("invalid base selected
        // task identity"), which is a separate finding; this test covers the marker only.
        repository.selectTask(null)
        workspace.service.account = AccountA
        workspace.service.resolutionOffline = true
        val gate = CompletableDeferred<Unit>().also { workspace.auth.signInGate = it }
        val signingIn = launch { repository.signIn(CredentialProvider) }
        awaitDeletionCondition("sign-in started") { repository.state.value.authStatus == AuthStatus.SigningIn }
        // A delete the signed-out workspace could not finish before the sign-in.
        workspace.writeMarker(task.id, SignedOutWorkspaceOwner)

        gate.complete(Unit)
        signingIn.join()

        val takeover = workspace.service.resolutionRequests.single()
        assertTrue(
            "the takeover carries the delete: ${takeover.taskOperations}",
            takeover.taskOperations.any { it.taskId == task.id && it.type == TaskOperationType.Delete },
        )
        assertFalse(repository.state.value.tasks.any { it.id == task.id })
        assertEquals(emptySet<String>(), workspace.markerTaskIds(SignedOutWorkspaceOwner))
    }

    private suspend fun assertDeletedInQueue(
        workspace: PendingDeletionWorkspace,
        repository: TimerRepository,
        task: FocusTask,
    ) {
        assertFalse("the task is gone: ${repository.state.value.tasks}", repository.state.value.tasks.any { it.id == task.id })
        val queued = workspace.queuedTaskOperationTypes()
        assertTrue("the delete is in the owner's durable queue: $queued", TaskOperationType.Delete in queued)
    }

    private fun workspace(account: User? = null, online: Boolean = false) =
        PendingDeletionWorkspace(account, online).also(workspaces::add)

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
        val AccountB = User("account-b", "b@example.test", "B", "")
    }
}
