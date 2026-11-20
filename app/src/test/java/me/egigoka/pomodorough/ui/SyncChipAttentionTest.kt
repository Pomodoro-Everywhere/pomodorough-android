package me.egigoka.pomodorough.ui

import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AND-04: the header chip colour follows the same route and account state as its label. A chip
 * that reads "On device" never takes the attention colour from a stale account sync status.
 */
class SyncChipAttentionTest {
    private val user = User("user-1", "person@example.invalid", "Person", "")

    @Test
    fun onDeviceRouteIsCalmWhateverTheAccountSyncStatus() {
        SyncStatus.entries.forEach { status ->
            val state = AppState(syncStatus = status, network = IrohNetworkState(mode = ReplicationMode.OFFLINE))
            assertFalse("On device with $status", syncNeedsAttention(state))
        }
    }

    @Test
    fun signedOutCloudRouteReadsOnDeviceAndIsCalm() {
        listOf(SyncStatus.Queued, SyncStatus.Retrying, SyncStatus.Checking).forEach { status ->
            val state = AppState(authStatus = AuthStatus.SignedOut, syncStatus = status, network = cloud())
            assertFalse("signed out Cloud with $status", syncNeedsAttention(state))
        }
    }

    @Test
    fun accountWorkInProgressNeedsAttention() {
        assertTrue(syncNeedsAttention(AppState(authStatus = AuthStatus.Loading, network = cloud())))
        assertTrue(syncNeedsAttention(AppState(authStatus = AuthStatus.SigningIn, network = cloud())))
    }

    @Test
    fun signedInCloudFollowsTheSyncStatus() {
        fun signedIn(status: SyncStatus) =
            AppState(authStatus = AuthStatus.SignedIn, user = user, syncStatus = status, network = cloud())
        assertFalse(syncNeedsAttention(signedIn(SyncStatus.Synced)))
        assertFalse(syncNeedsAttention(signedIn(SyncStatus.Offline)))
        assertTrue(syncNeedsAttention(signedIn(SyncStatus.Queued)))
        assertTrue(syncNeedsAttention(signedIn(SyncStatus.Conflict)))
    }

    @Test
    fun roomRouteFollowsTheRoomConnection() {
        fun room(status: IrohConnectionStatus) = AppState(
            authStatus = AuthStatus.SignedIn,
            user = user,
            syncStatus = SyncStatus.Queued,
            network = IrohNetworkState(mode = ReplicationMode.IROH, status = status, roomId = "room-1"),
        )
        assertFalse(syncNeedsAttention(room(IrohConnectionStatus.LISTENING)))
        assertFalse(syncNeedsAttention(room(IrohConnectionStatus.WAITING_FOR_PEERS)))
        assertTrue(syncNeedsAttention(room(IrohConnectionStatus.SYNCING)))
        assertTrue(syncNeedsAttention(room(IrohConnectionStatus.UNAVAILABLE)))
    }

    private fun cloud() = IrohNetworkState(mode = ReplicationMode.CENTRALIZED)
}
