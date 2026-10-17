package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R49A03HingeGeometryTest {
    @Test
    fun asymmetricInsetsKeepControlsBelowOriginalPhysicalHinge() {
        val originalHinge = HingeBoundsPx(0, 600, 1000, 640)
        val bounds = ContentBoundsInWindow(0f, 24f, 1000, 900)
        val hinge = requireNotNull(windowHingeToContent(originalHinge, bounds))
        assertEquals(HingeInContent(0f, 576f, 1000f, 616f), hinge)
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Horizontal))
        assertEquals(IntRect(0, 0, 1000, 576), panes.first)
        assertEquals(IntRect(0, 616, 1000, 900), panes.second)
        assertEquals(originalHinge.bottom.toFloat(), bounds.top + panes.second.top, 0f)
    }

    @Test
    fun offCenterPhysicalPanesPreservePixelsAcrossDensityAndInsets() {
        listOf(1f, 1.5f, 2.625f).forEach { density ->
            listOf(FoldHingeOrientation.Horizontal, FoldHingeOrientation.Vertical).forEach { orientation ->
                val bounds = ContentBoundsInWindow(37f * density, 24f * density, 900, 1200)
                val original = if (orientation == FoldHingeOrientation.Vertical) {
                    HingeBoundsPx(410, 0, 439, 1600)
                } else HingeBoundsPx(0, 713, 752, 1200)
                val hinge = requireNotNull(windowHingeToContent(original, bounds))
                val panes = requireNotNull(physicalFoldPanes(bounds, hinge, orientation))
                assertExcludesOriginal(bounds, panes, original, orientation)
            }
        }
    }

    @Test
    fun fractionalOriginRoundsBothPanesAwayFromHairline() {
        val bounds = ContentBoundsInWindow(24.25f, 31.75f, 900, 700)
        val original = HingeBoundsPx(400, 0, 400, 1000)
        val hinge = requireNotNull(windowHingeToContent(original, bounds))
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
        assertEquals(375, panes.first.right)
        assertEquals(376, panes.second.left)
        assertExcludesOriginal(bounds, panes, original, FoldHingeOrientation.Vertical)
    }

    @Test
    fun hingeOutsideContentAndAtEdgesUsesWholeAvailableRegion() {
        val bounds = ContentBoundsInWindow(100f, 20f, 800, 900)
        listOf(-40 to -20, 0 to 100, 900 to 1000, 1000 to 1040).forEach { (left, right) ->
            val hinge = requireNotNull(windowHingeToContent(HingeBoundsPx(left, 0, right, 1000), bounds))
            val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
            assertEquals(FoldContentLayout.Single(IntRect(0, 0, 800, 900)), foldContentLayout(panes))
        }
    }

    @Test
    fun partialEdgeFoldKeepsBothTimerPartsInsideOnlyUnoccludedRegion() {
        val bounds = ContentBoundsInWindow(100f, 20f, 800, 900)
        val cases = listOf(
            HingeBoundsPx(80, 0, 120, 1000) to IntRect(20, 0, 800, 900),
            HingeBoundsPx(880, 0, 920, 1000) to IntRect(0, 0, 780, 900),
        )
        cases.forEach { (original, expected) ->
            val hinge = requireNotNull(windowHingeToContent(original, bounds))
            val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
            assertEquals(FoldContentLayout.Single(expected), foldContentLayout(panes))
        }
    }

    @Test
    fun fullSpanFoldIsOccludedThenInteriorAndOutsideTransitionsRecover() {
        val bounds = ContentBoundsInWindow(100f, 20f, 800, 900)
        val cases = listOf(
            HingeBoundsPx(0, 0, 1000, 1000) to FoldContentLayout.Occluded,
            HingeBoundsPx(300, 0, 320, 1000) to FoldContentLayout.Split(
                PhysicalFoldPanes(IntRect(0, 0, 200, 900), IntRect(220, 0, 800, 900)),
            ),
            HingeBoundsPx(1000, 0, 1040, 1000) to FoldContentLayout.Single(IntRect(0, 0, 800, 900)),
        )
        cases.forEach { (original, expected) ->
            val hinge = requireNotNull(windowHingeToContent(original, bounds))
            val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
            assertEquals(expected, foldContentLayout(panes))
        }
    }

    @Test
    fun missingMeasurementAndInvalidPlatformBoundsDoNotProduceGeometry() {
        val bounds = ContentBoundsInWindow(0f, 0f, 900, 700)
        val original = HingeBoundsPx(300, 0, 340, 800)
        assertNull(windowHingeToContent(original, null))
        assertNull(windowHingeToContent(null, bounds))
        assertNull(windowHingeToContent(original, bounds.copy(width = 0)))
        assertNull(windowHingeToContent(original, bounds.copy(height = 0)))
        assertNull(windowHingeToContent(original.copy(right = 200), bounds))
        assertNull(windowHingeToContent(original.copy(bottom = -1), bounds))
        val hinge = requireNotNull(windowHingeToContent(original, bounds))
        assertNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Unknown))
    }

    private fun assertExcludesOriginal(
        bounds: ContentBoundsInWindow,
        panes: PhysicalFoldPanes,
        original: HingeBoundsPx,
        orientation: FoldHingeOrientation,
    ) {
        val firstEnd = if (orientation == FoldHingeOrientation.Vertical) bounds.left + panes.first.right
            else bounds.top + panes.first.bottom
        val secondStart = if (orientation == FoldHingeOrientation.Vertical) bounds.left + panes.second.left
            else bounds.top + panes.second.top
        val originalStart = if (orientation == FoldHingeOrientation.Vertical) original.left else original.top
        val originalEnd = if (orientation == FoldHingeOrientation.Vertical) original.right else original.bottom
        assertTrue("first pane ends $firstEnd before original hinge $originalStart", firstEnd <= originalStart)
        assertTrue("second pane starts $secondStart after original hinge $originalEnd", secondStart >= originalEnd)
        assertTrue(originalStart - firstEnd < 1f)
        assertTrue(secondStart - originalEnd < 1f)
    }
}
