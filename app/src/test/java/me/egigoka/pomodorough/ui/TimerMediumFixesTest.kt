package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMediumFixesTest {
    @Test
    fun secondaryWidthCapIs840() {
        assertEquals(840.dp, SecondaryTabMaxWidth)
    }

    @Test
    fun secondaryListIsCenteredWithWidthCap() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(scaffold.contains("SecondaryTabMaxWidth"))
        assertTrue(scaffold.contains("widthIn(max = SecondaryTabMaxWidth)"))
        assertTrue(scaffold.contains("Alignment.TopCenter"))
    }

    @Test
    fun orbitDeductionGrowsWithFontAndMessages() {
        val base = portraitOrbitDeduction(1f, false)
        assertEquals(230.dp, base)
        assertTrue(portraitOrbitDeduction(1.3f, false) > base)
        assertTrue(portraitOrbitDeduction(2f, false) > portraitOrbitDeduction(1.3f, false))
        assertTrue(portraitOrbitDeduction(1f, true) > base)
    }

    @Test
    fun orbitSizeShrinksWithHeaderPressure() {
        val roomy = portraitOrbitSize(500.dp, 600.dp, 1f, false)
        val largeFont = portraitOrbitSize(500.dp, 600.dp, 1.3f, false)
        val withMessages = portraitOrbitSize(500.dp, 600.dp, 1f, true)
        assertTrue(largeFont < roomy)
        assertTrue(withMessages < roomy)
    }

    @Test
    fun orbitSizeStaysInReadableBand() {
        assertEquals(318.dp, portraitOrbitSize(1200.dp, 1200.dp, 1f, false))
        assertEquals(132.dp, portraitOrbitSize(300.dp, 400.dp, 2f, true))
        assertTrue(portraitOrbitSize(800.dp, 320.dp, 1f, false) <= 318.dp)
    }

    @Test
    fun portraitHeroKeepsScrollAndNoDummyGesture() {
        val hero = productionText("me/egigoka/pomodorough/ui/TimerHero.kt")
        assertTrue(hero.contains("private fun PortraitTimerHero"))
        assertTrue(hero.contains("verticalScroll(rememberScrollState())"))
        assertTrue(!hero.contains("rememberScrollableState"))
        assertTrue(!hero.contains(".scrollable("))
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(!scaffold.contains("rememberScrollableState"))
        assertTrue(!scaffold.contains(".scrollable("))
        val content = productionText("me/egigoka/pomodorough/ui/TimerScreenContent.kt")
        assertTrue(!content.contains("rememberScrollableState"))
        assertTrue(!content.contains(".scrollable("))
        assertTrue(!content.contains("- 230.dp).coerceIn"))
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
