package me.egigoka.pomodorough.ui

// Pure mapping between platform folding primitives and TimerPosture.
// Takes Booleans, not androidx.window types, so JVM unit tests stay
// classpath-free. Call site converts FoldingFeature.state/orientation.
enum class FoldHingeOrientation {
    Horizontal,
    Vertical,
    Unknown,
}

internal fun foldPostureForState(isHalfOpened: Boolean): FoldPosture =
    if (isHalfOpened) FoldPosture.HalfOpen else FoldPosture.Flat

internal fun foldHingeOrientation(isVertical: Boolean?): FoldHingeOrientation = when (isVertical) {
    true -> FoldHingeOrientation.Vertical
    false -> FoldHingeOrientation.Horizontal
    null -> FoldHingeOrientation.Unknown
}

internal fun isTabletopPosture(isHalfOpened: Boolean, hinge: FoldHingeOrientation): Boolean =
    isHalfOpened && hinge == FoldHingeOrientation.Horizontal
