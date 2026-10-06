package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R43-A05: landscape Timer must keep navigation outside the
// orientation-specific timer content so every destination stays
// reachable without rotating.
class LandscapeNavigationTest {
    @Test
    fun clearlyWideLandscapeEarnsSideRail() {
        assertEquals(
            LandscapeNavigationStyle.Rail,
            landscapeNavigationStyle(800.dp, 400.dp),
        )
        assertEquals(
            LandscapeNavigationStyle.Rail,
            landscapeNavigationStyle(1200.dp, 800.dp),
        )
    }

    @Test
    fun barelyWideLandscapeKeepsBottomBar() {
        assertEquals(
            LandscapeNavigationStyle.Bar,
            landscapeNavigationStyle(800.dp, 700.dp),
        )
    }

    @Test
    fun navigationChoiceDerivesFromProportionsNotFixedWidth() {
        val posture = productionText("me/egigoka/pomodorough/ui/TimerPosture.kt")
        assertTrue(posture.contains("fun landscapeNavigationStyle("))
        assertTrue(!posture.contains("600.dp"))
        assertTrue(!posture.contains("840.dp"))
    }

    @Test
    fun landscapeTimerKeepsNavigationOutsideContent() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "landscape Timer branch must wire navigation (bar or rail) with tab selection",
            landscapeBranchWiresNavigation(scaffold),
        )
    }

    @Test
    fun everyDestinationReachableFromLandscapeTimer() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val content = productionText("me/egigoka/pomodorough/ui/TimerScreenContent.kt")
        assertTrue(
            "landscape navigation must expose every MainTab destination",
            landscapeExposesEveryDestination(scaffold, content),
        )
    }

    private fun landscapeBranchWiresNavigation(scaffold: String): Boolean {
        val branch = scaffold.substringAfter("TimerLayoutDecision.Landscape")
        // Bound the check to the landscape branch so portrait/half-open
        // navigation cannot satisfy it.
        val bounded = branch.substringBefore("TimerLayoutDecision.Portrait")
        val wiresSelection = bounded.contains("onSelectTab") && bounded.contains("activeTab")
        if (!wiresSelection) return false
        val directWithNav = bounded.contains("LandscapeTimerScreen(") &&
            (bounded.contains("MainNavigationBar(") || bounded.contains("MainNavigationRail("))
        if (directWithNav) return true
        // The branch may delegate to a wrapper; the wrapper must render
        // the timer content beside a bar or rail with the tab selection.
        if (!bounded.contains("LandscapeTimerScaffold(")) return false
        val wrapper = scaffold.substringAfter("fun LandscapeTimerScaffold")
            .substringBefore("private data class FoldWindowState")
        return wrapper.contains("LandscapeTimerScreen(") &&
            (wrapper.contains("MainNavigationBar(") || wrapper.contains("MainNavigationRail(")) &&
            wrapper.contains("activeTab") && wrapper.contains("onSelectTab")
    }

    private fun landscapeExposesEveryDestination(scaffold: String, content: String): Boolean {
        val branch = scaffold.substringAfter("TimerLayoutDecision.Landscape")
        val bounded = branch.substringBefore("TimerLayoutDecision.Portrait")
        val wiresSelection = bounded.contains("onSelectTab") && bounded.contains("activeTab")
        if (!wiresSelection) return false
        // Both navigation surfaces iterate every destination from one source.
        val barCoversAll = content.contains("MainNavigationBar(") && content.contains("MainTab.entries")
        val railCoversAll = content.contains("MainNavigationRail(") && content.contains("MainTab.entries")
        return barCoversAll && railCoversAll
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
