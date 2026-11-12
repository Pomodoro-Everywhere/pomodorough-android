package me.egigoka.pomodorough.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

// The smallest size a one-line text may scale down to before it would have to
// lose letters. At the largest system text this is still twice as large on screen.
private val SingleLineSmallestFontSize = 6.sp

private const val SingleLineFontStep = 0.25f

// Used by short labels that must stay on one line: the navigation labels and
// the landscape timer's context label. A text that fits keeps its style. One
// that does not first loses its letter spacing (labelMedium's fixed 0.8 sp gap
// per letter alone kept "Arrivals" wider than its item at the largest text),
// then takes the largest size, down to SingleLineSmallestFontSize, at which its
// one-line width fits maxWidth.
@Composable
internal fun fittedSingleLineStyle(text: String, style: TextStyle, maxWidth: Int): TextStyle {
    val measurer = rememberTextMeasurer()
    return remember(text, style, maxWidth) {
        fun fits(candidate: TextStyle) =
            measurer.measure(text, candidate, maxLines = 1, softWrap = false).size.width <= maxWidth
        if (fits(style)) return@remember style
        val unspaced = style.copy(letterSpacing = 0.sp)
        var size = style.fontSize.value
        val smallest = SingleLineSmallestFontSize.value
        while (size > smallest && !fits(unspaced.copy(fontSize = size.sp))) {
            size = maxOf(smallest, size - SingleLineFontStep)
        }
        unspaced.copy(fontSize = size.sp)
    }
}
