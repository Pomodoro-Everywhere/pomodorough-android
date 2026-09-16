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

    @Test fun unfoldedWideSelectsLandscape() = assertEquals(
        TimerLayoutDecision.Landscape,
        timerLayoutDecision(1200.dp, 800.dp, FoldPosture.Flat),
    )

    @Test fun halfOpenWinsOverSizeForTabletop() = assertEquals(
        TimerLayoutDecision.FoldHalfOpen,
        timerLayoutDecision(800.dp, 600.dp, FoldPosture.HalfOpen),
    )

    @Test fun squareInnerDisplayFallsBackToPortrait() = assertEquals(
        TimerLayoutDecision.Portrait,
        timerLayoutDecision(800.dp, 820.dp, FoldPosture.Flat),
    )
}
