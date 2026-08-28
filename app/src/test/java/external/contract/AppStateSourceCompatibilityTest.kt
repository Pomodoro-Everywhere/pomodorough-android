package external.contract

import me.egigoka.pomodorough.data.AccountStatus
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppStateSourceCompatibilityTest {
    @Test
    fun legacyPositionalConstructorAndComponentsKeepTheirOriginalTypesAndPositions() {
        val network = IrohNetworkState(peerCount = 2)
        val state = AppState(
            true,
            AuthStatus.SignedIn,
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            "task",
            TimerSettings(),
            3,
            SyncStatus.Queued,
            null,
            null,
            "conflict",
            "notice",
            "device",
            network,
        )

        val user: User? = state.component3()
        val timer: CanonicalTimer? = state.component4()
        val history: List<HistoryItem> = state.component5()
        val deviceId: String = state.component17()
        val legacyNetwork: IrohNetworkState = state.component18()

        assertNull(user)
        assertNull(timer)
        assertEquals(emptyList<HistoryItem>(), history)
        assertEquals("device", deviceId)
        assertEquals(network, legacyNetwork)
        assertFalse(state.localAccountResetRequired)
        assertNull(state.completionAlertTimerId)
        assertEquals(AccountStatus.Available, state.accountStatus)
    }

    @Test
    fun resetRequirementHasNamedAccountStatusWithoutChangingConstructorShape() {
        val state = AppState(localAccountResetRequired = true)

        assertEquals(AccountStatus.LocalResetRequired, state.accountStatus)
    }
}
