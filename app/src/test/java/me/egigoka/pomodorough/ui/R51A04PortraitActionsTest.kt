package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// R51-A04: portrait Finish/Cancel clipped at font scale 2.0 inside fixed
// 48dp heights. Renders production TimerHero with real Material 3
// typography at density 1 through Robolectric native graphics and asserts
// full translated glyph bounds sit inside control bounds.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R51A04PortraitActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun portrait320RtlFitsFinishCancelAtScale2() {
        renderPortrait(widthDp = 320, fontScale = 2f, rtl = true)
        assertFitsAndReachable(label(R.string.finish))
        assertFitsAndReachable(label(R.string.cancel))
    }

    @Test
    fun portrait240RtlWrapsFinishCancelWithoutClipping() {
        renderPortrait(widthDp = 240, fontScale = 2f, rtl = true)
        assertFitsAndReachable(label(R.string.finish))
        assertFitsAndReachable(label(R.string.cancel))
    }

    @Test
    fun portrait320LtrFitsPrimaryAndActionsAtScale2() {
        renderPortrait(widthDp = 320, fontScale = 2f, rtl = false)
        assertFitsAndReachable(label(R.string.resume))
        assertFitsAndReachable(label(R.string.finish))
        assertFitsAndReachable(label(R.string.cancel))
    }

    @Test
    fun portraitDefaultScaleControlFits() {
        renderPortrait(widthDp = 320, fontScale = 1f, rtl = false)
        assertFitsAndReachable(label(R.string.resume))
        assertFitsAndReachable(label(R.string.finish))
        assertFitsAndReachable(label(R.string.cancel))
    }

    @Test
    fun finishActivatesAtScale2() {
        var finished = 0
        renderPortrait(widthDp = 240, fontScale = 2f, rtl = true, onFinish = { finished++ })
        compose.onNodeWithText(label(R.string.finish)).performScrollTo().performClick()
        assertEquals(1, finished)
    }

    @Test
    fun cancelActivatesAtScale2() {
        var cancelled = 0
        renderPortrait(widthDp = 240, fontScale = 2f, rtl = true, onCancel = { cancelled++ })
        compose.onNodeWithText(label(R.string.cancel)).performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun translatedActionLabelsPresentAndDistinct() {
        val finish = label(R.string.finish)
        val cancel = label(R.string.cancel)
        val resume = label(R.string.resume)
        assertTrue(finish.isNotBlank() && cancel.isNotBlank() && resume.isNotBlank())
        assertEquals(3, setOf(finish, cancel, resume).size)
    }

    @Test
    fun portraitActionsGrowInsteadOfClipping() {
        // Behavioral replacement for the former source pin that grepped
        // FlowRow/heightIn/no-fixed-height. One setContent per test, so
        // growth is pinned against the 48dp minimum: fixed heights stay
        // at 48px and fail both the growth and the fits below.
        renderPortrait(widthDp = 240, fontScale = 2f, rtl = true)
        val grownHeight = controlHeight(label(R.string.finish))
        assertTrue(
            "finish grows beyond fixed 48px, was $grownHeight",
            grownHeight > 48f,
        )
        assertFits(label(R.string.finish))
        assertFits(label(R.string.cancel))
    }

    @Test
    fun landscapeActionsStayFitted() {
        // Behavioral only: narrow landscape fits all three actions.
        // Former source pin asserted FlowRow/heightIn strings here.
        renderLandscape(widthDp = 240)
        assertFits(label(R.string.resume))
        assertFits(label(R.string.finish))
        assertFits(label(R.string.cancel))
    }

    @Test
    fun portraitActionsInvokeToggleFinishCancelOnce() {
        // Behavioral replacement for the source callback-count pin
        // (active branch). One setContent per test; stop-sound has its
        // own test below for the completion-alert branch.
        var toggle = 0
        var finish = 0
        var cancel = 0
        renderPortrait(
            widthDp = 240,
            fontScale = 2f,
            rtl = true,
            actions = TimerHeroActions(
                onToggleTimer = { toggle++ },
                onFinishTimer = { finish++ },
                onCancelTimer = { cancel++ },
                onStopSound = {},
                onSelectTask = {},
            ),
        )
        compose.onNodeWithText(label(R.string.resume)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.finish)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.cancel)).performScrollTo().performClick()
        assertEquals(1, toggle)
        assertEquals(1, finish)
        assertEquals(1, cancel)
    }

    @Test
    fun portraitStopSoundInvokesOnce() {
        // Completion-alert branch of the former callback-count pin.
        var stop = 0
        renderCompletionAlert(onStopSound = { stop++ })
        compose.onNodeWithText(label(R.string.stop_sound)).performScrollTo().performClick()
        assertEquals(1, stop)
    }

    @Test
    fun portraitGrowthScrollsOuterBehaviorally() {
        // Behavioral replacement for the TimerScreenContent source pin.
        // Narrow scale-2 actions stay reachable through the outer scroll.
        renderPortrait(widthDp = 240, fontScale = 2f, rtl = true)
        compose.onNodeWithText(label(R.string.finish)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).performScrollTo().assertIsDisplayed()
        assertFits(label(R.string.finish))
        assertFits(label(R.string.cancel))
    }

    private fun assertFitsAndReachable(text: String) {
        // Merged tree: the clickable button absorbs its label text.
        // Scroll first: enlarged text pushes actions below the fold and
        // the production screen reaches them through its outer scroll.
        val control = compose.onNodeWithText(text).performScrollTo()
        control.assertIsDisplayed()
        control.assertHasClickAction()
        control.assertIsEnabled()
        assertFits(text)
    }

    private fun assertFits(text: String) {
        // R51 probe criterion: the full paragraph fits the measured text
        // box with no dropped lines. hasVisualOverflow also trips on
        // subpixel glyph overhang when the paragraph fits exactly, so it
        // is not a clipping signal here. The control-bounds check below
        // pins glyphs inside the button itself.
        val layout = textLayout(text)
        assertFalse("$text exceeds lines", layout.multiParagraph.didExceedMaxLines)
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

    private fun renderPortrait(
        widthDp: Int,
        fontScale: Float,
        rtl: Boolean,
        onFinish: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        renderPortrait(widthDp, fontScale, rtl, actionsWith(onFinish, onCancel))
    }

    private fun renderPortrait(widthDp: Int, fontScale: Float, rtl: Boolean, actions: TimerHeroActions) {
        val state = pausedHeroState()
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                // Outer scroll mirrors PortraitTimerScreen, which owns
                // scrolling for the header, messages, and hero together.
                MaterialTheme {
                    Column(Modifier.width(widthDp.dp).verticalScroll(rememberScrollState())) {
                        TimerHero(state, actions, orbitMaxSize = 132.dp)
                    }
                }
            }
        }
    }

    private fun renderLandscape(widthDp: Int) {
        val state = pausedHeroState()
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalConfiguration provides config,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme { Box(Modifier.width(widthDp.dp)) { LandscapeTimerActions(state, actionsWith()) } }
            }
        }
    }

    private fun label(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    private fun pausedHeroState() = TimerHeroState(
        timer = CanonicalTimer(
            id = "timer-r51-a04",
            phase = TimerPhase.Focus,
            status = TimerStatus.Paused,
            plannedDurationMs = 25 * 60_000L,
            elapsedAtAnchorMs = 0L,
            anchorAt = "2026-08-20T00:25:00Z",
        ),
        activeCompletionAlertTimerId = null,
        settings = TimerSettings(),
        longBreakProgress = 0,
        ready = true,
        tasks = emptyList(),
        selectedTaskId = null,
        mutationsEnabled = true,
    )

    private fun actionsWith(onFinish: () -> Unit = {}, onCancel: () -> Unit = {}) =
        TimerHeroActions({}, onFinish, onCancel, {}, {})

    private fun controlHeight(text: String): Float {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        return node.size.height.toFloat()
    }

    private fun renderCompletionAlert(onStopSound: () -> Unit) {
        val completed = pausedHeroState().timer?.copy(status = TimerStatus.Completed)
        val state = pausedHeroState().copy(
            timer = completed,
            activeCompletionAlertTimerId = completed?.id,
        )
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { fontScale = 2f }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalConfiguration provides config,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        TimerHero(state, TimerHeroActions({}, {}, {}, onStopSound, {}))
                    }
                }
            }
        }
    }
}
