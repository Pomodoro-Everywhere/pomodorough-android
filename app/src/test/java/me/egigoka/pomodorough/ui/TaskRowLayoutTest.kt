package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.TaskDailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-15 and AND-28: on a phone the task table cut short titles into
 * fragments and ellipsized the "Finished" heading. A row now uses the table
 * only when its title column can show a typical title on one line and every
 * column can show its heading; otherwise it stacks and the column headings go
 * away. AND-29: the task board beside the timer is such a narrow column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h914dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowRowsStack() {
        assertEquals(TaskRowLayout.Stacked, taskRowLayout(379.dp, titleNeeds(200.dp), 1f))
        assertEquals(TaskRowLayout.Stacked, taskRowLayout(288.dp, titleNeeds(200.dp), 1f))
    }

    @Test
    fun wideRowsUseTheTable() {
        assertEquals(TaskRowLayout.Table, taskRowLayout(808.dp, titleNeeds(200.dp), 1f))
    }

    @Test
    fun enlargedTextAlwaysStacks() {
        assertEquals(TaskRowLayout.Stacked, taskRowLayout(808.dp, titleNeeds(200.dp), 1.3f))
    }

    // The title fits, but one heading would be cut. The row pads its content
    // 14 dp on each side.
    @Test
    fun aHeadingThatDoesNotFitStacks() {
        val content = 808.dp - 28.dp
        val fitting = titleNeeds(200.dp).copy(finished = content * TableTaskRowWeights.finishedShare)
        val wideFinished = fitting.copy(finished = fitting.finished + 1.dp)
        val wideAction = fitting.copy(action = content * TableTaskRowWeights.actionShare + 1.dp)
        assertEquals(TaskRowLayout.Table, taskRowLayout(808.dp, fitting, 1f))
        assertEquals(TaskRowLayout.Stacked, taskRowLayout(808.dp, wideFinished, 1f))
        assertEquals(TaskRowLayout.Stacked, taskRowLayout(808.dp, wideAction, 1f))
    }

    @Test
    fun columnSharesFillTheRow() {
        val weights = TableTaskRowWeights
        val total = weights.titleShare + weights.finishedShare + weights.timeShare + weights.actionShare
        assertEquals(1f, total, 0.0001f)
    }

    @Test
    fun regularPhoneStacksRowsWithoutHeadings() = assertStackedWithoutHeadings()

    @Test
    @Config(qualifiers = "en-w320dp-h569dp-port")
    fun smallPhoneStacksRowsWithoutHeadings() = assertStackedWithoutHeadings()

    @Test
    @Config(qualifiers = "en-w840dp-h1280dp-port")
    fun tabletShowsTheTableWithWholeHeadings() {
        render()
        val heading = compose.onNodeWithText("Finished", useUnmergedTree = true)
        assertFalse("Finished heading cut", heading.isCut())
        val summary = summaryBounds()
        val delete = deleteBounds()
        assertTrue("Delete beside the title", delete.center.y in summary.top..summary.bottom)
    }

    // AND-29: the task board beside the timer on a 1280x800 tablet is about
    // 630 dp wide. The title fits the table there, but "Finished" did not.
    @Test
    @Config(qualifiers = "en-w630dp-h900dp-port")
    fun boardBesideTheTimerShowsNoCutHeading() = assertStackedWithoutHeadings()

    // The title owns the row's first line; the buttons go below it.
    private fun assertStackedWithoutHeadings() {
        render()
        compose.onNodeWithText("Finished", useUnmergedTree = true).assertDoesNotExist()
        assertTrue("Delete below the title", deleteBounds().top >= summaryBounds().bottom)
    }

    // The row reads as one sentence: the title and counts are cleared into
    // one description that is not a button.
    private fun summaryBounds() = compose.onNode(
        hasContentDescription(TaskTitle, substring = true) and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
    ).fetchSemanticsNode().boundsInRoot

    private fun deleteBounds() =
        compose.onNodeWithContentDescription("Delete $TaskTitle", substring = true).fetchSemanticsNode().boundsInRoot

    // The board pads its labels and rows 16 dp from the screen edges.
    private fun render() {
        val summary = TaskDailySummary(FocusTask("task-notes", TaskTitle), 2, 50 * 60_000L)
        val actions = TaskRowActions(onDelete = {}, onBeginRename = {}, onCancelRename = {}, onRename = {})
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                Column {
                    TaskColumnLabels(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    TaskSummaryRow(summary, true, actions, Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                }
            }
        }
    }

    private fun titleNeeds(title: Dp) = TaskTableColumnNeeds(title, 0.dp, 0.dp, 0.dp)

    private companion object {
        const val TaskTitle = "Write release notes"
    }
}

// The text is cut when its whole text, laid out on one line in its own
// style, is wider than its node (the NavigationLabelsFitTest measure).
private fun SemanticsNodeInteraction.isCut(): Boolean {
    val semantics = fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    semantics.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val input = layouts.single().layoutInput
    val whole = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        .measure(input.text, input.style, maxLines = 1, softWrap = false)
    return whole.size.width > minOf(semantics.size.width, input.constraints.maxWidth)
}
