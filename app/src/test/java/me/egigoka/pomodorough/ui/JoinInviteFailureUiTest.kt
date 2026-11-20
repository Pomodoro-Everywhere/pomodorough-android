package me.egigoka.pomodorough.ui

import android.app.Application
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

/** AND-16: a join that opened no room says so under the invite field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class JoinInviteFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failedJoinShowsTheFailureUnderTheInvite() {
        render(joinInviteFailed = true)
        compose.onNodeWithText(string(R.string.room_invite_join_failed), useUnmergedTree = true).assertExists()
    }

    @Test
    fun untriedInviteShowsNoFailure() {
        render(joinInviteFailed = false)
        compose.onNodeWithText(string(R.string.room_invite_join_failed), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun render(joinInviteFailed: Boolean) {
        val state = AppState(
            authStatus = AuthStatus.SignedOut,
            network = IrohNetworkState(mode = ReplicationMode.OFFLINE),
        )
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                NetworkSection(
                    state, true,
                    NetworkActions({}, {}, {}, {}, {}, {}, {}, {}),
                    RoomNameDraftState(), "pomodorough1:broken", {},
                    joinInviteFailed = joinInviteFailed,
                )
            }
        }
    }

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)
}
