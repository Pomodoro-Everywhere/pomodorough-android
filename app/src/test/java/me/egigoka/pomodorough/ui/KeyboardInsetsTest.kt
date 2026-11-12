package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.ComponentName
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, B6 (AND-14): the keyboard resizes the content and does not pan
 * the window under the status bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w320dp-h569dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theWindowResizesForTheKeyboardInsteadOfPanning() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        val adjust = activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjust)
    }

    @Test
    fun contentEndsAtTheTopOfTheKeyboard() {
        val view = renderContent()
        val rootHeight = compose.onRoot().getBoundsInRoot().height
        assertEquals(rootHeight - NavigationBarHeight, contentHeight())

        showKeyboard(view, KeyboardHeightPx)

        // mdpi: 1 px is 1 dp. The navigation bar padding is not added to the keyboard.
        assertEquals(rootHeight - KeyboardHeightPx.dp, contentHeight())
    }

    private fun contentHeight() = compose.onNodeWithTag(ContentTag).getBoundsInRoot().height

    private fun renderContent(): View {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().scaffoldContentAboveKeyboard(PaddingValues(bottom = NavigationBarHeight))) {
                Box(Modifier.fillMaxSize().testTag(ContentTag))
            }
        }
        return view
    }

    private fun showKeyboard(view: View, heightPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private companion object {
        const val ContentTag = "content"
        const val KeyboardHeightPx = 250
        val NavigationBarHeight = 80.dp
    }
}
