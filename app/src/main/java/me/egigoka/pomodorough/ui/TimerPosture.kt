package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Own posture model: androidx.window is not on the classpath
// (see app/build.gradle.kts), and this step adds no Gradle dependency.
// Map to androidx.window FoldingFeature.State at the call site later.
enum class FoldPosture {
    Flat,
    HalfOpen,
}

enum class TimerLayoutDecision {
    Portrait,
    Landscape,
    FoldHalfOpen,
}

// One navigation surface per window shape, the same on every tab (AND-26):
// switching tabs must never move the navigation. The choice follows the
// Material 3 window size classes. A medium or expanded width (tablets,
// unfolded foldables, landscape phones) earns a side rail. A compact height
// (short landscape phones) also takes the rail, because a bottom bar would
// spend scarce height. Everything else, and every fold split (tabletop keeps
// the bar under its lower pane), uses the bottom bar.
internal enum class MainNavigationStyle {
    Rail,
    Bar,
}

// Material 3 window size class breakpoints. They classify the window; they
// do not size or place any view.
internal val MediumWindowWidthBreakpoint = 600.dp
internal val ExpandedWindowWidthBreakpoint = 840.dp
internal val MediumWindowHeightBreakpoint = 480.dp

internal fun mainNavigationStyle(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
    foldSplit: Boolean = false,
): MainNavigationStyle = when {
    foldSplit -> MainNavigationStyle.Bar
    maxWidthDp >= MediumWindowWidthBreakpoint -> MainNavigationStyle.Rail
    maxHeightDp < MediumWindowHeightBreakpoint -> MainNavigationStyle.Rail
    else -> MainNavigationStyle.Bar
}

// AND-29: an expanded width (tablets in landscape, large tablets in portrait,
// unfolded foldables) shows two panes beside the rail. A compact height keeps
// one pane: a landscape phone is wide enough by class, but too short for two
// useful panes under the header. A fold split owns its own panes.
internal enum class WindowPaneLayout {
    SinglePane,
    TwoPane,
}

internal fun windowPaneLayout(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
    foldSplit: Boolean = false,
): WindowPaneLayout = when {
    foldSplit -> WindowPaneLayout.SinglePane
    maxHeightDp < MediumWindowHeightBreakpoint -> WindowPaneLayout.SinglePane
    maxWidthDp >= ExpandedWindowWidthBreakpoint -> WindowPaneLayout.TwoPane
    else -> WindowPaneLayout.SinglePane
}

// The task board is on screen on the Tasks tab and, with two panes, beside
// the Timer. Only then can the user see and undo a task deletion.
internal fun taskBoardShown(activeTab: MainTab, panes: WindowPaneLayout): Boolean =
    activeTab == MainTab.Tasks || (activeTab == MainTab.Timer && panes == WindowPaneLayout.TwoPane)

// Half-open wins so tabletop keeps its split. Separating FLAT (two logical
// displays with a hinge gap) also splits: it is a fold layout even though
// the angle is flat.
internal fun shouldUseFoldSplit(
    posture: FoldPosture = FoldPosture.Flat,
    isSeparating: Boolean = false,
): Boolean = posture == FoldPosture.HalfOpen || isSeparating

// AND-29: the Timer picks its layout by the height class. Only a window that
// is both wider than high and of compact height (a landscape phone) gets the
// compact landscape readout. A wide window with enough height, such as a
// 1280x800 tablet, shows the dial.
internal fun timerLayoutDecision(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
    posture: FoldPosture = FoldPosture.Flat,
    isSeparating: Boolean = false,
): TimerLayoutDecision {
    if (shouldUseFoldSplit(posture, isSeparating)) return TimerLayoutDecision.FoldHalfOpen
    val compactHeight = maxHeightDp < MediumWindowHeightBreakpoint
    return if (compactHeight && maxWidthDp > maxHeightDp) {
        TimerLayoutDecision.Landscape
    } else {
        TimerLayoutDecision.Portrait
    }
}
