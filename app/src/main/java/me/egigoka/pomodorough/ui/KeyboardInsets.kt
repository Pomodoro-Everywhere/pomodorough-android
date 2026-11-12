package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

// UX round 2, B6 (AND-14): the window resizes for the keyboard instead of
// panning under the status bar (adjustResize in the manifest). Scrolling
// content then ends at the top of the keyboard. The scaffold padding is
// consumed first, so the navigation bar height is not added a second time.
internal fun Modifier.scaffoldContentAboveKeyboard(padding: PaddingValues): Modifier =
    padding(padding).consumeWindowInsets(padding).imePadding()
