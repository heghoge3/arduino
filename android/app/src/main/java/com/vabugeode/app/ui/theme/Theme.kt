package com.vabugeode.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vabugeode.app.R

/**
 * Стиль «Терминал · лёд»: тёмно-оранжевый фон, холодный голубой текст,
 * у каждого блока свой цвет по смыслу (температура тёплая, тревога красная, питание зелёное).
 * Имена полей прежние — экраны перекрашиваются без правок.
 */
object Palette {
    var light by androidx.compose.runtime.mutableStateOf(false)

    val Bg get() = if(light) Color(0xFFFFF0DB) else Color(0xFF29180F)
    val Surface get() = if(light) Color(0xFFFFF7E9) else Color(0xFF322015)      // фон блоков почти совпадает с фоном: блоки держат рамки
    val SurfaceHigh get() = if(light) Color(0xFFF0DCC1) else Color(0xFF402B1D)
    val Outline get() = if(light) Color(0xFF967658) else Color(0xFF806047)
    val Text get() = if(light) Color(0xFF302014) else Color(0xFFFFEFDC)
    val TextDim get() = if(light) Color(0xFF654B35) else Color(0xFFD4BDA6)
    val TextFaint get() = if(light) Color(0xFF765A40) else Color(0xFFBFA48B)

    val Geo get() = if(light) Color(0xFF9F430D) else Color(0xFFFFAD66)          // основной цвет интерфейса — «GEO» в логотипе
    // Каждый цвет — свой оттенок по кругу, без повторов: красный, оранжевый, жёлтый, салатовый,
    // зелёный, бирюзовый, голубой (Geo), синий, фиолетовый
    val Good get() = if(light) Color(0xFF286843) else Color(0xFF4CD48A)         // зелёный: всё хорошо, питание
    val Warn get() = if(light) Color(0xFF805100) else Color(0xFFFFD166)         // жёлтый: внимание
    val Bad = Color(0xFFFF5E57)          // красный: тревога
    val BadBg = Color(0xFF1D0C10)
    val BadText = Color(0xFFFFB8B0)

    val Temp get() = if(light) Color(0xFF9F430D) else Color(0xFFFFA54C)         // температура — оранжевая
    val Cold = Color(0xFF5AA9FF)         // холод после заката — синий
    val Pressure get() = if(light) Color(0xFF694795) else Color(0xFFA38CFF)     // фиолетовый
    val Humidity get() = if(light) Color(0xFF186971) else Color(0xFF36CFC9)     // бирюзовый
    val Altitude = Color(0xFFB8E35A)     // салатовый
    val Battery get() = if(light) Color(0xFF286843) else Color(0xFF4CD48A)      // зелёный, как «всё хорошо»
    val Sunset = Color(0xFFFF7A45)       // закат — красно-оранжевый (вместо розового)
    val SunsetSun = Color(0xFFFF7A45)    // солнышко заката
    val Sunrise = Color(0xFFFFD166)      // солнышко восхода — жёлтое
    val Pdr = Color(0xFFD69BFF)          // счисление по шагам на карте — сиреневый
    val Pink = Color(0xFFFFAD66)         // имя осталось от старой палитры; розового больше нет

    val Scan = Color(0x06FFB877)         // сканлайны поверх экрана
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

private val scheme get() = darkColorScheme(
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
    val context=androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { Palette.light=context.getSharedPreferences("appearance",0).getBoolean("light",false) }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
