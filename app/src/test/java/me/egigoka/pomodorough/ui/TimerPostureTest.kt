package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerPostureTest {
    @Test fun portraitPhoneStaysPortrait() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(400.dp, 800.dp, FoldPosture.Flat),
    )

    @Test fun landscapePhoneSelectsLandscape() = assertEquals(
        TimerLayoutDecision.Landscape,
        timerLayoutDecision(800.dp, 400.dp, FoldPosture.Flat),
    )

    // AND-29: the height class picks the layout. A wide window with enough
    // height shows the dial; only a short wide window gets the compact readout.
    @Test fun unfoldedWideWithEnoughHeightShowsTheDial() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(1200.dp, 800.dp, FoldPosture.Flat),
    )

    @Test fun tabletLandscapeShowsTheDial() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(1280.dp, 800.dp),
    )

    @Test fun shortWideWindowsKeepTheCompactReadout() {
        listOf(569.dp to 320.dp, 891.dp to 411.dp, 1280.dp to 479.dp).forEach { (width, height) ->
            assertEquals("$width x $height", TimerLayoutDecision.Landscape, timerLayoutDecision(width, height))
        }
    }

    @Test fun compactHeightEndsAtTheMediumHeightBreakpoint() {
        assertEquals(TimerLayoutDecision.Landscape, timerLayoutDecision(800.dp, 479.dp))
        assertEquals(TimerLayoutDecision.Portrait, timerLayoutDecision(800.dp, 480.dp))
    }

    @Test fun shortWindowThatIsNotWiderThanHighStaysPortrait() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(400.dp, 450.dp),
    )

    @Test fun halfOpenWinsAtEveryWindowSize() {
        listOf(1280.dp to 800.dp, 800.dp to 400.dp, 400.dp to 800.dp).forEach { (width, height) ->
            assertEquals(
                "$width x $height",
                TimerLayoutDecision.FoldHalfOpen,
                timerLayoutDecision(width, height, FoldPosture.HalfOpen),
            )
        }
    }

    @Test fun separatingFlatFoldWinsOverTheHeightClass() = assertEquals(
        TimerLayoutDecision.FoldHalfOpen,
        timerLayoutDecision(1280.dp, 800.dp, FoldPosture.Flat, isSeparating = true),
    )

    @Test fun flatFoldWithoutASeparatingHingeFollowsTheHeightClass() {
        assertEquals(TimerLayoutDecision.Portrait, timerLayoutDecision(1280.dp, 800.dp, FoldPosture.Flat))
        assertEquals(TimerLayoutDecision.Landscape, timerLayoutDecision(800.dp, 400.dp, FoldPosture.Flat))
    }

    @Test fun halfOpenWinsOverSizeForTabletop() = assertEquals(
        TimerLayoutDecision.FoldHalfOpen,
        timerLayoutDecision(800.dp, 600.dp, FoldPosture.HalfOpen),
    )

    @Test fun squareInnerDisplayFallsBackToPortrait() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(800.dp, 820.dp, FoldPosture.Flat),
    )
}
