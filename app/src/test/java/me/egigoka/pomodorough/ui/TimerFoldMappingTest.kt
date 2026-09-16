package me.egigoka.pomodorough.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerFoldMappingTest {
    @Test fun halfOpenedMapsToHalfOpen() = assertEquals(
        FoldPosture.HalfOpen,
        foldPostureForState(true),
    )

    @Test fun flatMapsFromNotHalfOpened() = assertEquals(
        FoldPosture.Flat,
        foldPostureForState(false),
    )

    @Test fun verticalFlagMapsToVertical() = assertEquals(
        FoldHingeOrientation.Vertical,
        foldHingeOrientation(true),
    )

    @Test fun horizontalFlagMapsToHorizontal() = assertEquals(
        FoldHingeOrientation.Horizontal,
        foldHingeOrientation(false),
    )

    @Test fun missingFeatureMapsToUnknown() = assertEquals(
        FoldHingeOrientation.Unknown,
        foldHingeOrientation(null),
    )

    @Test fun tabletopNeedsHalfOpenPlusHorizontal() {
        assertTrue(isTabletopPosture(true, FoldHingeOrientation.Horizontal))
        assertFalse(isTabletopPosture(true, FoldHingeOrientation.Vertical))
        assertFalse(isTabletopPosture(true, FoldHingeOrientation.Unknown))
        assertFalse(isTabletopPosture(false, FoldHingeOrientation.Horizontal))
    }
}
