package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX round 2, B1: the landscape countdown line shrinks into the height the
 * first viewport leaves for it, and only then.
 */
class LandscapeFirstViewportTest {
    @Test
    fun countdownKeepsItsSizeWhenTheViewportHasRoom() {
        assertEquals(1f, shrinkScale(naturalHeight = 48, constraints = Constraints(maxHeight = 96), minScale = 0.3f))
        assertEquals(1f, shrinkScale(naturalHeight = 48, constraints = Constraints(maxHeight = 48), minScale = 0.3f))
    }

    @Test
    fun countdownKeepsItsSizeInsideScrollingFoldPanes() {
        assertEquals(1f, shrinkScale(naturalHeight = 48, constraints = Constraints(), minScale = 0.3f))
    }

    @Test
    fun countdownShrinksInProportionToTheHeightLeft() {
        assertEquals(0.75f, shrinkScale(naturalHeight = 48, constraints = Constraints(maxHeight = 36), minScale = 0.3f))
    }

    @Test
    fun countdownNeverShrinksBelowItsCaptionSize() {
        assertEquals(0.3f, shrinkScale(naturalHeight = 48, constraints = Constraints(maxHeight = 4), minScale = 0.3f))
        assertEquals(1f, shrinkScale(naturalHeight = 48, constraints = Constraints(maxHeight = 4), minScale = 1.2f))
    }
}
