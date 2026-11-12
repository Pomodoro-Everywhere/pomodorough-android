package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.NoticeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-17: a failure notice that offers a retry shows a Retry button next to
 * Dismiss, and names the same retry as a screen-reader action on the card.
 * UX round 2, L1: Retry does not dismiss the notice; the repository replaces
 * it when the retry has an outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeRetryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    @Test
    fun signInFailureRetrySignsInAndKeepsTheNotice() {
        render(NoticeAction.RetrySignIn)
        retryButton().performClick()
        assertEquals(listOf("signIn"), calls)
    }

    @Test
    fun restoreFailureRetryRefreshesAndKeepsTheNotice() {
        render(NoticeAction.RetryAccountRestore)
        retryButton().performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun retryIsANamedScreenReaderActionOnTheCard() {
        render(NoticeAction.RetrySignIn)
        val retry = cardActions().single()
        assertEquals("Retry", retry.label)
        compose.runOnUiThread { retry.action() }
        assertEquals(listOf("signIn"), calls)
    }

    @Test
    fun plainNoticeOffersDismissOnly() {
        render(action = null)
        compose.onNodeWithText("Dismiss", useUnmergedTree = true).assertExists()
        retryButton().assertDoesNotExist()
        assertEquals(emptyList<CustomAccessibilityAction>(), cardActions())
    }

    // UX round 2, B3: a notice with Retry waits for the user's answer.
    @Test
    fun retryNoticeStaysUntilTheUserAnswers() {
        render(NoticeAction.RetrySignIn)
        compose.mainClock.advanceTimeBy(60_000)
        assertEquals("60 s without a tap", emptyList<String>(), calls)
        retryButton().performClick()
        assertEquals(listOf("signIn"), calls)
    }

    @Test
    fun plainNoticeStillGoesAwayAfterTheDisplayTime() {
        render(action = null)
        compose.mainClock.advanceTimeBy(6_000)
        assertEquals("6 s", emptyList<String>(), calls)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("8 s", listOf("dismissNotice"), calls)
    }

    // The card is one node for a screen reader; its buttons are in the unmerged tree.
    private fun retryButton() = compose.onNodeWithText("Retry", useUnmergedTree = true)

    private fun cardActions(): List<CustomAccessibilityAction> =
        compose.onNode(hasContentDescription("Heads up. $Sentence")).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun render(action: NoticeAction?) {
        val state = AppState(
            ready = true,
            authStatus = AuthStatus.SignedOut,
            notice = Sentence,
            noticeAction = action,
        )
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                PomodoroughScreen(
                    state = state, onSignIn = { calls += "signIn" }, onLogout = {}, onResetLocalAccount = {},
                    onRefresh = { calls += "refresh" },
                    onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {}, onSelectPhase = {},
                    onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
                    onAddTask = { _, _ -> }, onDeleteTask = {}, onResolveHistory = {},
                    onRecoverHistoryResolution = {}, onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
                    onDismissConflict = {}, onDismissNotice = { calls += "dismissNotice" },
                    onSetReplicationMode = {},
                    onCreateIrohRoom = {}, onJoinIrohRoom = {}, onLeaveIrohRoom = {},
                    onRefreshIrohInvite = {}, onSyncIrohNow = {}, onShareIrohInvite = {},
                    onRetryStartup = {},
                )
            }
        }
    }

    private companion object {
        const val Sentence = "Could not reach the server. Check your connection, then try again."
    }
}
