@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package me.egigoka.pomodorough.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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

private val colors = lightColorScheme(
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
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 58.sp,
        lineHeight = 56.sp,
        letterSpacing = (-2.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
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
fun PomodoroughTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
