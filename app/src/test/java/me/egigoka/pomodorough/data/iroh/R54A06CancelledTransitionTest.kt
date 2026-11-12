package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A06: an Iroh action that publishes `transitioning` clears it when it is cancelled.
 * NetworkSection disables the network actions and TimerRepository blocks timer mutations while
 * the flag is set, so a cancelled join, leave, route change, or room creation needed a process
 * restart. Each case shows that the cancellation still reaches the caller, that the state is the
 * persisted route without an error message, and that the next action works. Actions that never
 * set the flag keep their state when cancelled.
 */
class R54A06CancelledTransitionTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Test
    fun backgroundCancelledCentralizedJoinKeepsNetworkActionsEnabled() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            harness.orchestration.onBackground()
            val join = runCatching { harness.orchestration.joinRoom(harness.invite()) }
            assertTrue(
                "the lifecycle gate cancels the join: ${join.exceptionOrNull()}",
                join.exceptionOrNull() is CancellationException,
            )
            assertEquals(0, harness.startCount)
            assertPersistedRoute(harness, ReplicationMode.CENTRALIZED, roomId = null)
            returnToForegroundWithoutAction(harness)
            harness.orchestration.joinRoom(harness.invite())
            assertEquals(2, harness.startCount)
            assertEquals(1, harness.joinCount)
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun cancelledCallerDuringJoinStartRestoresThePersistedRoute() = runBlocking {
        // The caller's scope is cancelled while the endpoint binds, so the route read that clears
        // the flag runs in a cancelled coroutine and must not be cancelled itself.
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            harness.startGate = CompletableDeferred()
            harness.yieldOnSettingsRead = true
            val outcome = CompletableDeferred<Throwable?>()
            val caller = launch(Dispatchers.IO) {
                try {
                    harness.orchestration.joinRoom(harness.invite())
                    outcome.complete(null)
                } catch (error: Throwable) {
                    outcome.complete(error)
                    throw error
                }
            }
            harness.await { harness.startCount == 1 }
            assertTrue(harness.state.value.transitioning)
            caller.cancelAndJoin()
            assertTrue("the caller's cancellation propagates", outcome.await() is CancellationException)
            assertPersistedRoute(harness, ReplicationMode.CENTRALIZED, roomId = null)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun recoveryReportedDuringJoinKeepsTheRecoveryPrompt() = runBlocking {
        // The endpoint refuses its identity mid-join and the second bind is cancelled. The
        // recovery prompt owns the state, so the persisted route must not replace it.
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            harness.joinRecovery = IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED
            val join = runCatching { harness.orchestration.joinRoom(harness.invite()) }
            assertTrue(join.exceptionOrNull() is CancellationException)
            assertEquals(1, harness.startCount)
            val state = harness.state.value
            assertEquals(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED, state.identityRecovery)
            assertFalse(state.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun cancelledLeaveKeepsTheRoomAndSyncNowWorks() = runBlocking {
        withForegroundRoom { harness, reports ->
            val cancellation = CancellationException("r54 a06 cancelled leave")
            harness.leaveFailure = cancellation
            assertSame(cancellation, runCatching { harness.orchestration.leaveRoom() }.exceptionOrNull())
            assertEquals(1, harness.stopCount)
            assertPersistedRoute(harness, ReplicationMode.IROH, roomId = RoomId)
            harness.leaveFailure = null
            assertSyncNowBinds(harness)
            harness.orchestration.leaveRoom()
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun cancelledModeChangeKeepsTheRouteAndSyncNowWorks() = runBlocking {
        withForegroundRoom { harness, reports ->
            val cancellation = CancellationException("r54 a06 cancelled route change")
            harness.setModeFailure = cancellation
            val change = runCatching { harness.orchestration.setMode(ReplicationMode.OFFLINE) }
            assertSame(cancellation, change.exceptionOrNull())
            assertEquals(1, harness.stopCount)
            assertPersistedRoute(harness, ReplicationMode.IROH, roomId = RoomId)
            harness.setModeFailure = null
            assertSyncNowBinds(harness)
            harness.orchestration.setMode(ReplicationMode.OFFLINE)
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun cancelledRoomCreationKeepsTheRouteAndCreatesOnRetry() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val cancellation = CancellationException("r54 a06 cancelled creation")
            harness.createRoomFailure = cancellation
            assertSame(cancellation, runCatching { harness.orchestration.createRoom("A06") }.exceptionOrNull())
            assertPersistedRoute(harness, ReplicationMode.CENTRALIZED, roomId = null)
            harness.createRoomFailure = null
            harness.orchestration.createRoom("A06")
            assertEquals(listOf<String?>("A06"), harness.createdNames)
            assertEquals(1, harness.startCount)
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertNotNull(harness.state.value.invite)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun unreadableRouteAfterCancellationStillClearsTransitioning() = runBlocking {
        // The route read fails while the flag is cleared. The read failure is reported, and the
        // caller still receives its own cancellation.
        withHarness(ReplicationMode.IROH, RoomId) { harness, reports ->
            harness.orchestration.initialize()
            val cancellation = CancellationException("r54 a06 cancelled route change")
            val readFailure = IOException("r54 a06 route read")
            harness.setModeFailure = cancellation
            harness.settingsFailure = readFailure
            val change = runCatching { harness.orchestration.setMode(ReplicationMode.OFFLINE) }
            assertSame(cancellation, change.exceptionOrNull())
            assertFalse(harness.state.value.transitioning)
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertEquals(listOf<Throwable>(readFailure), reports.toList())
        }
    }

    @Test
    fun cancelledRollbackAfterFailedJoinClearsTransitioning() = runBlocking {
        // The cancellation arrives in the join's failure handling, after its cancellation catch.
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val cancellation = CancellationException("r54 a06 cancelled rollback")
            harness.startFailure = IOException("r54 a06 endpoint")
            harness.discardInactiveFailure = cancellation
            val join = runCatching { harness.orchestration.joinRoom(harness.invite()) }
            assertSame(cancellation, join.exceptionOrNull())
            assertEquals(1, harness.discardedRooms.size)
            assertPersistedRoute(harness, ReplicationMode.CENTRALIZED, roomId = null)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun cancelledSyncNowKeepsTheLiveState() = runBlocking {
        // Sync now never publishes `transitioning`, so its cancellation must not replace the
        // live endpoint state with the persisted route.
        withForegroundRoom { harness, reports ->
            harness.orchestration.syncNow()
            val live = harness.state.value
            val cancellation = CancellationException("r54 a06 cancelled sync")
            harness.syncFailure = cancellation
            assertSame(cancellation, runCatching { harness.orchestration.syncNow() }.exceptionOrNull())
            assertEquals(IrohConnectionStatus.LISTENING, live.status)
            assertEquals(live, harness.state.value)
            assertTrue(reports.isEmpty())
        }
    }

    private suspend fun withForegroundRoom(
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) = withHarness(ReplicationMode.IROH, RoomId) { harness, reports ->
        harness.orchestration.initialize()
        harness.orchestration.onForeground()
        harness.await { harness.startCount == 1 }
        test(harness, reports)
    }

    private suspend fun withHarness(
        mode: ReplicationMode,
        activeRoomId: String? = null,
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
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

    private suspend fun assertSyncNowBinds(harness: IrohRoomOrchestrationHarness) {
        val starts = harness.startCount
        harness.orchestration.syncNow()
        assertEquals(starts + 1, harness.startCount)
        assertEquals(1, harness.syncCount)
        assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
        assertFalse(harness.state.value.transitioning)
    }

    private fun assertPersistedRoute(
        harness: IrohRoomOrchestrationHarness,
        mode: ReplicationMode,
        roomId: String?,
    ) {
        val state = harness.state.value
        assertFalse("a cancelled action must not leave network actions disabled", state.transitioning)
        assertEquals(mode, state.mode)
        assertEquals(roomId, state.roomId)
        assertNull(state.message)
        assertNotEquals(IrohConnectionStatus.UNAVAILABLE, state.status)
    }

    // The user returns to the app. Only the lifecycle matters here; the foreground action is
    // R54-A05's subject, and in the centralized route it starts nothing.
    private fun returnToForegroundWithoutAction(harness: IrohRoomOrchestrationHarness) {
        val lifecycle = IrohRoomOrchestration::class.java.getDeclaredField("lifecycle")
            .apply { isAccessible = true }
            .get(harness.orchestration) as IrohLifecycleState
        lifecycle.enterForeground()
    }

    private companion object {
        const val RoomId = "room-test0001"
    }
}
