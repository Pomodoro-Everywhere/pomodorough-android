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
 * UX round 2, batch B rework2 item 2: an account takes over a signed-out workspace whose selected
 * task exists only in the pending queue. Core rejects a base selection that is absent from the
 * base tasks ("invalid base selected task identity"), so the takeover base must not carry the
 * optimistic selection; the pending selectTask operation carries it instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignedOutTakeoverSelectedTaskTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun takeoverOfAPendingSelectedTaskCompletesWithTheTaskSelected() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val repository = workspace.startProcess()
        val task = repository.seedSelectedTask("Write report")
        workspace.service.account = AccountA

        repository.signIn(CredentialProvider)

        val state = repository.state.value
        assertFalse(
            "the takeover base is valid: ${state.notice}",
            state.notice?.contains("selected task identity") == true,
        )
        assertEquals(state.notice, AuthStatus.SignedIn, state.authStatus)
        assertNull("the takeover finished: ${state.historyResolution?.error}", state.historyResolution)
        val takeover = workspace.service.resolutionRequests.single()
        assertTrue(
            "the takeover carries the selection: ${takeover.selectedTaskOperations}",
            takeover.selectedTaskOperations.orEmpty().any { it.taskId == task.id },
        )
        assertEquals(listOf(task.id), state.tasks.map(FocusTask::id))
        assertEquals(task.id, state.selectedTaskId)
    }

    private companion object {
        val AccountA = User("account-a", "a@example.test", "A", "")
    }
}
