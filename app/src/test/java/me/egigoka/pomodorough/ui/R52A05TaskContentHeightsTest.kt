package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// R52-A05: task selector and Add task clipped enlarged text inside fixed
// 48dp/52dp heights. Renders production TaskSelector, LandscapeTaskSelector,
// and TaskBoardHeader with real Material 3 typography at density 1 through
// Robolectric native graphics and asserts full translated glyph bounds sit
// inside intrinsic-height controls with minimum touch targets retained.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R52A05TaskContentHeightsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selector320RtlFitsAtScale2() {
        renderSelector(widthDp = 320, fontScale = 2f, rtl = true)
        assertFitsAndReachable(label(R.string.no_task))
    }

    @Test
    fun selector240RtlFitsVerticallyAtScale2() {
        // Narrow pane: the single-line title keeps its deliberate
        // maxLines=1 ellipsis (full title stays in the dropdown menu),
        // so only vertical glyph fit plus reachability are pinned here.
        renderSelector(widthDp = 240, fontScale = 2f, rtl = true)
        assertFitsVerticallyAndReachable(label(R.string.no_task))
    }

    @Test
    fun selector320LtrFitsAtScale2() {
        renderSelector(widthDp = 320, fontScale = 2f, rtl = false)
        assertFitsAndReachable(label(R.string.no_task))
    }

    @Test
    fun selectorDefaultScaleControlFits() {
        renderSelector(widthDp = 320, fontScale = 1f, rtl = false)
        assertFitsAndReachable(label(R.string.no_task))
    }

    @Test
    fun selectorGrowsInsteadOfClipping() {
        renderSelector(widthDp = 240, fontScale = 2f, rtl = true)
        val grownHeight = controlHeight(label(R.string.no_task))
        assertTrue(
            "selector grows beyond fixed 48px, was $grownHeight",
            grownHeight > 48f,
        )
        assertFitsVertically(label(R.string.no_task))
    }

    @Test
    fun landscapeSelector240RtlFitsVerticallyAtScale2() {
        renderLandscapeSelector(widthDp = 240, fontScale = 2f, rtl = true)
        assertFitsVerticallyAndReachable(label(R.string.no_task))
    }

    @Test
    fun addTask320RtlFitsAtScale2() {
        renderDraft(widthDp = 320, fontScale = 2f, rtl = true)
        assertFitsAndReachable(label(R.string.add_task))
    }

    @Test
    fun addTask240RtlGrowsInsteadOfClipping() {
        renderDraft(widthDp = 240, fontScale = 2f, rtl = true)
        val grownHeight = controlHeight(label(R.string.add_task))
        assertTrue(
            "add task grows beyond fixed 52px, was $grownHeight",
            grownHeight > 52f,
        )
        assertFits(label(R.string.add_task))
    }

    @Test
    fun addTaskDefaultScaleControlFits() {
        renderDraft(widthDp = 320, fontScale = 1f, rtl = false)
        assertFitsAndReachable(label(R.string.add_task))
    }

    @Test
    fun addTaskActivatesOnceAtScale2() {
        var calls = 0
        var submitted = ""
        renderDraft(widthDp = 240, fontScale = 2f, rtl = true) { draft, _ ->
            calls++
            submitted = draft
        }
        compose.onNodeWithText(label(R.string.add_task)).performScrollTo().performClick()
        assertEquals(1, calls)
        assertEquals("Buy milk", submitted)
    }

    private fun assertFitsAndReachable(text: String) {
        val control = compose.onNodeWithText(text).performScrollTo()
        control.assertIsDisplayed()
        control.assertHasClickAction()
        control.assertIsEnabled()
        assertFits(text)
    }

    private fun assertFitsVerticallyAndReachable(text: String) {
        val control = compose.onNodeWithText(text).performScrollTo()
        control.assertIsDisplayed()
        control.assertHasClickAction()
        control.assertIsEnabled()
        assertFitsVertically(text)
    }

    private fun assertFits(text: String) {
        val layout = textLayout(text)
        assertFalse("$text exceeds lines", layout.multiParagraph.didExceedMaxLines)
        assertFitsVertically(text, layout)
    }

    private fun assertFitsVertically(text: String, layout: TextLayoutResult = textLayout(text)) {
        // R51 probe criterion, vertical legs: the full paragraph fits
        // the measured text box and the control. hasVisualOverflow also
        // trips on subpixel glyph overhang when the paragraph fits
        // exactly, so it is not a clipping signal here.
        assertTrue(
            "$text paragraph ${layout.multiParagraph.height} in box ${layout.size.height}",
            layout.multiParagraph.height <= layout.size.height + 0.5f,
        )
        val controlSize = compose.onNodeWithText(text).fetchSemanticsNode().size
        assertTrue(
            "$text paragraph ${layout.multiParagraph.height} in control ${controlSize.height}",
            layout.multiParagraph.height <= controlSize.height + 0.5f,
        )
        assertTrue(
            "$text width ${layout.size.width} in control ${controlSize.width}",
            layout.size.width <= controlSize.width + 0.5f,
        )
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun renderSelector(widthDp: Int, fontScale: Float, rtl: Boolean) {
        compose.setContent {
            withScaledDensity(fontScale, rtl) {
                // Outer scroll mirrors PortraitTimerScreen, which owns
                // scrolling for the header, messages, and hero together.
                Column(Modifier.width(widthDp.dp).verticalScroll(rememberScrollState())) {
                    TaskSelector(TaskSelectorState(emptyList(), null, true), {})
                }
            }
        }
    }

    private fun renderLandscapeSelector(widthDp: Int, fontScale: Float, rtl: Boolean) {
        compose.setContent {
            withScaledDensity(fontScale, rtl) {
                Column(Modifier.width(widthDp.dp).verticalScroll(rememberScrollState())) {
                    LandscapeTaskSelector(TaskSelectorState(emptyList(), null, true), {})
                }
            }
        }
    }

    private fun renderDraft(
        widthDp: Int,
        fontScale: Float,
        rtl: Boolean,
        onAdd: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    ) {
        val draft = TaskDraftUiState().apply { onDraftChange("Buy milk") }
        compose.setContent {
            withScaledDensity(fontScale, rtl) {
                Column(Modifier.width(widthDp.dp).verticalScroll(rememberScrollState())) {
                    TaskBoardHeader(emptyList(), true, onAdd, draft)
                }
            }
        }
    }

    private fun label(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Composable
    private fun withScaledDensity(fontScale: Float, rtl: Boolean, content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalConfiguration provides config,
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            MaterialTheme { content() }
        }
    }

    private fun controlHeight(text: String): Float {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        return node.size.height.toFloat()
    }
}
