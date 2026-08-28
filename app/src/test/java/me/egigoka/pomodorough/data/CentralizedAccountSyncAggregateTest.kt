package me.egigoka.pomodorough.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralizedAccountSyncAggregateTest {
    @Test
    fun runtimeSnapshotComesFromOneAccountAndSyncStateOwner() {
        val aggregate = CentralizedAccountSyncAggregate(AccountWorkspaceEventSink {})
        aggregate.authStatus = AuthStatus.SignedIn
        aggregate.syncing = true
        aggregate.retrying = true
        aggregate.historyResolution = HistoryResolutionState(1, 2, corrupted = false)
        aggregate.accountSwitch = AccountSwitchState("local@example.com", "other@example.com")
        aggregate.terminalSyncError = "stopped"

        val snapshot = aggregate.runtimeSnapshot(
            centralized = true,
            pendingQueuesEmpty = false,
            localRevision = 7,
        )

        assertTrue(snapshot.signedIn)
        assertTrue(snapshot.centralized)
        assertTrue(snapshot.resolutionPending)
        assertTrue(snapshot.accountSwitchPending)
        assertTrue(snapshot.terminalSyncError)
        assertFalse(snapshot.pendingQueuesEmpty)
        assertEquals(7, snapshot.localRevision)
    }

    @Test
    fun clearSessionStateRetainsWorkspaceGenerationButClearsAccountAndSyncFlags() {
        val aggregate = CentralizedAccountSyncAggregate(AccountWorkspaceEventSink {})
        aggregate.user = User("user-1", "u@example.com", "User", "")
        aggregate.authStatus = AuthStatus.SignedIn
        aggregate.syncing = true
        aggregate.retrying = true
        aggregate.terminalSyncError = "stopped"

        aggregate.clearSessionState(AuthStatus.SignedOut)

        assertNull(aggregate.user)
        assertEquals(AuthStatus.SignedOut, aggregate.authStatus)
        assertFalse(aggregate.syncing)
        assertFalse(aggregate.retrying)
        assertNull(aggregate.terminalSyncError)
    }
}
