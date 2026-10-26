package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// R52-A06: the unweighted header wordmark starved Sign in to a 1px
// text box at 320dp/font scale 2. Renders the production AppHeader
// with real Material 3 typography at density 1 through Robolectric
// native graphics and asserts the auth label keeps measured width
// with no clipped paragraph.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R52A06HeaderReflowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun scale2RtlSignedOutKeepsSignInWidth() {
        render(AuthStatus.SignedOut, reset = false, fontScale = 2f, rtl = true)
        assertAuthFits(label(R.string.sign_in), expectEnabled = true)
    }

    @Test
    fun scale2RtlSignedInKeepsAccountWidth() {
        render(AuthStatus.SignedIn, reset = false, fontScale = 2f, rtl = true)
        assertAuthFits(label(R.string.account), expectEnabled = true)
    }

    @Test
    fun scale2RtlLoadingKeepsCheckingWidth() {
        render(AuthStatus.Loading, reset = false, fontScale = 2f, rtl = true)
        assertAuthFits(label(R.string.checking), expectEnabled = false)
    }

    @Test
    fun scale2RtlSigningInKeepsWidth() {
        render(AuthStatus.SigningIn, reset = false, fontScale = 2f, rtl = true)
        assertAuthFits(label(R.string.signing_in), expectEnabled = false)
    }

    @Test
    fun scale2RtlResetLongLabelFitsWithoutClipping() {
        render(AuthStatus.SignedOut, reset = true, fontScale = 2f, rtl = true)
        assertAuthFits(label(R.string.reset_local_account), expectEnabled = true)
    }

    @Test
    fun scale2LtrSignedOutKeepsWordmarkAndSignIn() {
        render(AuthStatus.SignedOut, reset = false, fontScale = 2f, rtl = false)
        compose.onNodeWithText(label(R.string.pomodorough)).assertIsDisplayed()
        assertAuthFits(label(R.string.sign_in), expectEnabled = true)
    }

    @Test
    fun scale2LtrResetLongLabelFits() {
        render(AuthStatus.SignedOut, reset = true, fontScale = 2f, rtl = false)
        assertAuthFits(label(R.string.reset_local_account), expectEnabled = true)
    }

    @Test
    fun scale1ControlKeepsRowLayout() {
        render(AuthStatus.SignedOut, reset = false, fontScale = 1f, rtl = false)
        compose.onNodeWithText(label(R.string.pomodorough)).assertIsDisplayed()
        assertAuthFits(label(R.string.sign_in), expectEnabled = true)
    }

    @Test
    fun signInActivatesOnceAtScale2() {
        var signs = 0
        val actions = AppHeaderActions(onSignIn = { signs++ }, onLogout = {}, onResetLocalAccount = {})
        render(AuthStatus.SignedOut, reset = false, fontScale = 2f, rtl = true, actions = actions)
        compose.onNodeWithText(label(R.string.sign_in)).performClick()
        assertTrue("sign in clicks once, was $signs", signs == 1)
    }

    @Test
    fun logoutActivatesOnceAtScale2() {
        var logouts = 0
        val actions = AppHeaderActions(onSignIn = {}, onLogout = { logouts++ }, onResetLocalAccount = {})
        render(AuthStatus.SignedIn, reset = false, fontScale = 2f, rtl = true, actions = actions)
        compose.onNodeWithText(label(R.string.account)).performClick()
        assertTrue("logout clicks once, was $logouts", logouts == 1)
    }

    private fun assertAuthFits(text: String, expectEnabled: Boolean) {
        val node = compose.onNodeWithText(text)
        node.assertIsDisplayed()
        if (expectEnabled) {
            node.assertHasClickAction()
            node.assertIsEnabled()
        } else {
            node.assertIsNotEnabled()
        }
        assertFits(text)
    }

    private fun assertFits(text: String) {
        // Defect pin: Sign in measured 1px wide with glyph lines
        // overflowing. Twenty pixels is an order of magnitude above
        // the defect and far below any fitted label.
        val layout = textLayout(text)
        assertFalse("$text exceeds lines", layout.multiParagraph.didExceedMaxLines)
        assertTrue(
            "$text paragraph ${layout.multiParagraph.height} in box ${layout.size.height}",
            layout.multiParagraph.height <= layout.size.height + 0.5f,
        )
        assertTrue(
            "$text box width ${layout.size.width} keeps measured width",
            layout.size.width >= 20,
        )
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun render(
        auth: AuthStatus,
        reset: Boolean,
        fontScale: Float,
        rtl: Boolean,
        actions: AppHeaderActions = AppHeaderActions({}, {}, {}),
    ) {
        val state = AppState(authStatus = auth, localAccountResetRequired = reset)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme { Box(Modifier.width(320.dp)) { AppHeader(state, actions) } }
            }
        }
    }

    private fun label(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)
}
