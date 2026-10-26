package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class R52A04RefreshInviteTest {
    @Test
    fun successfulRefreshPublishesInviteAndKeepsStoredRoom() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            val roomId = harness.installValidRoom()
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            harness.orchestration.refreshInvite()
            assertNotNull(harness.state.value.invite)
            assertEquals(roomId, harness.room?.roomId)
            assertEquals(roomId, harness.settings.activeRoomId)
        } finally {
            harness.orchestration.close()
        }
    }

    @Test
    fun endpointFailureReportsRecoverableAndKeepsStoredRoom() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            val roomId = harness.installValidRoom()
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            harness.orchestration.refreshInvite()
            val beforeInvite = harness.state.value.invite
            assertNotNull(beforeInvite)
            harness.startFailure = IOException("endpoint restart failed")
            harness.orchestration.refreshInvite()
            assertEquals(1, reported.size)
            assertTrue(reported.single() is IOException)
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertTrue(harness.state.value.message?.isNotBlank() == true)
            assertEquals(roomId, harness.room?.roomId)
            assertEquals(roomId, harness.settings.activeRoomId)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun retryAfterEndpointRestoreSucceeds() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            val roomId = harness.installValidRoom()
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            harness.startFailure = IOException("endpoint restart failed")
            harness.orchestration.refreshInvite()
            assertEquals(1, reported.size)
            harness.startFailure = null
            harness.orchestration.refreshInvite()
            assertNotNull(harness.state.value.invite)
            assertEquals(roomId, harness.room?.roomId)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun vaultFailureStaysSilentAndQuarantinesRecovery() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            harness.startFailure =
                IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
            harness.orchestration.refreshInvite()
            assertTrue(reported.isEmpty())
            assertEquals(
                IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
                harness.state.value.identityRecovery,
            )
            assertEquals("room-test0001", harness.room?.roomId)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    @Test
    fun cancellationPropagatesWithoutReportOrUnavailable() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            val cancellation = CancellationException("gone")
            harness.startFailure = cancellation
            val failure = runCatching {
                harness.orchestration.refreshInvite()
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
    fun missingRoomReportsRecoverableWithoutThrow() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )
        try {
            harness.orchestration.initialize()
            harness.room = null
            harness.orchestration.refreshInvite()
            assertEquals(1, reported.size)
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
        } finally {
            CrashReporter.delegate = previous
            harness.orchestration.close()
        }
    }

    private fun IrohRoomOrchestrationHarness.installValidRoom(): String {
        val secret = checkNotNull(roomSecret)
        val roomId = IrohProtocolV1.roomId(secret)
        room = IrohRoomEntity(
            roomId = roomId,
            roomName = "Test room",
            encryptedRoomSecret = byteArrayOf(1, 2, 3),
            returnStateJson = "return",
            roomStateJson = "room",
            createdAtMs = 1,
            activated = true,
        )
        settings = settings.copy(activeRoomId = roomId)
        snapshot = snapshot.copy(roomId = roomId, roomName = "Test room")
        return roomId
    }
}
