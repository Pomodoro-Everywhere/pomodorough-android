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

// Mirrors TimerScreenScaffold's BoxWithConstraints gate
// (maxWidth > maxHeight); half-open wins so tabletop keeps its split.
internal fun timerLayoutDecision(
    maxWidthDp: Dp,
    maxHeightDp: Dp,
    posture: FoldPosture = FoldPosture.Flat,
): TimerLayoutDecision {
    if (posture == FoldPosture.HalfOpen) return TimerLayoutDecision.FoldHalfOpen
    return if (maxWidthDp > maxHeightDp) TimerLayoutDecision.Landscape else TimerLayoutDecision.Portrait
}
