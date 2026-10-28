package com.vabugeode.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vabugeode.app.R

/**
 * Стиль «Терминал · лёд»: тёмно-синий фон, холодный голубой текст,
 * у каждого блока свой цвет по смыслу (температура тёплая, тревога красная, питание зелёное).
 * Имена полей прежние — экраны перекрашиваются без правок.
 */
object Palette {
    val Bg = Color(0xFF060A12)
    val Surface = Color(0xFF09101B)      // фон блоков почти совпадает с фоном: блоки держат рамки
    val SurfaceHigh = Color(0xFF0F1A2B)
    val Outline = Color(0xFF1E2D44)
    val Text = Color(0xFFD7EEFF)
    val TextDim = Color(0xFF8CA3BD)
    val TextFaint = Color(0xFF6A819E)

    val Geo = Color(0xFF9FD8FF)          // основной цвет интерфейса — «GEO» в логотипе
    // Каждый цвет — свой оттенок по кругу, без повторов: красный, оранжевый, жёлтый, салатовый,
    // зелёный, бирюзовый, голубой (Geo), синий, фиолетовый
    val Good = Color(0xFF4CD48A)         // зелёный: всё хорошо, питание
    val Warn = Color(0xFFFFD166)         // жёлтый: внимание
    val Bad = Color(0xFFFF5E57)          // красный: тревога
    val BadBg = Color(0xFF1D0C10)
    val BadText = Color(0xFFFFB8B0)

    val Temp = Color(0xFFFFA54C)         // температура — оранжевая
    val Cold = Color(0xFF5AA9FF)         // холод после заката — синий
    val Pressure = Color(0xFFA38CFF)     // фиолетовый
    val Humidity = Color(0xFF36CFC9)     // бирюзовый
    val Altitude = Color(0xFFB8E35A)     // салатовый
    val Battery = Color(0xFF4CD48A)      // зелёный, как «всё хорошо»
    val Sunset = Color(0xFFFF7A45)       // закат — красно-оранжевый (вместо розового)
    val SunsetSun = Color(0xFFFF7A45)    // солнышко заката
    val Sunrise = Color(0xFFFFD166)      // солнышко восхода — жёлтое
    val Pdr = Color(0xFFD69BFF)          // счисление по шагам на карте — сиреневый
    val Pink = Color(0xFF9FD8FF)         // имя осталось от старой палитры; розового больше нет

    val Scan = Color(0x08A0D8FF)         // сканлайны поверх экрана
}

/** Шрифты: VT323 — крупные цифры (кириллицы в нём нет), IBM Plex Mono — весь текст. */
object Fonts {
    val Pixel = FontFamily(Font(R.font.vt323))
    val Mono = FontFamily(
        Font(R.font.ibm_plex_mono, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_medium, FontWeight.SemiBold),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Bold),
    )
}

private val scheme = darkColorScheme(
    primary = Palette.Geo,
    onPrimary = Palette.Bg,
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
    displayLarge = TextStyle(fontFamily = Fonts.Pixel, fontSize = 96.sp, lineHeight = 80.sp),
    headlineMedium = TextStyle(fontFamily = Fonts.Mono, fontSize = 24.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = Fonts.Mono, fontSize = 19.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = Fonts.Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Fonts.Mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Fonts.Mono, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Fonts.Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Fonts.Mono, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Fonts.Mono, fontSize = 11.sp, letterSpacing = 1.sp),
)

/** Почти прямые углы у всего: диалоги, поля ввода, плавающие кнопки */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

@Composable
fun VabuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
