package com.vabugeode.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vabugeode.app.ui.theme.Fonts
import com.vabugeode.app.ui.theme.Palette

/**
 * Логотип: «vabu[GEO]de», «GEO» — инверсный блок, как курсор терминала.
 * Живой, но спокойный: по блоку медленно проходит блик, яркость чуть «дышит»,
 * а раз в 5–10 с — короткий сбой в пару кадров (сдвиг и цветные двоения, как у старого монитора).
 */
@Composable
fun Logo(size: TextUnit = 30.sp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "logo")
    val sweep by inf.animateFloat(
        -0.6f, 1.6f,
        infiniteRepeatable(tween(2600, delayMillis = 4200, easing = LinearEasing)), label = "sweep",
    )
    val breathe by inf.animateFloat(0.88f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    var glitch by remember { mutableStateOf(0) }  // 0 — нет; иначе номер кадра сбоя
    LaunchedEffect(Unit) {
        val rnd = kotlin.random.Random(System.nanoTime())
        while (true) {
            delay(5000L + rnd.nextLong(5000))
            repeat(3) { i -> glitch = i + 1; delay(55) }
            glitch = 0
        }
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.Geo)) { append("vabu") }
        withStyle(SpanStyle(color = Palette.Bg, background = Palette.Geo)) { append("GEO") }
        withStyle(SpanStyle(color = Palette.Geo)) { append("de") }
    }
    val jitter = when (glitch) { 1 -> 2.dp; 2 -> (-1.5).dp; 3 -> 1.dp; else -> 0.dp }
    Box(modifier) {
        if (glitch != 0) {
            // Цветные двоения: красный влево, бирюзовый вправо
            Text("vabuGEOde", fontFamily = Fonts.Pixel, fontSize = size, letterSpacing = 1.sp, color = Palette.Pink.copy(alpha = 0.55f),
                modifier = Modifier.offset(x = jitter - 2.dp))
            Text("vabuGEOde", fontFamily = Fonts.Pixel, fontSize = size, letterSpacing = 1.sp, color = Palette.Humidity.copy(alpha = 0.55f),
                modifier = Modifier.offset(x = jitter + 2.dp, y = 1.dp))
        }
        Text(
            text,
            fontFamily = Fonts.Pixel,
            fontSize = size,
            letterSpacing = 1.sp,
            modifier = Modifier
                .offset(x = jitter)
                .graphicsLayer { alpha = breathe }
                .drawWithContent {
                    drawContent()
                    // Блик: светлая косая полоса проходит слева направо
                    val x = this.size.width * sweep
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0f)),
                            start = Offset(x - 40f, 0f), end = Offset(x + 40f, this.size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}

/**
 * Блок-рамка терминала. title — подпись в разрыве верхней линии (как legend у fieldset).
 * accent — цвет рамки и подписи по смыслу блока; fill — заливка (тревога); thick — рамка 2 dp.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    title: String? = null,
    titleRight: String? = null,
    fill: Color? = null,
    thick: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val line = accent ?: Palette.Outline
    Box(modifier) {
        Column(
            Modifier
                .padding(top = if (title != null || titleRight != null) 7.dp else 0.dp)
                .fillMaxWidth()
                .background(fill ?: Palette.Bg)
                .border(if (thick) 2.dp else 1.dp, line)
                .padding(padding)
                .padding(top = if (title != null || titleRight != null) 4.dp else 0.dp),
            content = content,
        )
        if (title != null) Legend(title, accent ?: Palette.TextDim, fill = thick && fill != null, Modifier.padding(start = 10.dp))
        if (titleRight != null) {
            Legend(titleRight, accent ?: Palette.TextDim, fill = false, Modifier.align(Alignment.TopEnd).padding(end = 10.dp))
        }
    }
}

@Composable
private fun Legend(text: String, color: Color, fill: Boolean, modifier: Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (fill) Palette.Bg else color,
        modifier = modifier
            .background(if (fill) color else Palette.Bg)
            .padding(horizontal = 6.dp),
    )
}

/** Подпись секции капсом. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/** Текст, который мягко сменяется при изменении: новое значение приплывает снизу и проявляется */
@Composable
fun AnimatedValue(text: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith fadeOut(tween(150))
        },
        label = "value",
    ) { content(it) }
}

/** Крупное число шрифтом VT323 + единицы мелко. Всегда в одну строку — не переносится и не прыгает */
@Composable
fun BigValue(value: String, unit: String, color: Color, size: TextUnit = 42.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedValue(value) {
            Text(it, fontFamily = Fonts.Pixel, fontSize = size, lineHeight = size, color = color, maxLines = 1, softWrap = false)
        }
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * Плитка показателя фиксированного размера: число (кегль уменьшается для длинных значений)
 * и две строки подписи — всегда по одной строке, с многоточием.
 */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String = "",
    sub2: String = "",
    subColor: Color = Palette.TextDim,
) {
    val len = value.length + unit.length
    val size = when {
        len <= 6 -> 42.sp
        len <= 8 -> 34.sp
        else -> 28.sp
    }
    Panel(modifier, accent = color, title = label, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BigValue(value, unit, color, size) }
            Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
        Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub2, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .border(1.dp, color.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.6f))
    }
}

/** Статус строкой терминала: «● ЗАПИСЬ» (или «[ТЕКСТ]» без точки). blink — точка мигает, как курсор */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true, blink: Boolean = false) {
    val a = if (blink) {
        val inf = rememberInfiniteTransition(label = "blink")
        inf.animateFloat(1f, 0.15f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "dot").value
    } else 1f
    Text(
        buildAnnotatedString {
            if (dot) {
                withStyle(SpanStyle(color = color.copy(alpha = a))) { append("● ") }
                append(text.uppercase())
            } else append("[${text.uppercase()}]")
        },
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Сегментная полоска (заряд, SD): ████████░░░ */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, segments: Int = 20) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "meter")
    val filled = (animated * segments).toInt()
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .background(if (i < filled) color else color.copy(alpha = 0.15f)),
            )
        }
    }
}

/** Кнопка-рамка в стиле терминала; selected — инверсный блок (выбранная вкладка) */
@Composable
fun TermButton(text: String, color: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Palette.Bg else color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, color.copy(alpha = if (selected) 1f else 0.7f))
            .background(if (selected) color else color.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Заглушка, пока данных с устройства ещё нет. */
@Composable
fun Waiting(contentPadding: PaddingValues, text: String = "Ждём данные с устройства…") {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Строка «подпись — значение». */
@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}
