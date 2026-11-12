package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A03: the first initialization of a retained room can run with the lifecycle already in
 * the foreground. Its endpoint start then fails inside Sync now's recovery state instead of
 * escaping the caller. The action that ran it binds once, the orchestration adds no crash
 * report, and the room, operations, and secret stay for a retry. Vault failures and
 * cancellation keep their own paths.
 */
class R54A03FirstForegroundTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Test
    fun firstForegroundSyncNowContainsStartFailureWithOneBind() = runBlocking {
        withRetainedRoom { harness, reports ->
            harness.startFailure = IOException("r54 a03 secret invite user-text")
            harness.orchestration.syncNow()
            assertEquals("initialization owns the only bind", 1, harness.startCount)
            assertEquals(0, harness.syncCount)
            assertSyncUnavailable(harness)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun syncNowAfterContainedFirstForegroundBindsAgainAndSucceeds() = runBlocking {
        withRetainedRoom { harness, reports ->
            harness.startFailure = IOException("r54 a03 endpoint")
            harness.orchestration.syncNow()
            harness.startFailure = null
            harness.orchestration.syncNow()
            assertEquals(2, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertNull(harness.state.value.message)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun firstForegroundInitializeContainsStartFailureAndCompletes() = runBlocking {
        // TimerRepository.initialize() reaches the first start through the public initialize().
        withRetainedRoom { harness, reports ->
            harness.startFailure = IOException("r54 a03 endpoint")
            harness.orchestration.initialize()
            assertEquals(1, harness.startCount)
            assertSyncUnavailable(harness)
            harness.orchestration.initialize()
            assertEquals("initialization completed once", 1, harness.startCount)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun firstLifecycleForegroundContainsStartFailureWithOneBind() = runBlocking {
        withRetainedRoom(foregroundFirst = false) { harness, reports ->
            harness.startFailure = IOException("r54 a03 endpoint")
            harness.orchestration.onForeground()
            harness.await {
                harness.startCount > 0 && (harness.state.value.message != null || uncaught.isNotEmpty())
            }
            // Serializes behind the launched foreground action.
            harness.orchestration.initialize()
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals("the foreground action must not bind again", 1, harness.startCount)
            assertSyncUnavailable(harness)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun firstForegroundVaultFailureQuarantinesWithoutSync() = runBlocking {
        withRetainedRoom { harness, reports ->
            val error = IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
            harness.startFailure = error
            harness.orchestration.syncNow()
            assertEquals(error.recoveryKind, harness.state.value.identityRecovery)
            assertEquals(1, harness.startCount)
            assertEquals(0, harness.syncCount)
            assertEquals(1, harness.stopCount)
            assertNull(harness.state.value.message)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun firstForegroundCancellationPropagatesAndLeavesRetry() = runBlocking {
        withRetainedRoom { harness, reports ->
            val cancellation = CancellationException("r54 a03 cancel bind")
            harness.startFailure = cancellation
            assertSame(cancellation, runCatching { harness.orchestration.syncNow() }.exceptionOrNull())
            assertEquals(1, harness.startCount)
            assertEquals(0, harness.syncCount)
            assertNull(harness.state.value.message)
            assertNotEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            harness.startFailure = null
            harness.orchestration.syncNow()
            assertEquals(2, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun syncNowFailureClearsStaleTransitioning() = runBlocking {
        // Sync now's recovery state must clear a `transitioning` flag that an earlier exit left
        // set, as HEAD's catch did (C8). A cancelled route change first reached this state;
        // R54-A06 now clears the flag on that exit, so the stale flag is set directly here.
        withRetainedRoom { harness, reports ->
            harness.orchestration.initialize()
            harness.state.value = harness.state.value.copy(transitioning = true)
            harness.startFailure = IOException("r54 a03 endpoint")
            harness.orchestration.syncNow()
            assertEquals(2, harness.startCount)
            assertEquals(0, harness.syncCount)
            assertSyncUnavailable(harness)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun actionsStillRunAfterLocalOperationRecoveryFailure() = runBlocking {
        // A failed operation capture is reported and published, but initialization still lets
        // the caller's action run, as HEAD did. Mutant MA03a skipped every later action here.
        withRetainedRoom(foregroundFirst = false) { harness, reports ->
            val failure = IllegalStateException("r54 a03 capture")
            harness.captureLocalOperationsFailure = failure
            harness.orchestration.onForeground()
            // The capture runs under the action mutex, so Sync now serializes behind it.
            harness.await { harness.captureCount > 0 }
            harness.orchestration.syncNow()
            assertEquals("the foreground action and Sync now must each bind", 2, harness.startCount)
            assertEquals("Sync now must run", 1, harness.syncCount)
            assertEquals(2, harness.captureCount)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertEquals(listOf<Throwable>(failure, failure), reports.toList())
        }
    }

    private suspend fun withRetainedRoom(
        foregroundFirst: Boolean = true,
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(ReplicationMode.IROH, "room-test0001")
        val room = harness.room
        val settings = harness.settings
        val projection = harness.capturedProjection
        val secret = harness.roomSecret?.copyOf()
        try {
            if (foregroundFirst) enterForegroundWithoutAction(harness)
            test(harness, reports)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(room, harness.room)
            assertEquals(settings, harness.settings)
            assertEquals(projection, harness.capturedProjection)
            assertArrayEquals(secret, harness.roomSecret)
            assertTrue("the route must not change", harness.modes.isEmpty())
            assertEquals(0, harness.clearCount + harness.scrubCount + harness.discardedRooms.size)
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previousReporter
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    // The audit probe's ordering: the lifecycle is foreground before the first initialization
    // runs, as when foreground arrives while TimerRepository.initialize() is still suspended.
    private fun enterForegroundWithoutAction(harness: IrohRoomOrchestrationHarness) {
        val lifecycle = IrohRoomOrchestration::class.java.getDeclaredField("lifecycle")
            .apply { isAccessible = true }
            .get(harness.orchestration) as IrohLifecycleState
        lifecycle.enterForeground()
    }

    private fun assertSyncUnavailable(harness: IrohRoomOrchestrationHarness) {
        val state = harness.state.value
        assertEquals(IrohConnectionStatus.UNAVAILABLE, state.status)
        assertEquals("Iroh sync could not be completed. Try again.", state.message)
        assertFalse(state.transitioning)
        assertNull(state.identityRecovery)
        assertEquals(ReplicationMode.IROH, state.mode)
        assertEquals("room-test0001", state.roomId)
        assertEquals(2, state.operationCount)
        assertEquals(0, harness.stopCount)
    }
}
