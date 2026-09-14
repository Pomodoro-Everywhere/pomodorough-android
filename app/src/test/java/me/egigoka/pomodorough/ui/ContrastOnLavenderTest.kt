package me.egigoka.pomodorough.ui

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastOnLavenderTest {
    @Test
    fun inkOnLavenderPassesAaInBothThemes() {
        assertTrue(contrastRatio(Ink, Lavender) >= 4.5)
        assertTrue(contrastRatio(textColorOnLavender(), Lavender) >= 4.5)
    }

    @Test
    fun whiteOnLavenderFailsAaDocumentingA77() {
        assertTrue(contrastRatio(Color.White, Lavender) < 4.5)
    }

    @Test
    fun lavenderDecisionKeepsInk() {
        assertEquals(Ink, textColorOnLavender())
    }

    @Test
    fun lavenderSurfacesUseContainerAwareColor() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        val base = requireNotNull(root)
        val hero = File(base, "me/egigoka/pomodorough/ui/TimerHero.kt").readText()
        val content = File(base, "me/egigoka/pomodorough/ui/TimerScreenContent.kt").readText()
        val pattern = File(base, "me/egigoka/pomodorough/ui/PatternHistoryViews.kt").readText()
        val components = File(base, "me/egigoka/pomodorough/ui/UiComponents.kt").readText()
        assertTrue(hero.contains("contentColorForContainer(palette.container)"))
        assertTrue(content.contains("contentColorForContainer(syncColor("))
        assertTrue(pattern.contains("contentColorForContainer(palette.container)"))
        assertTrue(components.contains("contentColorForContainer(containerColor)"))
    }
}
