package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sign-in, restore, a history choice and recovery can each send the retained queue through
 * bootstrap resolution. While the Core decision is not recorded, none of them reaches the network
 * with it, and the hold notice stays visible until the decision commits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencyAccountNetworkTest {
    private val harness = LegacyQueueHarness()

    @After
    fun closeHarness() {
        harness.close()
    }

    @Test
    fun heldSignInNeverReachesTheNetwork() = runBlocking<Unit> {
        val auth = LegacyQueueAuth(signedIn = false, signInSucceeds = true)
        val (repository, witness) = harness.signedOutArrival(auth)
        harness.holdWitnessByReload(repository, witness)
        repository.dismissNotice()

        repository.signIn(LegacyCredentialProvider)

        assertEquals(0, auth.signInCalls.get())
        assertEquals(0, harness.events.count("bootstrap"))
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertEquals(harness.notSaved, repository.state.value.notice)

        harness.dao.refusals.set(0)
        repository.signIn(LegacyCredentialProvider)
        assertEquals(1, auth.signInCalls.get())
        assertEquals(1, harness.events.count("bootstrap"))
    }

    @Test
    fun heldHistoryChoiceIsDroppedBeforeResolveBootstrap() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival()
        harness.holdWitnessByReload(repository, witness)
        repository.dismissNotice()

        repository.resolveHistory(BootstrapStrategy.Merge)

        assertEquals(harness.notSaved, repository.state.value.notice)
        assertEquals(0, harness.events.count("resolveBootstrap"))
        assertNull(harness.dao.pendingBootstrapResolution())
        assertNotEquals(true, repository.state.value.historyResolution?.submitting)

        harness.dao.refusals.set(0)
        repository.resolveHistory(BootstrapStrategy.Merge)
        assertNotEquals(harness.notSaved, repository.state.value.notice)
        assertFalse(harness.dao.legacyAdoptionPending())
    }

    // The restore fetched its bootstrap before the reload installed the held queue, so the
    // completion is stale. The restore re-arms, and the first foreground after adoption runs it once.
    @Test
    fun reloadDuringRestoreBootstrapEndsSignedOutAndRetriesOnceAfterAdoption() = runBlocking<Unit> {
        val witness = prepareLegacyArrival(harness.dao, signedIn = true)
        val repository = harness.repository(LegacyQueueAuth(signedIn = true))
        val gate = CompletableDeferred<Unit>().also { harness.server.bootstrapGate = it }
        val startup = launch(Dispatchers.IO) { repository.initialize() }
        withTimeout(8_000) { harness.server.bootstrapEntered.await() }
        harness.holdWitnessByReload(repository, witness)
        gate.complete(Unit)
        startup.join()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertEquals(harness.notSaved, repository.state.value.notice)
        assertEquals(0, harness.events.count("resolveBootstrap"))

        harness.dao.refusals.set(0)
        harness.server.bootstrapGate = null
        // The sign-in transport fails after the decision commits, which re-arms the restore.
        repository.signIn(LegacyCredentialProvider)
        assertFalse(harness.dao.legacyAdoptionPending())

        repository.onForeground()
        assertTrue(awaitCondition { repository.state.value.authStatus == AuthStatus.SignedIn })
        awaitQuiet(harness.events)
        assertEquals(2, harness.events.count("bootstrap"))

        repository.onForeground()
        awaitQuiet(harness.events)
        assertEquals(2, harness.events.count("bootstrap"))
    }

    // A successful mutation must not clear the hold, because a cleared failure reopens workspace
    // reload and restore admission while the queue still waits for its decision.
    @Test
    fun savedMutationKeepsTheHoldNoticeAndRefusesReload() = runBlocking<Unit> {
        val (repository, witness) = harness.signedOutArrival()
        val modern = harness.dao.legacyDependencySnapshot()
        harness.holdWitnessByReload(repository, witness)
        val autoStart = repository.state.value.settings.autoStartBreaks

        repository.setAutoStart(!autoStart)

        assertEquals(!autoStart, repository.state.value.settings.autoStartBreaks)
        assertEquals(harness.notSaved, repository.state.value.notice)
        harness.dao.replaceWorkspace(modern)
        repository.reloadWorkspace()
        assertTrue(repository.state.value.tasks.any { it.title == "Café" })
    }

    @Test
    fun heldRecoveryShowsTheHoldNoticeInTheDialog() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival()
        val accountSync = repository.accountSync()
        accountSync.historyResolution = RepreviewResolution
        val gate = CompletableDeferred<Unit>().also { harness.server.bootstrapGate = it }
        val entered = CompletableDeferred<Unit>().also { harness.server.bootstrapEntered = it }
        val recovery = launch(Dispatchers.IO) { repository.recoverCorruptedResolution() }
        withTimeout(8_000) { entered.await() }

        // An open resolution refuses a reload, so the scenario steps it aside for the reload only.
        val refreshing = accountSync.historyResolution
        accountSync.historyResolution = null
        harness.holdWitnessByReload(repository, witness)
        accountSync.historyResolution = refreshing
        gate.complete(Unit)
        recovery.join()

        val resolution = checkNotNull(repository.state.value.historyResolution)
        assertEquals(harness.notSaved, resolution.error)
        assertFalse(resolution.submitting)
        assertEquals(0, harness.events.count("resolveBootstrap"))
    }

    @Test
    fun heldRecoveryNeverFetchesBootstrap() = runBlocking<Unit> {
        val (repository, witness) = harness.signedInArrival()
        harness.holdWitnessByReload(repository, witness)
        repository.accountSync().historyResolution = RepreviewResolution
        repository.dismissNotice()
        val bootstraps = harness.events.count("bootstrap")

        repository.recoverCorruptedResolution()

        assertEquals(bootstraps, harness.events.count("bootstrap"))
        assertEquals(false, repository.state.value.historyResolution?.submitting)
        assertEquals(harness.notSaved, repository.state.value.notice)
    }

    private fun TimerRepository.accountSync(): CentralizedAccountSyncAggregate =
        TimerRepository::class.java.getDeclaredField("centralizedAccountSync")
            .apply { isAccessible = true }
            .get(this) as CentralizedAccountSyncAggregate

    private companion object {
        val RepreviewResolution = HistoryResolutionState(
            localHistoryCount = 0,
            remoteHistoryCount = 0,
            corrupted = true,
            recovery = ResolutionRecovery.Repreview,
        )
    }
}
