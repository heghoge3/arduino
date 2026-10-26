package com.vabugeode.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.vabugeode.app.ui.theme.Palette

/** Логотип: «vabu» и «de» светлые, «GEO» жирным янтарным. */
@Composable
fun Logo(size: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Light)) { append("vabu") }
            withStyle(SpanStyle(color = Palette.Geo, fontWeight = FontWeight.Black)) { append("GEO") }
            withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Light)) { append("de") }
        },
        fontSize = size,
        letterSpacing = 0.5.sp,
        modifier = modifier,
    )
}

/** Карточка-подложка. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: Palette.Outline, shape)
            .padding(padding),
        content = content,
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

/** Плитка показателя: иконка, подпись, крупное значение, единицы и подстрочник. */
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
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 28.dp)
            Spacer(Modifier.width(8.dp))
            SectionLabel(label)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(bottom = 5.dp))
        }
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 2)
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.6f))
    }
}

/** Цветная «пилюля» со статусом. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Полоска прогресса (заряд, SD). */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Palette.SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
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
