package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TaskDailySummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pencil turns a task row into a focused editor prefilled with the
 * title. Done commits a changed title with the task id; the cancel icon,
 * an unchanged title, and a blank draft all leave without a rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskRenameRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pencilOpensFocusedEditorPrefilledWithTitle() {
        val row = renderRow()

        compose.onNodeWithContentDescription("Rename Alpha").performClick()

        compose.onNode(hasSetTextAction()).assertTextContains("Alpha")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(emptyList<Pair<String, String>>(), row.renamed)
    }

    @Test
    fun doneCommitsChangedTitleWithTaskId() {
        val row = renderRow()
        compose.onNodeWithContentDescription("Rename Alpha").performClick()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Beta")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(listOf("task-alpha" to "Beta"), row.renamed)
        assertEquals(0, row.cancels)
        compose.onNodeWithContentDescription("Rename Alpha").assertExists()
    }

    @Test
    fun cancelIconEmitsNoRename() {
        val row = renderRow()
        compose.onNodeWithContentDescription("Rename Alpha").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" draft")

        compose.onNodeWithContentDescription("Cancel rename").performClick()

        assertEquals(emptyList<Pair<String, String>>(), row.renamed)
        assertEquals(1, row.cancels)
        compose.onNodeWithContentDescription("Rename Alpha").assertExists()
    }

    @Test
    fun unchangedTitleCancelsOnDone() {
        val row = renderRow()
        compose.onNodeWithContentDescription("Rename Alpha").performClick()

        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(emptyList<Pair<String, String>>(), row.renamed)
        assertEquals(1, row.cancels)
    }

    @Test
    fun blankTitleCancelsOnDone() {
        val row = renderRow()
        compose.onNodeWithContentDescription("Rename Alpha").performClick()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("   ")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(emptyList<Pair<String, String>>(), row.renamed)
        assertEquals(1, row.cancels)
    }

    private class RowHarness {
        val renamed = mutableListOf<Pair<String, String>>()
        var cancels = 0
    }

    // Mirrors the view model: the pencil opens the editor and both commit
    // and cancel close it.
    private fun renderRow(): RowHarness {
        val harness = RowHarness()
        val summary = TaskDailySummary(FocusTask("task-alpha", "Alpha"), 2, 50 * 60 * 1000L)
        compose.setContent {
            var editing by remember { mutableStateOf(false) }
            MaterialTheme {
                TaskSummaryRow(
                    summary,
                    mutationsEnabled = true,
                    actions = TaskRowActions(
                        onDelete = {},
                        onBeginRename = { editing = true },
                        onCancelRename = { editing = false; harness.cancels++ },
                        onRename = { title -> editing = false; harness.renamed += summary.task.id to title },
                    ),
                    editing = editing,
                )
            }
        }
        return harness
    }
}
