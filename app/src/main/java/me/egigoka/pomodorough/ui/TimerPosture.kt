package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.Dp

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

// Navigation keeps every destination reachable from landscape Timer.
// The choice derives from measured proportions, not a fixed width:
// clearly-wide windows earn a side rail (vertical space is scarce),
// near-square windows keep a bottom bar (horizontal space is scarce).
internal enum class LandscapeNavigationStyle {
    Rail,
    Bar,
}

internal fun landscapeNavigationStyle(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
): LandscapeNavigationStyle =
    if (maxWidthDp >= maxHeightDp * 1.4f) LandscapeNavigationStyle.Rail
    else LandscapeNavigationStyle.Bar

// Mirrors TimerScreenScaffold's BoxWithConstraints gate
// (maxWidth > maxHeight); half-open wins so tabletop keeps its split.
// Separating FLAT (two logical displays with a hinge gap) also splits:
// it is a fold layout even though the angle is flat.
internal fun shouldUseFoldSplit(
    posture: FoldPosture = FoldPosture.Flat,
    isSeparating: Boolean = false,
): Boolean = posture == FoldPosture.HalfOpen || isSeparating

internal fun timerLayoutDecision(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
    posture: FoldPosture = FoldPosture.Flat,
    isSeparating: Boolean = false,
): TimerLayoutDecision {
    if (shouldUseFoldSplit(posture, isSeparating)) return TimerLayoutDecision.FoldHalfOpen
    return if (maxWidthDp > maxHeightDp) TimerLayoutDecision.Landscape else TimerLayoutDecision.Portrait
}
