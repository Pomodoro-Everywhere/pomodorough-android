package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.PendingDeletionWorkspace.CredentialProvider
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-18: sign-in moves the route to Cloud before the account exists. A sign-in that fails puts
 * back the route the user chose, so the Network tab never shows Cloud without an account.
 * The scripted server has no account unless a test sets one, so the profile request fails offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignInFailureRouteRestoreTest {
    private val workspaces = mutableListOf<PendingDeletionWorkspace>()

    @After
    fun tearDown() = workspaces.forEach { it.close() }

    @Test
    fun failedSignInFromOnDeviceKeepsOnDevice() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.OFFLINE)
        val repository = workspace.startProcess(route)

        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertRouteSettles(repository, route, ReplicationMode.OFFLINE)
    }

    @Test
    fun failedSignInFromARoomReturnsToTheRoomWorkspace() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        route.copyCurrentToAway()
        val roomTask = repository.seedTask("Room plan")
        repository.selectTask(null)

        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertRouteSettles(repository, route, ReplicationMode.IROH)
        val shown = repository.state.value.tasks
        assertTrue("the room workspace is back on screen: $shown", shown.any { it.id == roomTask.id })
    }

    @Test
    fun failedSignInFromCloudStaysOnCloud() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.CENTRALIZED)
        val repository = workspace.startProcess(route)

        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertRouteSettles(repository, route, ReplicationMode.CENTRALIZED)
    }

    @Test
    fun successfulSignInFromOnDeviceKeepsCloud() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.OFFLINE)
        val repository = workspace.startProcess(route)
        workspace.service.account = Account

        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertRouteSettles(repository, route, ReplicationMode.CENTRALIZED)
    }

    // R3 acceptance N9: the failed sign-in itself blocked the account network, so the restore
    // is refused like every other route change and Cloud stays selected until the reset.
    @Test
    fun refusedCredentialClearKeepsCloudWhileTheResetIsRequired() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        route.copyCurrentToAway()
        rejectProfileAndRefuseTheCredentialClear(workspace)

        repository.signIn(CredentialProvider)

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue("the app asks for a local reset", repository.state.value.localAccountResetRequired)
        assertRouteSettles(repository, route, ReplicationMode.CENTRALIZED)
    }

    // R3 acceptance N9: the reset the app asks for clears the workspace of the stored route.
    // That route stays Cloud, so the room the user signed in from keeps its tasks.
    @Test
    fun resetAfterARefusedCredentialClearKeepsTheRoomTasks() = runBlocking {
        val workspace = PendingDeletionWorkspace().also(workspaces::add)
        val route = workspace.workspaceSwap(ReplicationMode.IROH)
        val repository = workspace.startProcess(route)
        route.copyCurrentToAway()
        val roomTask = repository.seedTask("Room plan")
        repository.selectTask(null)
        rejectProfileAndRefuseTheCredentialClear(workspace)
        repository.signIn(CredentialProvider)

        repository.resetLocalAccount()
        repository.setReplicationMode(ReplicationMode.IROH)

        assertFalse("the reset finished", repository.state.value.localAccountResetRequired)
        assertRouteSettles(repository, route, ReplicationMode.IROH)
        awaitDeletionCondition("the room task back on screen") {
            repository.state.value.tasks.any { it.id == roomTask.id }
        }
        val restarted = workspace.startProcess(route)
        awaitDeletionCondition("the room task after a process restart") {
            restarted.state.value.tasks.any { it.id == roomTask.id }
        }
    }

    // A blank account id is a malformed profile (TimerSyncValidation.validateUser), which makes
    // the repository clear the rejected credentials; the store refuses that clear once.
    private fun rejectProfileAndRefuseTheCredentialClear(workspace: PendingDeletionWorkspace) {
        workspace.service.account = Account.copy(id = "")
        workspace.auth.failingClears = 1
    }

    // The route itself changes before signIn returns; the published state follows on the
    // repository's collector, so the test waits for it instead of reading a stale value.
    private suspend fun assertRouteSettles(
        repository: TimerRepository,
        route: WorkspaceSwapReplication,
        expected: ReplicationMode,
    ) {
        assertEquals(expected, route.state.value.mode)
        awaitDeletionCondition("published route $expected") { repository.state.value.network.mode == expected }
    }

    private companion object {
        val Account = User("account-a", "a@example.test", "A", "")
    }
}
