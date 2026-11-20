package me.egigoka.pomodorough.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AND-31: secondary text on the phase cards keeps 4.5:1 against its card once its alpha is
 * blended in. The old 72% gave Ink on Lavender 4.21:1.
 */
class SecondaryTextContrastTest {
    @Test
    fun secondaryTextMeetsTheTextMinimumOnEveryPhaseCard() {
        val pairs = listOf(
            "Ink on Lavender" to (Ink to Lavender),
            "Cloud on Violet" to (Cloud to Violet),
            "White on Violet" to (Color.White to Violet),
            "Ink on Butter" to (Ink to Butter),
        )
        pairs.forEach { (name, colours) ->
            val (text, card) = colours
            val shown = text.copy(alpha = SecondaryTextAlpha).compositeOver(card)
            val ratio = contrastRatio(shown, card)
            assertTrue("$name at $SecondaryTextAlpha is $ratio:1", ratio >= 4.5)
        }
    }
}
