package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A blocked Core decision is final for the reloaded queue. The workspace takes the corrupt-state
 * route, and Reset Local Account is the one way out: it clears the queue and both markers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencyBlockedResetTest {
    private val harness = LegacyQueueHarness()
    private val auth = LegacyQueueAuth(signedIn = false)

    @After
    fun closeHarness() {
        harness.close()
    }

    @Test
    fun blockedReloadAsksForALocalResetAndRefusesSignIn() = runBlocking<Unit> {
        val repository = blockedArrival()

        val state = repository.state.value
        assertTrue(state.localAccountResetRequired)
        assertEquals(harness.recovery, state.conflict)
        assertEquals(harness.recovery, state.notice)

        repository.signIn(LegacyCredentialProvider)
        assertEquals(0, auth.signInCalls.get())
    }

    @Test
    fun resetClearsTheBlockedQueueAndBothMarkers() = runBlocking<Unit> {
        val repository = blockedArrival()

        repository.resetLocalAccount()

        val local = checkNotNull(harness.dao.localState())
        assertNull(local.legacyDependencyInputJson)
        assertNull(local.legacyDependencyResultJson)
        assertTrue(harness.dao.pendingCommands().isEmpty())
        assertTrue(repository.addTask("After reset"))
    }

    // The reset leaves the account's own delete for its next sign-in, and finishes the delete
    // that belongs to the signed-out workspace the reset leaves behind.
    @Test
    fun resetKeepsTheAccountsDeletionMarkerAndReplaysTheLocalOne() = runBlocking<Unit> {
        val repository = blockedArrival()
        val markers = PendingTaskDeletionMarkerStore(
            harness.context.getSharedPreferences(PendingTaskDeletionMarkerStore.PreferencesName, Context.MODE_PRIVATE),
        )
        markers.write(PendingTaskDeletionMarker("gone-account", LegacyProofProfile.id))
        markers.write(PendingTaskDeletionMarker("gone-local", PendingTaskDeletionMarker.SignedOutWorkspaceOwner))

        repository.resetLocalAccount()

        assertEquals(setOf("gone-account"), markers.read(LegacyProofProfile.id))
        assertTrue(markers.read(PendingTaskDeletionMarker.SignedOutWorkspaceOwner).isEmpty())
    }

    private suspend fun blockedArrival(): TimerRepository {
        val (repository, witness) = harness.signedOutArrival(auth, harness.dispatch(corruptAdoption = true))
        harness.dao.replaceWorkspace(witness)
        repository.reloadWorkspace()
        return repository
    }
}
