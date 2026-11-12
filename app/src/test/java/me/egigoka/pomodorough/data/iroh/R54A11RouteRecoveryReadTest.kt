package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * R54-A11: a failed route action reads the persisted route to settle its state. When that read
 * fails too, the action still ends: `transitioning` clears, the state carries the action's
 * bounded notice, both failures are reported, and nothing restarts. A cancelled read still
 * cancels the action.
 */
class R54A11RouteRecoveryReadTest {
    private val actionFailure = IOException("r54 a11 action at /data/user/0/secret-path")
    private val readFailure = IOException("r54 a11 route read")

    @Test
    fun failedRouteChangeWithUnreadableRouteClearsTransitioning() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            harness.setModeFailure = actionFailure
            harness.settingsFailure = readFailure
            val change = runCatching { harness.orchestration.setMode(ReplicationMode.OFFLINE) }
            assertNull(change.exceptionOrNull())
            assertSettled(harness, "Replication route could not be changed")
            assertEquals(ReplicationMode.CENTRALIZED, harness.state.value.mode)
            assertEquals(listOf<Throwable>(actionFailure, readFailure), reports.toList())
            // The next action runs once the route is readable again.
            harness.setModeFailure = null
            harness.settingsFailure = null
            harness.orchestration.setMode(ReplicationMode.OFFLINE)
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
            assertFalse(harness.state.value.transitioning)
        }
    }

    @Test
    fun failedLeaveWithUnreadableRouteDoesNotRestartTheEndpoint() = runBlocking {
        withHarness(ReplicationMode.IROH, RoomId) { harness, reports ->
            harness.orchestration.initialize()
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 1 }
            harness.leaveFailure = actionFailure
            harness.settingsFailure = readFailure
            assertNull(runCatching { harness.orchestration.leaveRoom() }.exceptionOrNull())
            assertSettled(harness, "Iroh room could not be left")
            assertEquals("the room the leave kept stays on screen", RoomId, harness.state.value.roomId)
            assertEquals(1, harness.startCount)
            assertEquals(listOf<Throwable>(actionFailure, readFailure), reports.toList())
            // Sync now binds again once the route is readable.
            harness.leaveFailure = null
            harness.settingsFailure = null
            harness.orchestration.syncNow()
            assertEquals(2, harness.startCount)
            assertFalse(harness.state.value.transitioning)
        }
    }

    @Test
    fun cancelledRouteReadDuringRecoveryStillCancelsTheAction() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val cancellation = CancellationException("r54 a11 cancelled route read")
            harness.setModeFailure = actionFailure
            harness.settingsFailure = cancellation
            val change = runCatching { harness.orchestration.setMode(ReplicationMode.OFFLINE) }
            assertSame(cancellation, change.exceptionOrNull())
            assertFalse(harness.state.value.transitioning)
            assertEquals(listOf<Throwable>(actionFailure), reports.toList())
        }
    }

    private fun assertSettled(harness: IrohRoomOrchestrationHarness, message: String) {
        val state = harness.state.value
        assertFalse("a failed action must not leave network actions disabled", state.transitioning)
        assertEquals(IrohConnectionStatus.UNAVAILABLE, state.status)
        assertEquals(message, state.message)
    }

    private suspend fun withHarness(
        mode: ReplicationMode,
        activeRoomId: String? = null,
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(mode, activeRoomId)
        try {
            test(harness, reports)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previousReporter
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val RoomId = "room-test0001"
    }
}
