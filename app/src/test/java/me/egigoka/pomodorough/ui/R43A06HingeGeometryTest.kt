package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R43-A06: hinge geometry must be retained in window coordinates,
// converted to content coordinates, and usable regions must avoid the
// hinge. Separating FLAT must split, not fall back. Controls appear once.
class R43A06HingeGeometryTest {
    @Test
    fun scaffoldRetainsHingeBoundsInWindowCoordinates() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "fold state must retain measured hinge bounds (window coordinates)",
            scaffold.contains("hingeBounds"),
        )
    }

    @Test
    fun scaffoldHandlesSeparatingFlatState() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "fold split must handle separating FLAT, not only HALF_OPENED",
            scaffold.contains("isSeparating"),
        )
    }

    @Test
    fun scaffoldHandlesOcclusion() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "fold split must retain occlusion (FULL vs NONE)",
            scaffold.contains("occlusion", ignoreCase = true),
        )
    }

    @Test
    fun postureDecisionHandlesSeparatingFlat() {
        val posture = productionText("me/egigoka/pomodorough/ui/TimerPosture.kt")
        assertTrue(
            "layout decision must consider separating FLAT",
            posture.contains("isSeparating"),
        )
    }

    @Test
    fun foldSplitUsesMeasuredGeometryNotEqualHalves() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "fold split must convert window hinge to content coordinates",
            scaffold.contains("windowHingeToContent") || scaffold.contains("hingeFractions"),
        )
    }

    @Test
    fun offCenterVerticalHingeSplitsProportionally() {
        val bounds = ContentBoundsInWindow(0f, 0f, 1000, 1000)
        val hinge = requireNotNull(windowHingeToContent(HingeBoundsPx(300, 0, 300, 1000), bounds))
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
        assertEquals(300, panes.first.width)
        assertEquals(700, panes.second.width)
    }

    @Test
    fun nonzeroHingeWidthExcludedFromBothPanes() {
        val bounds = ContentBoundsInWindow(0f, 0f, 1000, 1000)
        val hinge = requireNotNull(windowHingeToContent(HingeBoundsPx(480, 0, 520, 1000), bounds))
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
        assertEquals(480, panes.first.right)
        assertEquals(520, panes.second.left)
        assertEquals(480, panes.second.width)
    }

    @Test
    fun horizontalTabletopSplitsTopAndBottomAroundHinge() {
        val bounds = ContentBoundsInWindow(0f, 0f, 800, 1000)
        val hinge = requireNotNull(windowHingeToContent(HingeBoundsPx(0, 600, 1000, 640), bounds))
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Horizontal))
        assertEquals(600, panes.first.bottom)
        assertEquals(640, panes.second.top)
        assertEquals(360, panes.second.height)
    }

    @Test
    fun separatingFlatTriggersFoldSplit() {
        assertEquals(
            TimerLayoutDecision.FoldHalfOpen,
            timerLayoutDecision(800.dp, 400.dp, FoldPosture.Flat, isSeparating = true),
        )
        assertEquals(
            TimerLayoutDecision.FoldHalfOpen,
            timerLayoutDecision(400.dp, 800.dp, FoldPosture.Flat, isSeparating = true),
        )
        assertTrue(shouldUseFoldSplit(FoldPosture.Flat, isSeparating = true))
    }

    @Test
    fun nonSeparatingFlatFallsBackToLegacyGate() {
        assertEquals(
            TimerLayoutDecision.Landscape,
            timerLayoutDecision(800.dp, 400.dp, FoldPosture.Flat, isSeparating = false),
        )
        assertEquals(
            TimerLayoutDecision.Portrait,
            timerLayoutDecision(400.dp, 800.dp, FoldPosture.Flat, isSeparating = false),
        )
        assertFalse(shouldUseFoldSplit(FoldPosture.Flat, isSeparating = false))
    }

    @Test
    fun halfOpenStillSplitsWithoutSeparating() {
        assertEquals(
            TimerLayoutDecision.FoldHalfOpen,
            timerLayoutDecision(800.dp, 600.dp, FoldPosture.HalfOpen, isSeparating = false),
        )
        assertTrue(shouldUseFoldSplit(FoldPosture.HalfOpen, isSeparating = false))
    }

    @Test
    fun occludingAndNonOccludingHingesBothAvoidControls() {
        val bounds = ContentBoundsInWindow(0f, 0f, 1000, 1000)
        listOf(HingeBoundsPx(490, 0, 510, 1000), HingeBoundsPx(500, 0, 500, 1000)).forEach { original ->
            val hinge = requireNotNull(windowHingeToContent(original, bounds))
            val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
            assertEquals(original.left, panes.first.right)
            assertEquals(original.right, panes.second.left)
        }
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(scaffold.contains("occlusionFull"))
    }

    @Test
    fun windowToContentConversionSubtractsMeasuredOriginAcrossInsets() {
        val bounds = ContentBoundsInWindow(24f, 30f, 900, 700)
        val hingePx = HingeBoundsPx(300, 0, 320, 800)
        val hinge = requireNotNull(windowHingeToContent(hingePx, bounds))
        assertEquals(276f, hinge.left, 0f)
        assertEquals(296f, hinge.right, 0f)
        val panes = requireNotNull(physicalFoldPanes(bounds, hinge, FoldHingeOrientation.Vertical))
        assertEquals(hingePx.left.toFloat(), bounds.left + panes.first.right, 0f)
        assertEquals(hingePx.right.toFloat(), bounds.left + panes.second.left, 0f)
    }

    @Test
    fun foldSplitDoesNotDuplicateTimerControls() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldSection = scaffold.substringAfter("internal fun HalfOpenTimerScreen")
            .substringBefore("private fun timerContentActions")
        assertTrue(
            "fold panes must be complementary readout plus controls",
            foldSection.contains("FoldReadoutPane") && foldSection.contains("FoldControlsPane"),
        )
        assertTrue(
            "fold split must reuse single readout and single controls",
            foldSection.contains("LandscapeTimerReadout") || scaffold.contains("LandscapeTimerReadout"),
        )
        assertFalse(
            "fold split must not render two full timer screens with duplicated controls",
            foldSection.contains("PortraitTimerScreen(state"),
        )
    }

    @Test
    fun navigationAndInsetsStayOutsideHingeSplit() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldSection = scaffold.substringAfter("internal fun HalfOpenTimerScreen")
            .substringBefore("private fun FoldPhysicalPanes")
        assertTrue(foldSection.contains("MainNavigationBar("))
        assertTrue(foldSection.contains("systemBarsPadding()"))
        assertTrue(foldSection.contains(".padding(padding)"))
        assertTrue(foldSection.contains("positionInWindow()"))
    }

    @Test
    fun postureTransitionsRecomputeSplitFromCurrentState() {
        assertTrue(shouldUseFoldSplit(FoldPosture.HalfOpen, isSeparating = false))
        assertTrue(shouldUseFoldSplit(FoldPosture.Flat, isSeparating = true))
        assertTrue(shouldUseFoldSplit(FoldPosture.HalfOpen, isSeparating = true))
        assertFalse(shouldUseFoldSplit(FoldPosture.Flat, isSeparating = false))
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(scaffold.contains("foldState.posture"))
        assertTrue(scaffold.contains("foldState.isSeparating"))
        assertTrue(scaffold.contains("foldState.hingeBounds"))
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
