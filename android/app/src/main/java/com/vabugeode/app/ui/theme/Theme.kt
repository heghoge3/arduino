package com.vabugeode.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра vabuGEOde: тёмная (не слепит ночью), янтарный акцент как у походного снаряжения. */
object Palette {
    val Bg = Color(0xFF0D1013)
    val Surface = Color(0xFF151A1F)
    val SurfaceHigh = Color(0xFF1D2329)
    val Outline = Color(0xFF29313A)
    val Text = Color(0xFFE9EDF0)
    val TextDim = Color(0xFF8B96A1)
    val TextFaint = Color(0xFF5A646E)

    val Geo = Color(0xFFFFA62B)      // акцент — «GEO» в логотипе
    val Good = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFC145)
    val Bad = Color(0xFFFF5D5D)

    val Temp = Color(0xFFFF8A5B)
    val Cold = Color(0xFF5AB8FF)
    val Pressure = Color(0xFFB794F6)
    val Humidity = Color(0xFF4FD1C5)
    val Altitude = Color(0xFF9AE66E)
    val Battery = Color(0xFFFFD166)
    val Sunset = Color(0xFFFF6B9A)
    val Pdr = Color(0xFF7FA7FF)
}

private val scheme = darkColorScheme(
    primary = Palette.Geo,
    onPrimary = Color(0xFF1A1100),
    secondary = Palette.Good,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Bad,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun VabuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
