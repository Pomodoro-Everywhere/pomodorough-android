package me.egigoka.pomodorough.data

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A retained pre-R54 queue never leaves the device before Core records its decision. Each held
 * sync attempt retries the decision write and raises the hold notice again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencySyncGuardTest {
    private val harness = LegacyQueueHarness()

    @After
    fun closeHarness() {
        harness.close()
    }

    @Test
    fun heldQueuePreparesNoAttemptUntilItsDecisionCommits() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival()
        harness.holdWitnessByReload(repository, witness)
        val host = repository.centralizedSyncRuntimeHost()

        assertNull(host.prepareGuardAttempt())
        assertEquals(harness.notSaved, repository.state.value.notice)

        repository.dismissNotice()
        val installsBefore = harness.dao.installs.get()
        assertNull(host.prepareGuardAttempt())
        assertEquals(harness.notSaved, repository.state.value.notice)
        assertTrue(harness.dao.installs.get() > installsBefore)

        harness.dao.refusals.set(0)
        assertNotNull(host.prepareGuardAttempt())
        assertNull(repository.state.value.notice)
        assertFalse(harness.dao.legacyAdoptionPending())
    }

    @Test
    fun blockedDecisionPreparesNoAttempt() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival(dispatch = harness.dispatch(corruptAdoption = true))
        harness.dao.replaceWorkspace(witness)
        repository.reloadWorkspace()

        assertNull(repository.centralizedSyncRuntimeHost().prepareGuardAttempt())
        assertEquals(harness.recovery, repository.state.value.notice)
    }

    @Test
    fun refreshWhileHeldSendsNothingAndKeepsTheQueue() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival(online = true)
        awaitQuiet(harness.events)
        harness.holdWitnessByReload(repository, witness)

        repository.refresh()
        awaitQuiet(harness.events)

        val state = repository.state.value
        assertEquals(0, harness.events.rowSyncs())
        assertNotEquals(SyncStatus.Syncing, state.syncStatus)
        assertEquals(harness.notSaved, state.notice)
        assertNull(state.conflict)
        val stored = harness.dao.legacyDependencySnapshot()
        assertEquals(witness.copy(local = stored.local), stored)
    }

    @Test
    fun refreshAfterTheDecisionCommitsSendsTheQueueOnce() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival(online = true)
        awaitQuiet(harness.events)
        harness.dao.replaceWorkspace(witness)
        harness.dao.refusals.set(1)
        repository.reloadWorkspace()
        assertEquals(1, harness.dao.installs.get())
        assertEquals(0, harness.events.rowSyncs())

        repository.refresh()

        assertTrue(awaitCondition { harness.events.rowSyncs() == 1 })
        awaitQuiet(harness.events)
        assertEquals(2, harness.dao.installs.get())
        assertEquals(1, harness.events.rowSyncs())
        assertFalse(harness.dao.legacyAdoptionPending())
    }

    @Test
    fun reloadDuringRetryDelayReplacesRetryingWithTheHoldNotice() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival(online = true, retryDelayMs = 1_500L)
        awaitQuiet(harness.events)
        harness.server.nextRowSyncFailure = IOException("offline during the first send")
        assertTrue(repository.addTask("Modern"))
        assertTrue(awaitCondition { repository.state.value.syncStatus == SyncStatus.Retrying })

        harness.holdWitnessByReload(repository, witness)

        assertNotEquals(SyncStatus.Retrying, repository.state.value.syncStatus)
        assertEquals(harness.notSaved, repository.state.value.notice)
        delay(2_000)
        assertEquals(1, harness.events.rowSyncs())
        assertNotEquals(SyncStatus.Retrying, repository.state.value.syncStatus)
    }

    private suspend fun CentralizedSyncRuntimeHost.prepareGuardAttempt(): SyncAttempt? =
        prepareSyncAttempt(SyncAttemptIdentity(accountGeneration(), "guard"))
}
