package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND-23: at font scale 2.0 the task name of an Arrivals row broke in the middle
 * of a word ("infrastructur/e") because the duration kept its place beside it.
 * The duration now moves below the details when the longest word would not fit,
 * and stays beside them when it does. The audit device was 411 dp wide; the row
 * here fills that width, so its title has a longer word than the audit's to
 * overflow the space beside the duration at font scale 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h923dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryRowWordWrapTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun taskNameKeepsWholeWordsAtFontScaleTwo() {
        renderRow(fontScale = 2f)

        val layout = textLayout(TaskTitle)
        (0 until layout.lineCount - 1).forEach { line ->
            val end = layout.getLineEnd(line)
            assertTrue("line $line ends inside a word", TaskTitle[end - 1].isWhitespace() || TaskTitle[end].isWhitespace())
        }
        assertTrue(
            "the duration moves below the task details",
            bounds("25 min").top >= bounds(TaskTitle).bottom,
        )
    }

    @Test
    fun durationStaysBesideTheDetailsAtFontScaleOne() {
        renderRow(fontScale = 1f)

        assertEquals(1, textLayout(TaskTitle).lineCount)
        assertTrue("the duration stays on the details line", bounds("25 min").left >= bounds(TaskTitle).right)
        assertTrue("the duration is centred on the row", bounds("25 min").top < bounds(TaskTitle).top)
    }

    @Test
    fun arrangementKeepsTheLongestWordOnOneLine() {
        assertEquals(HistoryRowArrangement.Inline, historyRowArrangement(400, 48, 13, 80, 259))
        assertEquals(HistoryRowArrangement.DurationBelow, historyRowArrangement(400, 48, 13, 80, 260))
        assertEquals(HistoryRowArrangement.DurationBelow, historyRowArrangement(400, 48, 13, 80, 339))
        assertEquals(HistoryRowArrangement.DetailsBelowStamp, historyRowArrangement(400, 48, 13, 80, 340))
    }

    private fun renderRow(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PomodoroughTheme(darkTheme = false) { HistoryRow(row(), taskTitle = TaskTitle, showPending = false) }
            }
        }
    }

    // The row is one node for a screen reader; its texts are in the unmerged tree.
    private fun bounds(text: String): Rect =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textLayout(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun row(): HistoryItem = Json { ignoreUnknownKeys = false; explicitNulls = false }.decodeFromString(
        "{\"id\":\"row\",\"timerId\":\"timer-row\",\"phase\":\"focus\",\"status\":\"completed\"," +
            "\"plannedDurationMs\":1500000,\"elapsedMs\":1500000,\"taskId\":\"task-1\"}",
    )

    private companion object {
        const val TaskTitle = "Review containerization"
    }
}
