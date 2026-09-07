package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohOrchestrationCrashReportingTest {
    @Test
    fun unexpectedEndpointStartFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        harness.startFailure = RuntimeException("endpoint exploded")
        try {
            harness.orchestration.initialize()
            harness.orchestration.onForeground()
            harness.await { harness.startCount > 0 }
            harness.await { reported.isNotEmpty() }
            assertEquals(1, reported.size)
            assertTrue(harness.state.value.message?.isNotBlank() == true)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun vaultStartFailureStaysSilentAndQuarantinesRecovery() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        harness.startFailure =
            IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
        try {
            harness.orchestration.initialize()
            harness.orchestration.onForeground()
            harness.await { harness.startCount > 0 }
            harness.await {
                harness.state.value.identityRecovery ==
                    IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING
            }
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }
}
