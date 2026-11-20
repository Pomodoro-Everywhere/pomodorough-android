package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-09: the Arrivals header names whose run log it shows, and the count badge speaks its
 * meaning instead of bare padded digits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class RunLogLabelTest {
    @get:Rule val compose = createComposeRule()

    private val user = User("user-1", "person@example.invalid", "Person", "")

    @Test
    fun signedOutRunLogBelongsToThisDevice() {
        val onDevice = AppState(authStatus = AuthStatus.SignedOut, network = IrohNetworkState(mode = ReplicationMode.OFFLINE))
        val signedOutCloud = onDevice.copy(network = IrohNetworkState(mode = ReplicationMode.CENTRALIZED))
        assertEquals(R.string.run_log_this_device, runLogLabel(onDevice))
        assertEquals(R.string.run_log_this_device, runLogLabel(signedOutCloud))
        assertEquals(R.string.run_log_this_device, runLogLabel(onDevice.copy(authStatus = AuthStatus.Loading)))
    }

    @Test
    fun signedInRunLogBelongsToTheAccount() {
        val state = AppState(
            authStatus = AuthStatus.SignedIn,
            user = user,
            network = IrohNetworkState(mode = ReplicationMode.CENTRALIZED),
        )
        assertEquals(R.string.run_log_this_account, runLogLabel(state))
    }

    @Test
    fun roomRunLogBelongsToTheRoom() {
        val state = AppState(
            authStatus = AuthStatus.SignedIn,
            user = user,
            network = IrohNetworkState(mode = ReplicationMode.IROH, roomId = "room-1"),
        )
        assertEquals(R.string.run_log_this_room, runLogLabel(state))
    }

    @Test
    fun countBadgeSpeaksTheArrivalCount() {
        compose.setContent {
            PomodoroughTheme(darkTheme = false) { HistoryTitle(3, R.string.run_log_this_device) }
        }

        compose.onNodeWithText("RUN LOG / THIS DEVICE").assertExists()
        compose.onNodeWithContentDescription("3 arrivals").assertExists()
        compose.onNodeWithText("03").assertDoesNotExist()
    }
}
