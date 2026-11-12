@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package me.egigoka.pomodorough.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF111923)
val Violet = Color(0xFF142C5C)
val Butter = Color(0xFFF5D05B)
val Lavender = Color(0xFF8FA8B8)
val Cloud = Color(0xFFE8F0F1)
val Danger = Color(0xFFFF604F)
val DangerAccent = Color(0xFF8F2424)
val DangerText = Color(0xFF681515)

// Railway signage face. Android ships Roboto Condensed as the
// "sans-serif-condensed" system family, so headings get the narrow
// station-board look without bundled font assets. A device without
// the family falls through to the default sans face.
internal val Signage = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold),
)

internal val LocalPomodoroughDarkTheme = staticCompositionLocalOf { false }

private val lightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Cloud,
    primaryContainer = Lavender,
    onPrimaryContainer = Ink,
    secondary = Danger,
    onSecondary = Ink,
    secondaryContainer = Cloud,
    onSecondaryContainer = Ink,
    tertiary = Butter,
    onTertiary = Ink,
    tertiaryContainer = Butter,
    onTertiaryContainer = Ink,
    background = Cloud,
    onBackground = Ink,
    surface = Cloud,
    onSurface = Ink,
    surfaceVariant = Lavender,
    onSurfaceVariant = Ink,
    outline = Violet,
    outlineVariant = Lavender,
    error = DangerText,
    onError = Cloud,
    errorContainer = Danger,
    onErrorContainer = Ink,
    // Snackbars paint the inverse roles. Left unset they fall back to
    // the Material baseline purple-greys, which are not app colours.
    inverseSurface = Ink,
    inverseOnSurface = Cloud,
    inversePrimary = Butter,
)

private val darkColors = darkColorScheme(
    // Dark primary is a light blue, so content on it is Ink (9.0:1).
    // White on it is 2.0:1.
    primary = Color(0xFF9CB8FF),
    onPrimary = Ink,
    primaryContainer = Color(0xFF243F73),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFFFB4AA),
    onSecondary = Color.White,
    secondaryContainer = DangerAccent,
    onSecondaryContainer = Color.White,
    tertiary = Butter,
    onTertiary = Color.White,
    // Dark focus cards sit on platform navy, the same station wall
    // the web and Apple clients paint behind the timer.
    tertiaryContainer = Violet,
    onTertiaryContainer = Color.White,
    background = Color(0xFF0D1722),
    onBackground = Color.White,
    surface = Color(0xFF0D1722),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF23313D),
    onSurfaceVariant = Color.White,
    outline = Color(0xFF91A7B5),
    outlineVariant = Color(0xFF41515E),
    error = Color(0xFFFFB4AB),
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color.White,
    inverseSurface = Cloud,
    inverseOnSurface = Ink,
    inversePrimary = Violet,
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Signage,
        fontWeight = FontWeight.Black,
        fontSize = 58.sp,
        lineHeight = 56.sp,
        letterSpacing = (-2.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Signage,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Signage,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Signage,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(42.dp),
    largeIncreased = RoundedCornerShape(36.dp),
    extraLargeIncreased = RoundedCornerShape(50.dp),
    extraExtraLarge = RoundedCornerShape(64.dp),
)

@Composable
fun PomodoroughTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPomodoroughDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) darkColors else lightColors,
            // Styles carry no colour: text takes the content colour of the
            // surface it sits on, so one typography serves both themes.
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
