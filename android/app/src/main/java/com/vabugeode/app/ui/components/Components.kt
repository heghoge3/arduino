package com.vabugeode.app.ui.components

import androidx.compose.foundation.background
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

/** Логотип: «vabu[GEO]de», «GEO» — инверсный блок, как курсор терминала. */
@Composable
fun Logo(size: TextUnit = 30.sp, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Palette.Geo)) { append("vabu") }
            withStyle(SpanStyle(color = Palette.Bg, background = Palette.Geo)) { append("GEO") }
            withStyle(SpanStyle(color = Palette.Geo)) { append("de") }
        },
        fontFamily = Fonts.Pixel,
        fontSize = size,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
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

/** Крупное число шрифтом VT323 + единицы мелко. */
@Composable
fun BigValue(value: String, unit: String, color: Color, size: TextUnit = 42.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontFamily = Fonts.Pixel, fontSize = size, lineHeight = size, color = color)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Плитка показателя: рамка и число в цвете показателя, подпись тусклая. */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subColor: Color = Palette.TextDim,
) {
    Panel(modifier, accent = color, title = label, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(value, unit, color)
            Spacer(Modifier.weight(1f))
            Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 2)
        }
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

/** Статус строкой терминала: «● ЗАПИСЬ» (или «[ТЕКСТ]» без точки). */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Text(
        if (dot) "● ${text.uppercase()}" else "[${text.uppercase()}]",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Сегментная полоска (заряд, SD): ████████░░░ */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, segments: Int = 20) {
    val filled = (fraction.coerceIn(0f, 1f) * segments).toInt()
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
