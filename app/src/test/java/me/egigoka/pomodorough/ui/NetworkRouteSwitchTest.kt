package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-08: while signed out the Cloud row is never ACTIVE; the route in use is On device, as the
 * header chip says, and the Cloud row offers sign-in instead. AND-11: the Iroh row without a room
 * is disabled and offers no SELECT action.
 */
// Native graphics: the first and last rows have uneven corners, and the legacy shadow path cannot
// hit-test a touch inside that clip, so a tap on them would never reach the row.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkRouteSwitchTest {
    @get:Rule val compose = createComposeRule()

    private val selectedModes = mutableListOf<ReplicationMode>()
    private var signInRequests = 0

    @Test
    fun signedOutCloudRouteIsNotActiveAndOffersSignIn() {
        render(ReplicationMode.CENTRALIZED, hasIrohRoom = false, CloudAccess.SignInOffered)

        row(R.string.cloud).assertIsNotSelected()
        row(R.string.cloud).assertDoesNotHaveText(R.string.active)
        row(R.string.cloud).assertHasText(R.string.route_sign_in)
        row(R.string.on_device).assertIsSelected()
        row(R.string.on_device).assertHasText(R.string.active)
    }

    @Test
    fun signedOutCloudRowStartsSignInInsteadOfSwitchingRoute() {
        render(ReplicationMode.OFFLINE, hasIrohRoom = false, CloudAccess.SignInOffered)

        row(R.string.cloud).performClick()

        assertEquals(1, signInRequests)
        assertEquals(emptyList<ReplicationMode>(), selectedModes)
    }

    @Test
    fun signedInCloudRouteIsActive() {
        render(ReplicationMode.CENTRALIZED, hasIrohRoom = false, CloudAccess.SignedIn)

        row(R.string.cloud).assertIsSelected()
        row(R.string.cloud).assertHasText(R.string.active)
        row(R.string.on_device).assertIsNotSelected()
    }

    @Test
    fun irohRowWithoutARoomIsDisabledAndOffersNoSelect() {
        render(ReplicationMode.OFFLINE, hasIrohRoom = false, CloudAccess.SignInOffered)

        row(R.string.iroh_room).assertIsNotEnabled()
        row(R.string.iroh_room).assertDoesNotHaveText(R.string.select)
        row(R.string.iroh_room).assertHasText(R.string.create_or_join_a_room_below_first)
    }

    @Test
    fun irohRowWithARoomCanBeSelected() {
        render(ReplicationMode.OFFLINE, hasIrohRoom = true, CloudAccess.SignInOffered)

        row(R.string.iroh_room).assertIsEnabled()
        row(R.string.iroh_room).assertHasText(R.string.select)
        row(R.string.iroh_room).performClick()
        assertEquals(listOf(ReplicationMode.IROH), selectedModes)
    }

    // The rows are tapped for real above; this pins that the tap target is not a small one.
    @Test
    fun everyRouteRowIsAtLeastTheMinimumTouchTarget() {
        render(ReplicationMode.OFFLINE, hasIrohRoom = true, CloudAccess.SignInOffered)

        listOf(R.string.on_device, R.string.iroh_room, R.string.cloud).forEach { title ->
            row(title).assertHeightIsAtLeast(48.dp)
        }
    }

    private fun render(active: ReplicationMode, hasIrohRoom: Boolean, cloudAccess: CloudAccess) {
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                RouteSwitch(active, hasIrohRoom, cloudAccess, true, { selectedModes += it }, { signInRequests++ })
            }
        }
    }

    private fun row(title: Int) = compose.onNode(hasText(string(title)) and hasClickAction())

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHasText(id: Int) =
        assert(hasText(string(id)))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotHaveText(id: Int) =
        assert(!hasText(string(id)))

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)
}
