package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.egigoka.pomodorough.data.FocusTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The undo window on the production screen. Leaving the Tasks tab ends the
 * window and commits the delete, and the snackbar, which has no time limit,
 * never keeps the last task rows out of reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskUndoWindowScreenTest {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }
    private lateinit var viewModel: PomodoroughViewModel

    @Test
    fun leavingTasksTabCommitsDeleteAndClosesSnackbar() {
        val repository = render(twoTasks)
        openTab("Tasks")
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        compose.onNodeWithText("Deleted “Alpha”").assertExists()
        assertEquals(emptyList<String>(), repository.deletions)

        openTab("Timer")
        compose.waitForIdle()

        assertEquals(listOf("task-alpha"), repository.deletions)
        compose.onNodeWithText("Deleted “Alpha”").assertDoesNotExist()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun undoSnackbarStaysOnTasksTabUntilUndo() {
        val repository = render(twoTasks)
        openTab("Tasks")
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        // Settle the click so the snackbar is up before the clock jumps.
        compose.onNodeWithText("Deleted “Alpha”").assertExists()

        compose.mainClock.advanceTimeBy(LongWait)
        compose.waitForIdle()

        assertEquals(emptyList<String>(), repository.deletions)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Delete Alpha").assertExists()
        assertEquals(emptyList<String>(), repository.deletions)
    }

    @Test
    fun lastTaskRowScrollsClearOfUndoSnackbar() {
        render(manyTasks)
        openTab("Tasks")
        compose.onNodeWithContentDescription("Delete Task 1").performClick()
        compose.onNodeWithText("Deleted “Task 1”").assertExists()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(LastListIndex)
        compose.waitForIdle()

        val lastRowBottom = compose.onNodeWithContentDescription("Delete Task $TaskCount")
            .fetchSemanticsNode().boundsInRoot.bottom
        val snackbarTop = compose.onNode(snackbar).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "last row Delete ends at $lastRowBottom px, under the snackbar that starts at $snackbarTop px",
            lastRowBottom <= snackbarTop,
        )
    }

    // UX round 2, B4: rotation stops the activity and rebuilds the screen, but
    // it is not a real stop. The delete stays undoable.
    @Test
    fun rotationKeepsTheUndoWindowAndARealStopEndsIt() {
        val repository = render(twoTasks)
        openTab("Tasks")
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        compose.onNodeWithText("Deleted “Alpha”").assertExists()

        viewModel.endUndoWindowOnStop(changingConfigurations = true)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals("deletes committed by a rotation", emptyList<String>(), repository.deletions)
        compose.onNodeWithText("Deleted “Alpha”").assertExists()
        compose.onNodeWithText("Undo").assertExists()

        viewModel.endUndoWindowOnStop(changingConfigurations = false)
        assertEquals(listOf("task-alpha"), repository.deletions)
    }

    // UX round 2, B4: the deleted row held the keyboard focus. Focus goes to
    // Undo instead of falling back to the first control of the screen.
    @Test
    fun keyboardDeleteMovesFocusToUndo() {
        render(twoTasks, inputMode = InputMode.Keyboard)
        openTab("Tasks")
        val delete = compose.onNodeWithContentDescription("Delete Alpha")
        delete.requestFocus()
        delete.assertIsFocused()

        delete.performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Undo") and hasClickAction()).assertIsFocused()
    }

    private fun openTab(label: String) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(hasText(label) and tab).performClick()
    }

    // Wires the real view model the way MainActivity does.
    private fun render(
        tasks: List<FocusTask>,
        inputMode: InputMode = InputMode.Touch,
    ): DeletionRecordingRepository {
        val repository = DeletionRecordingRepository(tasks)
        viewModel = PomodoroughViewModel(repository)
        restoration.setContent {
            CompositionLocalProvider(LocalInputModeManager provides FixedInputMode(inputMode)) { Screen() }
        }
        return repository
    }

    @Composable
    private fun Screen() {
        val state by viewModel.state.collectAsState()
        PomodoroughTheme(darkTheme = false) {
            PomodoroughScreen(
                state = state, onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
                onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {}, onSelectPhase = {},
                onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
                onAddTask = { _, _ -> }, onDeleteTask = viewModel::requestTaskDeletion, onResolveHistory = {},
                onRecoverHistoryResolution = {}, onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
                onDismissConflict = {}, onDismissNotice = {}, onSetReplicationMode = {},
                onCreateIrohRoom = {}, onJoinIrohRoom = {}, onLeaveIrohRoom = {},
                onRefreshIrohInvite = {}, onSyncIrohNow = {}, onShareIrohInvite = {},
                onRetryStartup = {},
                taskEditing = TaskEditingActions(
                    pendingDeletion = viewModel.pendingTaskDeletion,
                    onUndoDeletion = viewModel::undoTaskDeletion,
                    onConfirmDeletion = viewModel::confirmTaskDeletion,
                ),
            )
        }
    }

    private class FixedInputMode(override val inputMode: InputMode) : InputModeManager {
        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == this.inputMode
    }

    private companion object {
        // Six times the old ten-second snackbar timeout.
        const val LongWait = 60_000L
        const val TaskCount = 12

        // App header, board header and column labels come before the rows.
        // One row is hidden while its deletion is pending.
        const val LastListIndex = 3 + TaskCount - 2
        val twoTasks = listOf(FocusTask("task-alpha", "Alpha"), FocusTask("task-beta", "Beta"))
        val manyTasks = (1..TaskCount).map { FocusTask("task-$it", "Task $it") }
        val snackbar = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and
            hasAnyDescendant(hasText("Undo"))
    }
}
