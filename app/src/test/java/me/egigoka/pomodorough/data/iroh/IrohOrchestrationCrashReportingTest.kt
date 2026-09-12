package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun setModeCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            val cancellation = CancellationException("gone")
            harness.setModeFailure = cancellation
            val failure = runCatching {
                harness.orchestration.setMode(ReplicationMode.IROH)
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun createRoomCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            val cancellation = CancellationException("gone")
            harness.createRoomFailure = cancellation
            val failure = runCatching {
                harness.orchestration.createRoom("Room")
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun joinRoomCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            val cancellation = CancellationException("gone")
            harness.prepareJoinedRoomFailure = cancellation
            val failure = runCatching {
                harness.orchestration.joinRoom(harness.invite())
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun leaveRoomCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            val cancellation = CancellationException("gone")
            harness.leaveFailure = cancellation
            val failure = runCatching {
                harness.orchestration.leaveRoom()
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun recoverLocalOperationsCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            val cancellation = CancellationException("gone")
            harness.captureLocalOperationsFailure = cancellation
            val failure = runCatching {
                harness.orchestration.initialize()
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun recoverLocalOperationsFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.captureLocalOperationsFailure = RuntimeException("ops exploded")
            harness.orchestration.initialize()
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun recoverLocalOperationsVaultStaysSilentAndQuarantines() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.captureLocalOperationsFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
            harness.orchestration.initialize()
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED,
                harness.state.value.identityRecovery,
            )
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun rollbackDiscardCancellationPropagatesWithoutUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.startFailure = RuntimeException("join exploded")
            val cancellation = CancellationException("discard cancelled")
            harness.discardInactiveFailure = cancellation
            val failure = runCatching {
                harness.orchestration.joinRoom(harness.invite())
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(reported.isEmpty())
            assertTrue(harness.state.value.status != IrohConnectionStatus.UNAVAILABLE)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun rollbackDiscardOrdinaryFailureStillSurfacesJoinUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.startFailure = RuntimeException("join exploded")
            harness.discardInactiveFailure = RuntimeException("discard exploded")
            harness.orchestration.joinRoom(harness.invite())
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun setModeFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.setModeFailure = RuntimeException("mode exploded")
            harness.orchestration.setMode(ReplicationMode.IROH)
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun setModeVaultStaysSilentAndQuarantines() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.setModeFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
            harness.orchestration.setMode(ReplicationMode.IROH)
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
                harness.state.value.identityRecovery,
            )
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun createRoomFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.createRoomFailure = RuntimeException("create exploded")
            harness.orchestration.createRoom("Room")
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun joinRoomFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.prepareJoinedRoomFailure = RuntimeException("prepare exploded")
            harness.orchestration.joinRoom(harness.invite())
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun leaveRoomFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            harness.leaveFailure = RuntimeException("leave exploded")
            harness.orchestration.leaveRoom()
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(1, reported.size)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun createRoomVaultStaysSilentAndQuarantines() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.createRoomFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
            harness.orchestration.createRoom("Room")
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED,
                harness.state.value.identityRecovery,
            )
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun joinRoomVaultStaysSilentAndQuarantines() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.orchestration.initialize()
            harness.prepareJoinedRoomFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
            harness.orchestration.joinRoom(harness.invite())
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
                harness.state.value.identityRecovery,
            )
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun leaveRoomVaultStaysSilentAndQuarantines() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            harness.leaveFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
            harness.orchestration.leaveRoom()
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED,
                harness.state.value.identityRecovery,
            )
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }
}
