package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

// R54-A13: the network state is rendered as is, so a failed room action publishes its own bounded
// text and sends the exception, whose text may hold paths or native detail, only to the crash reporter.
class R54A13BoundedStateMessageTest {
    private val failure = IOException("native failure at /data/user/0/secret-path")

    @Test
    fun failedInviteRefreshPublishesBoundedText() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        val reported = harness.reportingCrashes {
            enterForeground()
            orchestration.createRoom("Bounded room")
            startFailure = failure
            orchestration.refreshInvite()
        }
        assertBoundedMessage(harness, "Iroh invite could not be refreshed", reported)
    }

    @Test
    fun failedLocalOperationRecoveryPublishesBoundedText() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        val reported = harness.reportingCrashes {
            captureLocalOperationsFailure = failure
            orchestration.initialize()
        }
        assertBoundedMessage(harness, "Saved Iroh operations could not be recovered", reported)
    }

    @Test
    fun createdRoomWhoseEndpointFailsPublishesBoundedText() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        val reported = harness.reportingCrashes {
            enterForeground()
            startFailure = failure
            orchestration.createRoom("Bounded room")
        }
        assertBoundedMessage(harness, "Iroh endpoint could not start", reported)
    }

    @Test
    fun failedRouteChangeOffIrohPublishesBoundedText() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        val reported = harness.reportingCrashes {
            setModeFailure = failure
            orchestration.setMode(ReplicationMode.OFFLINE)
        }
        assertEquals(ReplicationMode.CENTRALIZED, harness.state.value.mode)
        assertFalse(harness.state.value.transitioning)
        assertBoundedMessage(harness, "Replication route could not be changed", reported)
    }

    @Test
    fun failedLeaveThatRestartsTheEndpointPublishesBoundedText() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        val reported = harness.reportingCrashes {
            enterForeground()
            orchestration.createRoom("Bounded room")
            leaveFailure = failure
            orchestration.leaveRoom()
        }
        // The persisted IROH route restarts its endpoint, and the restarted state keeps the text.
        assertEquals(2, harness.startCount)
        assertEquals(ReplicationMode.IROH, harness.state.value.mode)
        assertFalse(harness.state.value.transitioning)
        assertBoundedMessage(harness, "Iroh room could not be left", reported)
    }

    private suspend fun IrohRoomOrchestrationHarness.reportingCrashes(
        scenario: suspend IrohRoomOrchestrationHarness.() -> Unit,
    ): List<Throwable> {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = { reported += it }
        try {
            scenario()
        } finally {
            CrashReporter.delegate = previous
            orchestration.close()
        }
        return reported
    }

    private fun assertBoundedMessage(
        harness: IrohRoomOrchestrationHarness,
        expected: String,
        reported: List<Throwable>,
    ) {
        assertEquals(expected, harness.state.value.message)
        assertSame(failure, reported.single())
    }
}
