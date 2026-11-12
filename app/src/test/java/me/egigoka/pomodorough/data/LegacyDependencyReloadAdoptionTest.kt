package me.egigoka.pomodorough.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Startup skips adoption in an Iroh room, so the workspace a route change brings back can still
 * carry a pre-R54 queue. The reload asks Core for its decision before any sync can send it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDependencyReloadAdoptionTest {
    private val harness = LegacyQueueHarness()

    @After
    fun closeHarness() {
        harness.close()
    }

    @Test
    fun switchFromIrohToCentralizedAdoptsBeforeTheFirstRowSync() = runBlocking<Unit> {
        val (repository, _) = harness.irohArrival()

        repository.setReplicationMode(ReplicationMode.CENTRALIZED)

        assertTrue(awaitCondition { harness.events.rowSyncs() >= 1 })
        val events = harness.events.all()
        assertEquals(1, harness.events.count("adopt"))
        val firstRowSync = events.indexOfFirst { it.startsWith("sync:") && it != LegacyQueueEvents.EmptySync }
        assertTrue(events.indexOf("adopt") < firstRowSync)
    }

    @Test
    fun blockedArrivalFromIrohHoldsSyncAndMutations() = runBlocking<Unit> {
        val (repository, _) = harness.irohArrival(dispatch = harness.dispatch(corruptAdoption = true))

        repository.setReplicationMode(ReplicationMode.CENTRALIZED)
        awaitQuiet(harness.events)

        assertEquals(harness.recovery, repository.state.value.notice)
        assertFalse(repository.addTask("Blocked"))
        assertEquals(0, harness.events.rowSyncs())
    }

    @Test
    fun leavingARoomAdoptsTheReturnedQueueOnce() = runBlocking<Unit> {
        val (repository, route) = harness.irohArrival()

        repository.leaveIrohRoom()

        assertEquals(1, harness.events.count("adopt"))
        assertFalse(harness.dao.legacyAdoptionPending())

        route.arriving = {}
        repository.setReplicationMode(ReplicationMode.CENTRALIZED)
        assertTrue(awaitCondition { harness.events.rowSyncs() >= 1 })
        awaitQuiet(harness.events)
        assertEquals(1, harness.events.count("adopt"))
        assertEquals(1, harness.events.rowSyncs())
    }
}
