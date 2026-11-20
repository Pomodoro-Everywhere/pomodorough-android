package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AND-29: at an expanded width the Timer, Pattern, Arrivals and Network tabs
 * show two panes beside the rail. The header and the message cards stay
 * above both panes. Each pane scrolls on its own.
 *
 * - Timer: the timer card beside the task board. The task picker heads the board.
 * - Pattern: the phase list beside the settings of the whole pattern.
 * - Network: the routes beside the detail of the chosen route.
 * - Arrivals: an arrival has no detail of its own (a row shows all of it), so
 *   the summary sits beside the run log.
 */
@Composable
internal fun ExpandedTabPanes(tabs: MainTabInputs) {
    val activeTab = tabs.ui.navigation.activeTab
    key(activeTab) {
        BoxWithConstraints(Modifier.fillMaxSize().scaffoldContentAboveKeyboard(tabs.padding)) {
            val headerMaxHeight = maxHeight * PaneHeaderMaxHeightShare
            Column(Modifier.fillMaxSize()) {
                PaneHeader(tabs, Modifier.heightIn(max = headerMaxHeight))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        MainTab.Timer -> TimerPanes(tabs)
                        MainTab.Pattern -> PatternPanes(tabs)
                        MainTab.Arrivals -> ArrivalsPanes(tabs)
                        MainTab.Network -> NetworkPanes(tabs)
                        MainTab.Tasks -> error("The Tasks tab is the task board alone")
                    }
                }
            }
        }
    }
}

// The leading pane takes this share of the width; the trailing pane takes the rest.
internal const val LeadingPaneWidthShare = 0.45f

// Tall message cards (large text) scroll inside this share of the height, so
// the panes always keep the rest.
private const val PaneHeaderMaxHeightShare = 0.5f

internal const val LeadingPaneTag = "expanded_leading_pane"
internal const val TrailingPaneTag = "expanded_trailing_pane"

private fun Modifier.leadingPane(row: RowScope): Modifier =
    with(row) { weight(LeadingPaneWidthShare).fillMaxHeight().testTag(LeadingPaneTag) }

private fun Modifier.trailingPane(row: RowScope): Modifier =
    with(row) { weight(1f - LeadingPaneWidthShare).fillMaxHeight().testTag(TrailingPaneTag) }

private val PaneSectionPadding = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)

@Composable
private fun PaneHeader(tabs: MainTabInputs, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        AppHeader(tabs.state, tabs.contentActions.header)
        TimerScreenMessages(tabs.state, tabs.contentActions, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Where a pane list starts its first item, and the space between items. */
private data class PaneListSpacing(val top: Dp = 0.dp, val betweenItems: Dp = 0.dp)

@Composable
private fun PaneList(
    modifier: Modifier,
    ui: TimerScreenUiState,
    listState: LazyListState = rememberLazyListState(),
    spacing: PaneListSpacing = PaneListSpacing(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(top = spacing.top, bottom = tabListBottomPadding(ui)),
        verticalArrangement = Arrangement.spacedBy(spacing.betweenItems),
        content = content,
    )
}

@Composable
private fun RowScope.TimerPanes(tabs: MainTabInputs) {
    val state = tabs.state
    val heroState = timerHeroState(state, tabs.mutationsEnabled)
    val heroActions = tabs.contentActions.hero
    Box(Modifier.leadingPane(this)) {
        TimerPullToRefresh(state, tabs.actions.onRefresh, Modifier.fillMaxSize()) {
            TimerPane(heroState, heroActions)
        }
    }
    PaneList(Modifier.trailingPane(this), tabs.ui, tabs.ui.navigation.tasks) {
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                TaskSelector(heroState.taskSelectorState, heroActions.onSelectTask)
            }
        }
        taskTab(state, tabs.actions, tabs.mutationsEnabled, tabs.ui.taskDraft)
    }
}

// The pane height is the viewport the timer card sizes its dial for.
@Composable
private fun TimerPane(state: TimerHeroState, actions: TimerHeroActions) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val viewportHeight = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TimerPaneHero(state, actions, viewportHeight)
        }
    }
}

@Composable
private fun RowScope.PatternPanes(tabs: MainTabInputs) {
    val state = tabs.state
    val actions = tabs.actions
    PaneList(Modifier.leadingPane(this), tabs.ui, tabs.ui.navigation.listState()) {
        item {
            PatternPhaseList(
                state.settings,
                state.timer,
                tabs.mutationsEnabled,
                actions.onSelectPhase,
                actions.onChangeDuration,
                PaneSectionPadding,
            )
        }
    }
    PaneList(Modifier.trailingPane(this), tabs.ui) {
        item {
            PatternWideSettings(
                state.settings.autoStartBreaks,
                tabs.mutationsEnabled,
                actions.onSetAutoStart,
                PaneSectionPadding,
            )
        }
    }
}

// The run log starts level with the summary title: its list starts at the
// title's depth, and the rows are spaced by the list instead of their own
// padding, so the first row adds nothing above itself.
@Composable
private fun RowScope.ArrivalsPanes(tabs: MainTabInputs) {
    PaneList(Modifier.leadingPane(this), tabs.ui) { arrivalsSummary(tabs.state, tabs.recentHistory) }
    val runLogSpacing = PaneListSpacing(top = ArrivalsTitleVerticalPadding, betweenItems = RunLogRowVerticalPadding * 2)
    PaneList(Modifier.trailingPane(this), tabs.ui, tabs.ui.navigation.listState(), runLogSpacing) {
        arrivalsRunLog(tabs.state, tabs.recentHistory, rowVerticalPadding = 0.dp)
    }
}

@Composable
private fun RowScope.NetworkPanes(tabs: MainTabInputs) {
    val actions = tabs.actions
    val joinInvite = JoinInviteDraft(actions.joinInviteDraft, actions.joinInviteFailed, actions.onJoinInviteChange)
    val leading = Modifier.leadingPane(this)
    val trailing = Modifier.trailingPane(this)
    NetworkSectionArrangement(
        tabs.state,
        tabs.mutationsEnabled,
        networkActions(actions),
        tabs.ui.roomNameDraft,
        joinInvite,
    ) { parts ->
        NetworkPane(leading, tabs.ui, rememberLazyListState(), parts.routes)
        NetworkPane(trailing, tabs.ui, tabs.ui.navigation.listState(), parts.routeDetails)
    }
}

@Composable
private fun NetworkPane(
    modifier: Modifier,
    ui: TimerScreenUiState,
    listState: LazyListState,
    part: @Composable ColumnScope.() -> Unit,
) {
    PaneList(modifier, ui, listState) {
        item {
            Column(PaneSectionPadding, verticalArrangement = Arrangement.spacedBy(14.dp), content = part)
        }
    }
}
