package com.vabugeode.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vabugeode.app.model.Satellite
import com.vabugeode.app.ui.theme.Palette
import kotlinx.coroutines.launch

private const val SLOTS = 16

/**
 * Сила сигнала спутников (SNR). Мест под столбики всегда одинаково — блок не прыгает:
 * спутников нет или GPS выключен — столбики серые и низкие. Высота меняется плавно.
 */
@Composable
fun SnrBars(sats: List<Satellite>, modifier: Modifier = Modifier, showLabels: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val n = maxOf(SLOTS, sats.size)
    val heights = remember(n) { List(n) { Animatable(0.06f) } }
    LaunchedEffect(sats, n) {
        heights.forEachIndexed { i, a ->
            val target = sats.getOrNull(i)?.let { (it.snr / 50f).coerceIn(0.06f, 1f) } ?: 0.06f
            launch { a.animateTo(target, tween(450)) }
        }
    }
    Canvas(modifier) {
        val labelH = if (showLabels) 14.dp.toPx() else 0f
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val h = size.height - labelH
        for (i in 0 until n) {
            val s = sats.getOrNull(i)
            val color = when {
                s == null -> Palette.Outline
                s.system == "GPS" -> Palette.Geo
                else -> Palette.Cold
            }
            val alpha = if (s == null || s.used) 1f else 0.35f
            val bh = heights[i].value * h
            val x = i * (bw + gap)
            drawRect(color.copy(alpha = alpha), topLeft = Offset(x, h - bh), size = Size(bw, bh))
            if (showLabels && s != null) {
                val tl = measurer.measure("${s.prn}", TextStyle(color = Palette.TextFaint, fontSize = 9.sp))
                drawText(tl, topLeft = Offset(x + (bw - tl.size.width) / 2, h + 2.dp.toPx()))
            }
        }
    }
}
