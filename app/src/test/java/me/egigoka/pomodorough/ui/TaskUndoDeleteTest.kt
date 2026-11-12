package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.PendingTaskDeletion
import me.egigoka.pomodorough.data.PendingTaskDeletionMarkerStore
import me.egigoka.pomodorough.data.PendingTaskDeletionWindow
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deleting a task hides its row and offers Undo with no time limit. Undo
 * restores the row without touching the repository. Dismiss, a second
 * delete, or leaving the Tasks screen commits exactly one delete through
 * the view model's deferred-delete flush.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskUndoDeleteTest {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val compose = createComposeRule()

    @Test
    fun deleteHidesRowAndShowsUndoSnackbarWithoutRepositoryCall() {
        val repository = render()

        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        compose.onNodeWithText("Deleted “Alpha”").assertExists()
        compose.onNodeWithText("Undo").assertExists()
        compose.onNodeWithContentDescription("Delete Alpha").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Beta").assertExists()
        assertEquals(emptyList<String>(), repository.deletions)
    }

    @Test
    fun undoRestoresRowWithoutRepositoryCall() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        compose.onNodeWithText("Undo").performClick()

        compose.onNodeWithContentDescription("Delete Alpha").assertExists()
        compose.onNodeWithText("Deleted “Alpha”").assertDoesNotExist()
        assertEquals(emptyList<String>(), repository.deletions)
    }

    // The snackbar once timed out after ten seconds and committed the delete.
    @Test
    fun undoStaysAvailableWithNoTimeLimit() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        compose.onNodeWithText("Deleted “Alpha”").assertExists()

        compose.mainClock.advanceTimeBy(LongWait)
        compose.waitForIdle()

        assertEquals(emptyList<String>(), repository.deletions)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Delete Alpha").assertExists()
        assertEquals(emptyList<String>(), repository.deletions)
    }

    // UX round 2: the second button deletes at once, so it is labelled for
    // that, in words, and no close icon named "Dismiss" remains.
    @Test
    fun secondButtonIsLabelledDeleteNow() {
        render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        compose.onNode(dismissAction).assertExists().assertHasClickAction()
        compose.onNode(hasContentDescription("Dismiss", substring = true)).assertDoesNotExist()
    }

    @Test
    fun dismissActionDeletesExactlyOnce() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        compose.onNode(dismissAction).assertHasClickAction().performClick()
        compose.waitForIdle()

        assertEquals(listOf("task-alpha"), repository.deletions)
        compose.mainClock.advanceTimeBy(LongWait)
        compose.waitForIdle()
        assertEquals(listOf("task-alpha"), repository.deletions)
        compose.onNodeWithText("Deleted “Alpha”").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Alpha").assertDoesNotExist()
    }

    @Test
    fun secondDeleteFlushesFirstThenDefersNew() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        compose.onNodeWithContentDescription("Delete Beta").performClick()

        assertEquals(listOf("task-alpha"), repository.deletions)
        compose.onNodeWithText("Deleted “Beta”").assertExists()
        compose.onNodeWithText("Deleted “Alpha”").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Alpha").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Beta").assertDoesNotExist()

        compose.onNode(dismissAction).performClick()
        compose.waitForIdle()
        assertEquals(listOf("task-alpha", "task-beta"), repository.deletions)
    }

    @Test
    fun leavingTasksScreenDeletesExactlyOnceAndClosesSnackbar() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        compose.onNodeWithText("Deleted “Alpha”").assertExists()

        onTasksScreen.value = false
        compose.waitForIdle()

        assertEquals(listOf("task-alpha"), repository.deletions)
        compose.onNodeWithText("Deleted “Alpha”").assertDoesNotExist()
        onTasksScreen.value = true
        compose.waitForIdle()
        assertEquals(listOf("task-alpha"), repository.deletions)
    }

    // Screen readers hear the snackbar when it appears: the host marks it
    // as a polite live region, and Undo is a labelled button inside it.
    @Test
    fun undoSnackbarIsAnnouncedAsLiveRegion() {
        render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()

        val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNode(liveRegion and hasAnyDescendant(hasText("Deleted “Alpha”"))).assertExists()
        compose.onNode(hasText("Undo") and hasClickAction() and hasAnyAncestor(liveRegion)).assertExists()
    }

    // UX round 2: the window lives in the repository, so a screen that is
    // built again shows it, and sign-out counts it as work that has not synced.
    @Test
    fun aRebuiltScreenShowsTheWindowTheRepositoryHolds() {
        val repository = render()
        compose.onNodeWithContentDescription("Delete Alpha").performClick()
        compose.waitForIdle()

        val rebuilt = PomodoroughViewModel(repository).pendingTaskDeletion

        assertEquals(PendingTaskDeletion("task-alpha", "Alpha"), rebuilt)
        assertEquals(listOf(3, 2), listOf(signOutPendingWork(2, rebuilt), signOutPendingWork(2, null)))
    }

    // The real view model drives a Scaffold-hosted snackbar and the same
    // visible-summary filter the task tab uses.
    private val onTasksScreen = mutableStateOf(true)

    private fun render(): DeletionRecordingRepository {
        val repository = DeletionRecordingRepository()
        val viewModel = PomodoroughViewModel(repository)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            val host = remember { SnackbarHostState() }
            MaterialTheme {
                Scaffold(snackbarHost = { UndoSnackbarHost(host) }) { padding ->
                    TaskDeletionUndoSnackbar(
                        viewModel.pendingTaskDeletion,
                        host,
                        viewModel::undoTaskDeletion,
                        viewModel::confirmTaskDeletion,
                        onTasksScreen = onTasksScreen.value,
                    )
                    Column(Modifier.padding(padding)) {
                        visibleTaskSummaries(state.taskSummaries, viewModel.pendingTaskDeletion).forEach { summary ->
                            TaskSummaryRow(
                                summary,
                                mutationsEnabled = true,
                                actions = TaskRowActions(onDelete = { viewModel.requestTaskDeletion(summary.task.id) }),
                            )
                        }
                    }
                }
            }
        }
        return repository
    }

    private companion object {
        // Six times the old ten-second snackbar timeout.
        const val LongWait = 60_000L

        // UX round 2: the app's own "Delete now" button replaced the Material
        // close icon, whose label Robolectric resolved from a pseudolocale.
        val dismissAction = hasText("Delete now")
    }
}

internal class DeletionRecordingRepository(
    private val tasks: List<FocusTask> = listOf(FocusTask("task-alpha", "Alpha"), FocusTask("task-beta", "Beta")),
) : TimerRepositoryContract {
    private val mutableState = MutableStateFlow(
        AppState(
            ready = true,
            authStatus = AuthStatus.SignedOut,
            tasks = tasks,
            taskSummaries = tasks.map { TaskDailySummary(it, 0, 0L) },
        ),
    )
    override val state: StateFlow<AppState> = mutableState
    val deletions = mutableListOf<String>()

    // The repository owns the undo window. This double runs the real window
    // over real preferences and records the delete that ends it.
    private val window = PendingTaskDeletionWindow(
        PendingTaskDeletionMarkerStore(
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(PendingTaskDeletionMarkerStore.PreferencesName, Context.MODE_PRIVATE),
        ),
    )
    override val pendingTaskDeletion: StateFlow<PendingTaskDeletion?> get() = window.pending

    override suspend fun requestTaskDeletion(taskId: String) {
        completePendingTaskDeletion()
        val task = state.value.tasks.firstOrNull { it.id == taskId } ?: return
        window.open(task, workspaceOwner = "undo-window-double")
    }

    override suspend fun undoTaskDeletion() = window.undo()

    override suspend fun completePendingTaskDeletion() {
        val pending = window.take() ?: return
        deleteTask(pending.taskId)
        window.forget(pending.marker)
    }

    override suspend fun deleteTask(taskId: String) {
        deletions += taskId
        mutableState.update { current ->
            current.copy(
                tasks = current.tasks.filter { it.id != taskId },
                taskSummaries = current.taskSummaries.filter { it.task.id != taskId },
            )
        }
    }

    override suspend fun initialize() = Unit
    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) = Unit
    override suspend fun logout() = Unit
    override suspend fun deleteAccount(confirmation: String) = Unit
    override fun refresh() = Unit
    override suspend fun toggleTimer() = Unit
    override suspend fun finishTimer() = Unit
    override suspend fun cancelAndClearTimer() = Unit
    override suspend fun clearTimer() = Unit
    override suspend fun selectPhase(phase: String) = Unit
    override suspend fun changeDuration(phase: String, delta: Int) = Unit
    override suspend fun setAutoStart(enabled: Boolean) = Unit
    override suspend fun selectTask(taskId: String?) = Unit
    override suspend fun addTask(title: String) = true
    override suspend fun finishExpiredTimer() = false
    override suspend fun consumeCompletionAlert(
        timerId: String,
        notify: suspend (timerId: String) -> Boolean,
    ) = false
    override suspend fun resolveHistory(strategy: BootstrapStrategy) = Unit
    override suspend fun recoverCorruptedResolution() = Unit
    override suspend fun confirmAccountSwitch() = Unit
    override suspend fun cancelAccountSwitch() = Unit
    override fun dismissConflict() = Unit
    override fun dismissNotice() = Unit
    override suspend fun setReplicationMode(mode: ReplicationMode) = Unit
    override suspend fun createIrohRoom(name: String) = Unit
    override suspend fun joinIrohRoom(invite: String) = Unit
    override suspend fun leaveIrohRoom() = Unit
    override suspend fun refreshIrohInvite() = Unit
    override suspend fun syncIrohNow() = Unit
    override fun onForeground() = Unit
    override fun onBackground() = Unit
}
