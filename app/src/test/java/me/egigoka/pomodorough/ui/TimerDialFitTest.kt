package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX round 2, B2 (AND-25): the ring keeps its labels only while they fit inside its stroke. */
class TimerDialFitTest {
    @Test
    fun ringHoldsLabelsThatFitInsideItsStroke() {
        assertTrue(ringHoldsLabels(ringDiameter = 268, strokeWidth = 18, labels = IntSize(150, 126)))
        assertTrue(ringHoldsLabels(ringDiameter = 268, strokeWidth = 18, labels = IntSize(232, 232)))
    }

    @Test
    fun ringGivesUpLabelsWiderThanItsInside() {
        assertFalse(ringHoldsLabels(ringDiameter = 132, strokeWidth = 18, labels = IntSize(240, 90)))
        assertFalse(ringHoldsLabels(ringDiameter = 268, strokeWidth = 18, labels = IntSize(233, 100)))
    }

    @Test
    fun ringGivesUpLabelsTallerThanItsInside() {
        assertFalse(ringHoldsLabels(ringDiameter = 132, strokeWidth = 18, labels = IntSize(90, 97)))
    }
}
