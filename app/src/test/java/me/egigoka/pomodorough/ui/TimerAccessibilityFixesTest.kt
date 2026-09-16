package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerAccessibilityFixesTest {
    @Test
    fun orbitFontCompensatesLargeFontScale() {
        val normal = orbitTimeFontSize(318.dp, 1f)
        val large = orbitTimeFontSize(318.dp, 1.3f)
        val largest = orbitTimeFontSize(318.dp, 2f)
        assertTrue(large < normal)
        assertTrue(largest < large)
        assertTrue(largest >= 24.sp)
    }

    @Test
    fun orbitFontKeepsSizeOrdering() {
        assertTrue(orbitTimeFontSize(318.dp, 1f) > orbitTimeFontSize(200.dp, 1f))
        assertTrue(orbitTimeFontSize(200.dp, 1.3f) < orbitTimeFontSize(200.dp, 1f))
    }

    @Test
    fun letterSpacingRelaxesAtLargeScale() {
        assertTrue(orbitTimeLetterSpacing(1f) < orbitTimeLetterSpacing(1.3f))
        assertTrue(landscapeReadoutLetterSpacing(1f) < landscapeReadoutLetterSpacing(1.3f))
        assertTrue(landscapeReadoutLetterSpacing(2f) >= (-2).sp)
    }

    @Test
    fun taskRowWeightsProtectMetricsWhenNarrow() {
        val narrow = taskRowWeights(isNarrow = true)
        val wide = taskRowWeights(isNarrow = false)
        assertTrue(narrow.finished + narrow.time >= wide.finished + wide.time)
        assertTrue(narrow.title <= narrow.finished + narrow.time + 0.01f)
        assertTrue(wide.title <= wide.finished + wide.time + 0.51f)
    }

    @Test
    fun navLabelsStayVisibleWithCompactSize() {
        assertTrue(shouldCompactNavLabels(1.3f))
        assertTrue(!shouldCompactNavLabels(1f))
        assertTrue(navLabelFontSize(1.3f) == 10.sp)
        assertTrue(navLabelFontSize(1f) == androidx.compose.ui.unit.TextUnit.Unspecified)
    }

    @Test
    fun portraitHeroScrollsAndEllipsizes() {
        val hero = productionText("me/egigoka/pomodorough/ui/TimerHero.kt")
        assertTrue(hero.contains("private fun PortraitTimerHero"))
        assertTrue(hero.contains("verticalScroll(rememberScrollState())"))
        assertTrue(hero.contains("orbitTimeFontSize("))
        assertTrue(hero.contains("orbitTimeLetterSpacing("))
        assertTrue(hero.contains("landscapeReadoutLetterSpacing("))
        assertTrue(hero.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(!hero.contains("if (orbitSize < 280.dp) 48.sp else 62.sp"))
    }

    @Test
    fun taskRowsEnforceTouchTargetAndRebalancedWeights() {
        val views = productionText("me/egigoka/pomodorough/ui/TaskViews.kt")
        assertTrue(views.contains("heightIn(min = 48.dp)"))
        assertTrue(views.contains("taskRowWeights("))
        assertTrue(views.contains("widthIn(min = 44.dp)"))
        assertTrue(!views.contains("weight(4.55f)"))
        assertTrue(!views.contains("weight(2f)"))
    }

    @Test
    fun navBarKeepsLabelsAtLargeFontScale() {
        val content = productionText("me/egigoka/pomodorough/ui/TimerScreenContent.kt")
        assertTrue(content.contains("shouldCompactNavLabels("))
        assertTrue(content.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(!content.contains("val showLabels"))
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
