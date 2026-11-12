package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, connected-test triage: every navigation destination keeps its
 * whole text name at every font scale (the v0.42.0 always-visible labels). A
 * label that does not fit its item scales down; it never disappears and never
 * loses letters to an ellipsis. Checked on the smallest audit phone, 320 dp
 * wide, in both orientations: the bar in portrait and the rail in landscape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w320dp-h569dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationLabelsFitTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun barLabelsStayWholeAtDefaultText() = assertLabelsWhole(fontScale = 1f)

    @Test
    fun barLabelsStayWholeAtTheLargestText() = assertLabelsWhole(fontScale = 2f)

    @Test
    @Config(qualifiers = "en-w569dp-h320dp-land")
    fun railLabelsStayWholeAtTheLargestText() = assertLabelsWhole(fontScale = 2f)

    private fun assertLabelsWhole(fontScale: Float) {
        render(fontScale)
        val cut = MainTab.entries.map { tab ->
            val label = label(tab)
            val labelNode = hasText(label) and isNavigationLabel()
            // The short landscape rail scrolls its destinations; the bar does not.
            compose.onAllNodes(hasTestTag(RailListTag)).fetchSemanticsNodes().singleOrNull()?.let {
                compose.onNode(hasTestTag(RailListTag)).performScrollToNode(hasText(label))
            }
            val node = compose.onNode(labelNode, useUnmergedTree = true)
            node.assertIsDisplayed()
            label to node.isCut()
        }
        assertEquals("labels cut at font scale $fontScale", MainTab.entries.map { label(it) to false }, cut)
    }

    private fun render(fontScale: Float) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalConfiguration provides config,
            ) {
                PomodoroughTheme(darkTheme = false) { SignedOutScreen() }
            }
        }
    }

    @Composable
    private fun SignedOutScreen() {
        PomodoroughScreen(
            state = AppState(ready = true, authStatus = AuthStatus.SignedOut),
            onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
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

private fun label(tab: MainTab): String =
    ApplicationProvider.getApplicationContext<Application>().getString(tab.labelRes)

// The label is the text node tagged by the navigation item, so a heading with
// the same word elsewhere on the screen does not count.
private fun isNavigationLabel() = hasTestTag(NavigationLabelTag)

// The label is cut when its whole text, laid out on one line in the style
// the label renders with, is wider than the label node or its constraints.
// The overflow flags on the node's own text layout are not used: for a plain
// String Text they are rebuilt for semantics and reported overflow at default
// text where every label visibly fit.
private fun SemanticsNodeInteraction.isCut(): Boolean {
    val semantics = fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    semantics.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val input = layouts.single().layoutInput
    val whole = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        .measure(input.text, input.style, maxLines = 1, softWrap = false)
    return whole.size.width > minOf(semantics.size.width, input.constraints.maxWidth)
}
