package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
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
 * UX round 2, batch B rework2 item 2, centralized head: a signed-in owner already holds a safe
 * canonical head when it reviews a corrupted history resolution again. The bootstrap base then
 * comes from TimerRepository.currentProjectionBase(), whose selection under a head is the safe
 * base selection. The optimistic selection of a task that only the pending queue holds must not
 * reach the base, or Core rejects it ("invalid base selected task identity").
 *
 * The second case tells the head branch from the no-head branch: both tasks are canonical, the
 * server's selection is the safe base selection, and the owner selected the other task offline.
 * Under a head the preparation base must keep the server's selection; the pending selectTask
 * operation carries the owner's choice. The no-head fallback, or a base rebuilt from the
 * optimistic local selection, puts the owner's choice in the base instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CentralizedHeadRepreviewBaseTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun repreviewUnderAHeadProjectsOverTheSafeBaseSelection() = runBlocking {
        val workspace = PendingDeletionWorkspace(AccountA, online = true).also(workspaces::add)
        workspace.service.account = AccountA
        val first = workspace.startProcess()
        assertEquals(first.state.value.notice, AuthStatus.SignedIn, first.state.value.authStatus)
        val task = first.seedSelectedTask("Write report")
        val stored = workspace.storedLocal()
        assertTrue("the owner holds a centralized head", stored.safeCanonicalHeadWallMs != null)
        assertNull("the safe base has no selection", stored.safeBaseSelectedTaskId)
        assertEquals("the optimistic selection is the pending task", task.id, stored.selectedTaskId)
        workspace.saveCorruptedResolution(AccountA)
        val repository = workspace.startProcess()
        awaitDeletionCondition("the saved resolution needs a new review") {
            repository.state.value.historyResolution?.recovery == ResolutionRecovery.Repreview
        }
        workspace.service.resolutionRequests.clear()

        repository.recoverCorruptedResolution()

        val state = repository.state.value
        val failure = listOfNotNull(state.notice, state.historyResolution?.error).joinToString()
        assertFalse("the bootstrap base is valid: $failure", "selected task identity" in failure)
        assertNull("the review finished: $failure", state.historyResolution)
        assertEquals(AuthStatus.SignedIn, state.authStatus)
        val request = workspace.service.resolutionRequests.single()
        assertTrue(
            "the resolution carries the selection: ${request.selectedTaskOperations}",
            request.selectedTaskOperations.orEmpty().any { it.taskId == task.id },
        )
        assertEquals(task.id, state.selectedTaskId)
    }

    @Test
    fun repreviewUnderAHeadKeepsTheServerSelectionInTheBase() = runBlocking {
        val workspace = PendingDeletionWorkspace(AccountA, online = true).also(workspaces::add)
        workspace.service.account = AccountA
        val serverSelected = workspace.canonicalTask("Write report")
        val ownerChoice = workspace.canonicalTask("Read mail")
        workspace.service.tasks[AccountA.id] = listOf(serverSelected, ownerChoice)
        workspace.service.selectedTaskIds[AccountA.id] = serverSelected.id
        val first = workspace.startProcess()
        assertEquals(first.state.value.notice, AuthStatus.SignedIn, first.state.value.authStatus)
        first.selectTask(ownerChoice.id)
        val stored = workspace.storedLocal()
        assertTrue("the owner holds a centralized head", stored.safeCanonicalHeadWallMs != null)
        assertEquals("the safe base keeps the server selection", serverSelected.id, stored.safeBaseSelectedTaskId)
        assertEquals("the optimistic selection is the owner's choice", ownerChoice.id, stored.selectedTaskId)
        workspace.saveCorruptedResolution(AccountA)
        val repository = workspace.startProcess()
        awaitDeletionCondition("the saved resolution needs a new review") {
            repository.state.value.historyResolution?.recovery == ResolutionRecovery.Repreview
        }
        workspace.bootstrapBaseSelections.clear()
        workspace.service.resolutionRequests.clear()

        repository.recoverCorruptedResolution()

        val state = repository.state.value
        assertNull("the review finished: ${state.notice}", state.historyResolution)
        assertEquals(
            "the preparation projects over the safe base selection",
            listOf<String?>(serverSelected.id),
            workspace.bootstrapBaseSelections.distinct(),
        )
        val request = workspace.service.resolutionRequests.single()
        assertTrue(
            "the resolution carries the owner's choice: ${request.selectedTaskOperations}",
            request.selectedTaskOperations.orEmpty().any { it.taskId == ownerChoice.id },
        )
    }

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
    }
}
