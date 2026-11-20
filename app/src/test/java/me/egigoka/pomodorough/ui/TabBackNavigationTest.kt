package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TaskDailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-32: system Back on a secondary tab returns to Timer instead of leaving
 * the app. Back on Timer stays with the system, and an open rename editor
 * still takes the first Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h914dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var renameCancels = 0

    @Test
    fun backFromEverySecondaryTabReturnsToTimer() {
        render()
        MainTab.entries.filter { it != MainTab.Timer }.forEach { tab ->
            openTab(tab)
            tabNode(tab).assertIsSelected()
            pressBack()
            tabNode(MainTab.Timer).assertIsSelected()
        }
    }

    @Test
    fun backOnTimerIsLeftToTheSystem() {
        render()
        tabNode(MainTab.Timer).assertIsSelected()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun backWhileRenamingCancelsTheRenameBeforeLeavingTasks() {
        render()
        openTab(MainTab.Tasks)
        compose.onNodeWithContentDescription("Rename Alpha").performClick()
        compose.onNode(renameField).assertExists()

        pressBack()
        assertEquals(1, renameCancels)
        compose.onNode(renameField).assertDoesNotExist()
        tabNode(MainTab.Tasks).assertIsSelected()

        pressBack()
        tabNode(MainTab.Timer).assertIsSelected()
    }

    // The add-task field is always editable; only the rename field holds the title.
    private val renameField = hasSetTextAction() and hasText("Alpha")

    private fun render() {
        val summary = TaskDailySummary(FocusTask("task-alpha", "Alpha"), 0, 0L)
        val state = AppState(
            ready = true, authStatus = AuthStatus.SignedOut,
            tasks = listOf(summary.task), taskSummaries = listOf(summary),
        )
        compose.setContent {
            var editing by remember { mutableStateOf<String?>(null) }
            PomodoroughTheme(darkTheme = false) {
                StaticPomodoroughScreen(
                    state,
                    TaskEditingActions(
                        editingTaskId = editing,
                        onBeginRename = { editing = it },
                        onCancelRename = { editing = null; renameCancels++ },
                    ),
                )
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openTab(tab: MainTab) = tabNode(tab).performClick()

    private fun tabNode(tab: MainTab) = compose.onNode(hasText(label(tab)) and isTab)

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun label(tab: MainTab): String =
        ApplicationProvider.getApplicationContext<Application>().getString(tab.labelRes)
}
