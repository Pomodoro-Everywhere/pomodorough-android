package me.egigoka.pomodorough.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeReadoutSizingTest {
    @Test
    fun smallLandscapeShrinksBelowLegacyFixedSize() {
        val small = landscapeReadoutFontSize(320.dp, 568.dp)
        assertTrue(small < 112.sp)
        assertTrue(small <= 64.sp)
    }

    @Test
    fun fontStaysWithinReadableBand() {
        assertTrue(landscapeReadoutFontSize(200.dp, 320.dp) >= 40.sp)
        assertTrue(landscapeReadoutFontSize(800.dp, 1280.dp) <= 84.sp)
    }

    @Test
    fun largerBoxNeverShrinksFont() {
        val small = landscapeReadoutFontSize(320.dp, 568.dp)
        val large = landscapeReadoutFontSize(600.dp, 1024.dp)
        assertTrue(large >= small)
    }

    @Test
    fun heroDerivesFromConstraintsAndScrolls() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        val hero = File(requireNotNull(root), "me/egigoka/pomodorough/ui/TimerHero.kt").readText()
        assertTrue(hero.contains("landscapeReadoutFontSize(maxHeight, maxWidth)"))
        assertTrue(hero.contains("BoxWithConstraints"))
        assertTrue(hero.contains("verticalScroll(rememberScrollState())"))
    }
}
