package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A12: a join binds the endpoint twice, before and after the room join. A caller cancelled
 * after the first bind restored the persisted route but left that endpoint bound. The join now
 * stops it, unless the cancellation lands before the join replaced the previous endpoint.
 */
class R54A12CancelledJoinTest {
    @Test
    fun cancelledJoinAfterFirstBindStopsTheEndpoint() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val (outcome, stops) = cancelJoinDuringSecondBind(harness)
            assertTrue("the caller's cancellation propagates", outcome is CancellationException)
            assertEquals("a cancelled join must stop the endpoint it bound", 1, stops)
            assertEquals(ReplicationMode.CENTRALIZED, harness.state.value.mode)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun failedStopOfACancelledJoinIsReportedAndTheCancellationPropagates() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val stopFailure = IOException("r54 a12 stop")
            val (outcome, stops) = cancelJoinDuringSecondBind(harness) {
                harness.stopFailure = stopFailure
            }
            assertTrue("the caller's cancellation propagates: $outcome", outcome is CancellationException)
            assertEquals(1, stops)
            assertFalse(harness.state.value.transitioning)
            assertEquals(listOf<Throwable>(stopFailure), reports.toList())
        }
    }

    @Test
    fun joinCancelledBeforeItsRoomIsPreparedKeepsTheCurrentEndpoint() = runBlocking {
        // The cancellation lands while the join flushes the active room, before it stops that
        // room's endpoint, so the endpoint still serves the route the cancellation keeps.
        withHarness(ReplicationMode.IROH, RoomId) { harness, reports ->
            harness.orchestration.initialize()
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 1 }
            val cancellation = CancellationException("r54 a12 cancelled flush")
            harness.captureLocalOperationsFailure = cancellation
            val stopsBefore = harness.stopCount
            val join = runCatching { harness.orchestration.joinRoom(harness.invite()) }
            assertSame(cancellation, join.exceptionOrNull())
            assertEquals(stopsBefore, harness.stopCount)
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertEquals(RoomId, harness.state.value.roomId)
            assertFalse(harness.state.value.transitioning)
            assertTrue(reports.isEmpty())
        }
    }

    // Holds the join's second bind, cancels its caller there, and returns the caller's outcome
    // with the number of stops the cancellation caused.
    private suspend fun CoroutineScope.cancelJoinDuringSecondBind(
        harness: IrohRoomOrchestrationHarness,
        beforeCancel: () -> Unit = {},
    ): Pair<Throwable?, Int> {
        val firstBind = CompletableDeferred<Unit>()
        harness.startGate = firstBind
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
        harness.startGate = CompletableDeferred()
        firstBind.complete(Unit)
        harness.await { harness.startCount == 2 }
        val stopsBefore = harness.stopCount
        beforeCancel()
        caller.cancelAndJoin()
        return outcome.await() to harness.stopCount - stopsBefore
    }

    private suspend fun withHarness(
        mode: ReplicationMode,
        activeRoomId: String? = null,
        test: suspend CoroutineScope.(IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(mode, activeRoomId)
        try {
            coroutineScope { test(harness, reports) }
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
