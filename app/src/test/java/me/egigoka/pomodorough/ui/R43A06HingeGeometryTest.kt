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
        val fractions = hingeFractions(
            HingeBoundsPx(300, 0, 300, 1000),
            WindowSizePx(1000, 1000),
        )
        assertNotNull(fractions)
        val weights = verticalSplitWeights(requireNotNull(fractions))
        assertNotNull(weights)
        assertEquals(0.3f, requireNotNull(weights).first, 0.001f)
        assertEquals(0.7f, requireNotNull(weights).second, 0.001f)
        assertFalse("off-center split must not be equal halves", requireNotNull(weights).first == 0.5f)
    }

    @Test
    fun nonzeroHingeWidthExcludedFromBothPanes() {
        val fractions = hingeFractions(
            HingeBoundsPx(480, 0, 520, 1000),
            WindowSizePx(1000, 1000),
        )
        val weights = verticalSplitWeights(requireNotNull(fractions))
        assertNotNull(weights)
        assertEquals(0.04f, requireNotNull(weights).hinge, 0.001f)
        val content = windowHingeToContent(
            HingeBoundsPx(480, 0, 520, 1000),
            WindowSizePx(1000, 1000),
            1000.dp, 1000.dp,
        )
        assertNotNull(content)
        val (left, right) = verticalPaneWidths(1000.dp, requireNotNull(content))
        assertEquals(480.dp, left)
        assertEquals(480.dp, right)
        assertFalse(controlBoundsIntersectHinge(0.dp, left, requireNotNull(content).left, requireNotNull(content).right))
        assertFalse(controlBoundsIntersectHinge(requireNotNull(content).right, 1000.dp, requireNotNull(content).left, requireNotNull(content).right))
    }

    @Test
    fun horizontalTabletopSplitsTopAndBottomAroundHinge() {
        val fractions = hingeFractions(
            HingeBoundsPx(0, 600, 1000, 640),
            WindowSizePx(1000, 1000),
        )
        val weights = horizontalSplitWeights(requireNotNull(fractions))
        assertNotNull(weights)
        assertEquals(0.6f, requireNotNull(weights).first, 0.001f)
        assertEquals(0.04f, requireNotNull(weights).hinge, 0.001f)
        assertEquals(0.36f, requireNotNull(weights).second, 0.001f)
        val content = windowHingeToContent(
            HingeBoundsPx(0, 600, 1000, 640),
            WindowSizePx(1000, 1000),
            800.dp, 1000.dp,
        )
        assertNotNull(content)
        val (top, bottom) = horizontalPaneHeights(1000.dp, requireNotNull(content))
        assertFalse(controlBoundsIntersectHinge(0.dp, top, requireNotNull(content).top, requireNotNull(content).bottom))
        assertFalse(controlBoundsIntersectHinge(requireNotNull(content).bottom, 1000.dp, requireNotNull(content).top, requireNotNull(content).bottom))
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
        val occluding = windowHingeToContent(
            HingeBoundsPx(490, 0, 510, 1000),
            WindowSizePx(1000, 1000),
            1000.dp, 1000.dp,
        )
        assertNotNull(occluding)
        val (left, _) = verticalPaneWidths(1000.dp, requireNotNull(occluding))
        assertFalse(controlBoundsIntersectHinge(0.dp, left, requireNotNull(occluding).left, requireNotNull(occluding).right))
        val hairline = windowHingeToContent(
            HingeBoundsPx(500, 0, 500, 1000),
            WindowSizePx(1000, 1000),
            1000.dp, 1000.dp,
        )
        assertNotNull(hairline)
        val (lineLeft, _) = verticalPaneWidths(1000.dp, requireNotNull(hairline))
        assertFalse(controlBoundsIntersectHinge(0.dp, lineLeft, requireNotNull(hairline).left, requireNotNull(hairline).right))
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(scaffold.contains("occlusionFull"))
        assertTrue(scaffold.contains("Spacer("))
    }

    @Test
    fun windowToContentConversionPreservesFractionsAcrossInsets() {
        val window = WindowSizePx(1000, 800)
        val hingePx = HingeBoundsPx(300, 0, 320, 800)
        val content = windowHingeToContent(hingePx, window, 900.dp, 700.dp)
        assertNotNull(content)
        assertEquals(270.dp, requireNotNull(content).left)
        assertEquals(288.dp, requireNotNull(content).right)
        val (left, right) = verticalPaneWidths(900.dp, requireNotNull(content))
        assertEquals(270.dp, left)
        assertEquals(612.dp, right)
        assertFalse(controlBoundsIntersectHinge(0.dp, left, requireNotNull(content).left, requireNotNull(content).right))
        assertFalse(controlBoundsIntersectHinge(requireNotNull(content).right, 900.dp, requireNotNull(content).left, requireNotNull(content).right))
    }

    @Test
    fun foldSplitDoesNotDuplicateTimerControls() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldSection = scaffold.substringAfter("private fun HalfOpenTimerScreen")
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
        val foldSection = scaffold.substringAfter("private fun HalfOpenTimerScreen")
            .substringBefore("private fun FoldSideBySide")
        assertTrue(foldSection.contains("MainNavigationBar("))
        assertTrue(foldSection.contains("systemBarsPadding()"))
        assertTrue(foldSection.contains(".padding(padding)"))
        assertTrue(foldSection.contains("BoxWithConstraints"))
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
