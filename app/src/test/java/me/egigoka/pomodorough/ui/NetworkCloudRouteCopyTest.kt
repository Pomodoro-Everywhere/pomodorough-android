package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Cloud route card must not describe a signed-in server while the account is
 * signed out: the header says "On device" in that state, so the card asks for sign-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkCloudRouteCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun signedOutCloudRouteAsksForSignIn() {
        renderNetwork(AuthStatus.SignedOut)
        compose.onNodeWithText(string(R.string.sign_in_to_sync_across_devices)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.signed_in_server)).assertDoesNotExist()
    }

    @Test
    fun loadingAccountStillAsksForSignIn() {
        renderNetwork(AuthStatus.Loading)
        compose.onNodeWithText(string(R.string.sign_in_to_sync_across_devices)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.signed_in_server)).assertDoesNotExist()
    }

    @Test
    fun signedInCloudRouteNamesTheServer() {
        renderNetwork(AuthStatus.SignedIn)
        compose.onNodeWithText(string(R.string.signed_in_server)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sign_in_to_sync_across_devices)).assertDoesNotExist()
    }

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    private fun renderNetwork(authStatus: AuthStatus) {
        val state = AppState(
            authStatus = authStatus,
            network = IrohNetworkState(mode = ReplicationMode.CENTRALIZED),
        )
        compose.setContent {
            MaterialTheme {
                NetworkSection(
                    state, true,
                    NetworkActions({}, {}, {}, {}, {}, {}, {}, {}),
                    RoomNameDraftState(), "", {},
                )
            }
        }
    }
}
