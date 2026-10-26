package com.vabugeode.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vabugeode.app.model.Satellite
import com.vabugeode.app.ui.theme.Palette

/**
 * Столбики силы сигнала спутников (SNR).
 * Используемые в решении — яркие, остальные — бледные. GPS янтарный, BeiDou голубой.
 * При подмене столбики подозрительно одинаковые — это видно глазом.
 */
@Composable
fun SnrBars(sats: List<Satellite>, modifier: Modifier = Modifier, showLabels: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (sats.isEmpty()) return@Canvas
        val labelH = if (showLabels) 14.dp.toPx() else 0f
        val gap = 3.dp.toPx()
        val n = sats.size
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtMost(22.dp.toPx())
        val maxSnr = 50f
        val h = size.height - labelH
        sats.forEachIndexed { i, s ->
            val color = if (s.system == "GPS") Palette.Geo else Palette.Cold
            val alpha = if (s.used) 1f else 0.3f
            val bh = (s.snr / maxSnr).coerceIn(0.03f, 1f) * h
            val x = i * (bw + gap)
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(x, h - bh),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            if (showLabels) {
                val tl = measurer.measure("${s.prn}", TextStyle(color = Palette.TextFaint, fontSize = 9.sp))
                drawText(tl, topLeft = Offset(x + (bw - tl.size.width) / 2, h + 2.dp.toPx()))
            }
        }
    }
}
