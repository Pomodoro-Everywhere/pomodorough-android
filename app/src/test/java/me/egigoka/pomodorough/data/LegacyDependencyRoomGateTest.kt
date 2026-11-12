package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Entering a room stashes the workspace and sends its queue to peers, so a held queue never
 * enters one. Leaving a room is never refused: the returned queue is held after it arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencyRoomGateTest {
    private val harness = LegacyQueueHarness()

    @After
    fun closeHarness() {
        harness.close()
    }

    @Test
    fun heldWorkspaceNeverEntersARoom() = runBlocking<Unit> {
        val route = LegacyRouteController(harness.dao, ReplicationMode.CENTRALIZED, harness.events)
        val (repository, witness) = harness.signedOutArrival(replication = route)
        harness.holdWitnessByReload(repository, witness)
        val entries = listOf<suspend () -> Unit>(
            { repository.createIrohRoom("Room") },
            { repository.setReplicationMode(ReplicationMode.IROH) },
            { repository.joinIrohRoom("invite") },
        )

        for (entry in entries) {
            repository.dismissNotice()
            val mark = harness.events.all().size
            entry()
            assertEquals(emptyList<String>(), harness.events.since(mark).filter(::isRouteEntry))
            assertEquals(ReplicationMode.CENTRALIZED, route.state.value.mode)
            assertEquals(harness.notSaved, repository.state.value.notice)
        }

        harness.dao.refusals.set(0)
        repository.createIrohRoom("Room")
        assertEquals(1, harness.events.count("createRoom"))
    }

    // Leaving goes to OFFLINE. The controller leaves first; the returned queue is then held,
    // and a later switch to CENTRALIZED sends it only after the decision commits.
    @Test
    fun leavingARoomToOfflineHoldsTheReturnedQueue() = runBlocking<Unit> {
        val (repository, route) = harness.irohArrival(remoteSyncIntervalMs = 150L)
        harness.dao.refusals.set(Int.MAX_VALUE)

        repository.leaveIrohRoom()

        assertEquals(1, harness.events.count("leaveRoom"))
        assertEquals(ReplicationMode.OFFLINE, route.state.value.mode)
        assertEquals(harness.notSaved, repository.state.value.notice)
        assertTrue(harness.dao.installs.get() >= 1)

        route.arriving = {}
        repository.setReplicationMode(ReplicationMode.CENTRALIZED)
        repository.onForeground()
        delay(800)
        assertEquals(0, harness.events.rowSyncs())

        harness.dao.refusals.set(0)
        repository.refresh()
        assertTrue(awaitCondition { harness.events.rowSyncs() == 1 })
        awaitQuiet(harness.events)
        assertEquals(1, harness.events.rowSyncs())
    }

    @Test
    fun switchingARoomToCentralizedHoldsTheReturnedQueue() = runBlocking<Unit> {
        val (repository, _) = harness.irohArrival(remoteSyncIntervalMs = 150L)
        harness.dao.refusals.set(Int.MAX_VALUE)

        repository.setReplicationMode(ReplicationMode.CENTRALIZED)

        assertEquals(harness.notSaved, repository.state.value.notice)
        delay(400)
        assertEquals(0, harness.events.rowSyncs())
        repository.onForeground()
        delay(800)
        assertEquals(0, harness.events.rowSyncs())

        repository.dismissNotice()
        repository.resolveHistory(BootstrapStrategy.Merge)
        assertEquals(0, harness.events.count("resolveBootstrap"))
        assertEquals(harness.notSaved, repository.state.value.notice)
    }

    private fun isRouteEntry(event: String) =
        event.startsWith("setMode:") || event == "createRoom" || event == "joinRoom"
}
