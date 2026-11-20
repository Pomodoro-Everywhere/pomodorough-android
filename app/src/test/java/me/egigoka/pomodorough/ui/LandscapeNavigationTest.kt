package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R43-A05: landscape Timer must keep navigation outside the
// orientation-specific timer content so every destination stays
// reachable without rotating. AND-26: the navigation surface comes from
// the window size alone, so every tab shows the same one.
class LandscapeNavigationTest {
    @Test
    fun wideOrShortWindowsEarnSideRail() {
        // Landscape phones, the small audit phone turned, tablets both ways,
        // an unfolded foldable, and a nearly square wide window.
        listOf(
            891.dp to 411.dp,
            569.dp to 320.dp,
            800.dp to 1280.dp,
            1280.dp to 800.dp,
            673.dp to 841.dp,
            800.dp to 700.dp,
        ).forEach { (width, height) ->
            assertEquals("$width x $height", MainNavigationStyle.Rail, mainNavigationStyle(width, height))
        }
    }

    @Test
    fun compactPortraitPhonesKeepBottomBar() {
        listOf(320.dp to 569.dp, 411.dp to 891.dp, 599.dp to 960.dp).forEach { (width, height) ->
            assertEquals("$width x $height", MainNavigationStyle.Bar, mainNavigationStyle(width, height))
        }
    }

    @Test
    fun foldSplitKeepsBottomBarUnderItsPanes() {
        assertEquals(MainNavigationStyle.Bar, mainNavigationStyle(1280.dp, 800.dp, foldSplit = true))
    }

    @Test
    fun everyTabSharesOneNavigationScaffold() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val layout = scaffold.substringAfter("private fun TimerScreenLayout(")
            .substringBefore("private fun MainScreenScaffold(")
        assertTrue(layout.contains("MainScreenScaffold(mainNavigationStyle("))
        assertTrue(layout.contains("LandscapeTimerScreen("))
        assertTrue(layout.contains("SecondaryTabList("))
        assertTrue(layout.contains("PortraitTimerTab("))
        val wrapper = scaffold.substringAfter("private fun MainScreenScaffold(")
            .substringBefore("internal data class FoldWindowState")
        assertTrue(wrapper.contains("MainNavigationBar(") && wrapper.contains("MainNavigationRail("))
        assertTrue(wrapper.contains("activeTab"))
        assertFalse(scaffold.contains("PortraitScreenScaffold"))
        assertFalse(scaffold.contains("LandscapeTimerScaffold"))
    }

    @Test
    fun everyDestinationReachableFromBothSurfaces() {
        val content = productionText("me/egigoka/pomodorough/ui/TimerScreenContent.kt")
        // Both navigation surfaces iterate every destination from one source.
        assertTrue(content.contains("MainNavigationBar(") && content.contains("MainTab.entries"))
        assertTrue(content.contains("MainNavigationRail(") && content.contains("items(MainTab.entries"))
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
