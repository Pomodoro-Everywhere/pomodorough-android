package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.egigoka.pomodorough.data.AppState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-30: the launch splash and the storage-failure screen draw on Ink, so the
 * status bar and navigation bar icons are light while they show, whatever the
 * system theme picked. The timer screen gets the previous appearance back, and
 * the splash names what it waits for: the local timer, not a clock sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashSystemBarIconsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun splashKeepsSystemBarIconsLightAndRestoresThemAfterwards() {
        assertInkScreenKeepsIconsLight(AppState())
    }

    @Test
    fun storageFailureScreenKeepsSystemBarIconsLight() {
        assertInkScreenKeepsIconsLight(AppState(localStorageReadFailed = true))
    }

    @Test
    fun splashSaysItIsLoadingTheTimer() {
        compose.setContent { Screen(AppState()) }

        compose.onNodeWithText("Loading your timer").assertExists()
    }

    private fun assertInkScreenKeepsIconsLight(state: AppState) {
        compose.runOnUiThread { controller().setDarkIcons(true) }
        var startupShown by mutableStateOf(true)
        compose.setContent { if (startupShown) Screen(state) }

        compose.runOnIdle { assertEquals(false to false, controller().darkIcons()) }
        startupShown = false
        compose.runOnIdle { assertEquals(true to true, controller().darkIcons()) }
    }

    private fun controller(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)

    private fun WindowInsetsControllerCompat.setDarkIcons(dark: Boolean) {
        isAppearanceLightStatusBars = dark
        isAppearanceLightNavigationBars = dark
    }

    private fun WindowInsetsControllerCompat.darkIcons() = isAppearanceLightStatusBars to isAppearanceLightNavigationBars

    @Composable
    private fun Screen(state: AppState) = PomodoroughTheme(darkTheme = false) {
        PomodoroughScreen(
            state = state, onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
            onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {}, onSelectPhase = {},
            onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
            onAddTask = { _, _ -> }, onDeleteTask = {}, onResolveHistory = {},
            onRecoverHistoryResolution = {}, onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
            onDismissConflict = {}, onDismissNotice = {}, onSetReplicationMode = {},
            onCreateIrohRoom = {}, onJoinIrohRoom = {}, onLeaveIrohRoom = {},
            onRefreshIrohInvite = {}, onSyncIrohNow = {}, onShareIrohInvite = {},
            onRetryStartup = {},
        )
    }
}
