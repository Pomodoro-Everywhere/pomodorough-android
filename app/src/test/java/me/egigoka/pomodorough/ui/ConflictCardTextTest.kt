package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, L5: AppState.conflict keeps the raw reason as a diagnostic.
 * The conflict card never shows it; it shows one authored sentence, on the
 * Timer tab and in the header of the other tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConflictCardTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun timerTabShowsTheAuthoredSentenceInsteadOfTheRawReason() {
        render(RawReason)
        assertAuthoredSentenceOnly(RawReason)
    }

    @Test
    fun tasksTabHeaderShowsTheAuthoredSentenceInsteadOfTheRawReason() {
        render(RawReason)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(hasText("Tasks") and tab).performClick()
        assertAuthoredSentenceOnly(RawReason)
    }

    @Test
    fun aRepositorySentenceThatReachesTheCardIsReplacedToo() {
        render(ImmutableConflict)
        assertAuthoredSentenceOnly(ImmutableConflict)
    }

    private fun assertAuthoredSentenceOnly(reason: String) {
        compose.onNodeWithText(reason, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(Title, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(Sentence, useUnmergedTree = true).assertExists()
    }

    private fun render(reason: String) {
        val state = AppState(ready = true, authStatus = AuthStatus.SignedOut, conflict = reason)
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
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
    }

    private companion object {
        const val RawReason = "Sync revision regressed from 5 to 4"
        const val ImmutableConflict = "Iroh room has an immutable operation conflict."
        const val Title = "Changes resolved on another device"
        const val Sentence = "Some changes made here were not applied as they were, so Pomodorough kept " +
            "the saved version; check your timer and tasks before you go on."
    }
}
