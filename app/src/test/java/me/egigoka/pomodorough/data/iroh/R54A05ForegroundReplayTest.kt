package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A05: TimerRepository.initialize() replays foreground on every call. A replay while the
 * lifecycle is already foreground must not renew its generation, which strands the endpoint
 * start the current foreground owns, and must not start the endpoint again. A failed start in
 * the foreground action publishes the Sync now notice, never the exception text.
 */
class R54A05ForegroundReplayTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Test
    fun foregroundReplayKeepsTheInFlightStartAndStartsOnce() = runBlocking {
        withInitializedRoom { harness, reports ->
            val gate = CompletableDeferred<Unit>()
            harness.startGate = gate
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 1 }
            harness.orchestration.onForeground()
            gate.complete(Unit)
            delay(SettleMillis)
            // Serializes behind any foreground action still queued.
            harness.orchestration.initialize()
            assertEquals("the replay must not start the endpoint again", 1, harness.startCount)
            assertEquals("the replay must not strand the in-flight start", 0, harness.stopCount)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun foregroundStartFailurePublishesSyncNowNoticeNotExceptionText() = runBlocking {
        withInitializedRoom { harness, reports ->
            val failure = IOException("r54 a05 secret invite user-text")
            harness.startFailure = failure
            harness.orchestration.onForeground()
            harness.await { reports.isNotEmpty() }
            harness.orchestration.initialize()
            assertEquals(1, harness.startCount)
            assertEquals("the orchestration reports the start failure once", listOf<Throwable>(failure), reports)
            val state = harness.state.value
            assertEquals(IrohConnectionStatus.UNAVAILABLE, state.status)
            assertEquals(RecoveryMessage, state.message)
            assertFalse(state.transitioning)
            assertNull(state.identityRecovery)
        }
    }

    @Test
    fun syncNowRetriesAFailedForegroundStartWithASecondBind() = runBlocking {
        withInitializedRoom { harness, reports ->
            val failure = IOException("r54 a05 failed foreground start")
            harness.startFailure = failure
            harness.orchestration.onForeground()
            harness.await { reports.isNotEmpty() }
            harness.orchestration.onForeground()
            delay(SettleMillis)
            harness.orchestration.initialize()
            assertEquals("the replay after a failed start must not bind", 1, harness.startCount)
            harness.startFailure = null
            harness.orchestration.syncNow()
            assertEquals("Sync now binds the endpoint again", 2, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertEquals(0, harness.stopCount)
            assertEquals(listOf<Throwable>(failure), reports)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertNull(harness.state.value.message)
        }
    }

    @Test
    fun replayAfterAccountQuarantineReleaseStartsTheEndpoint() = runBlocking {
        // Quarantine moves the lifecycle to the background, so the first replay after release is
        // a real transition and binds the endpoint that the quarantine stopped.
        withInitializedRoom { harness, reports ->
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 1 }
            harness.orchestration.quarantineAccount()
            harness.orchestration.releaseAccountQuarantine()
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 2 }
            harness.orchestration.initialize()
            assertEquals(2, harness.startCount)
            assertTrue(reports.isEmpty())
        }
    }

    // The room is initialized in the background, so the first foreground owns the only start.
    private suspend fun withInitializedRoom(
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(ReplicationMode.IROH, "room-test0001")
        try {
            harness.orchestration.initialize()
            assertEquals(0, harness.startCount)
            test(harness, reports)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertTrue("the route must not change", harness.modes.isEmpty())
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previousReporter
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val RecoveryMessage = "Iroh sync could not be completed. Try again."
        const val SettleMillis = 300L
    }
}
