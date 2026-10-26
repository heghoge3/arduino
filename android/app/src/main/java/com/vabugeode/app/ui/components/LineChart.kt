package com.vabugeode.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Ряд данных для графика: (время UTC мс, значение). */
data class ChartSeries(
    val points: List<Pair<Long, Double>>,
    val color: Color,
    val label: String,
    val dashed: Boolean = false,
    val fill: Boolean = true,
)

/**
 * Линейный график по времени. Умеет: линию заката и затенение ночи,
 * разрывы при пропусках данных, «ползунок» пальцем с подсказкой значения.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    unit: String = "",
    digits: Int = 1,
    sunsetMs: Long? = null,
    duskMs: Long? = null,
    showAxes: Boolean = true,
    interactive: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }

    val all = series.flatMap { it.points }
    if (all.size < 2) {
        Canvas(modifier) {}
        return
    }
    val t0 = all.minOf { it.first }
    val t1 = max(all.maxOf { it.first }, t0 + 60_000)
    var vMin = all.minOf { it.second }
    var vMax = all.maxOf { it.second }
    if (vMax - vMin < 1e-6) { vMin -= 1; vMax += 1 }
    val padV = (vMax - vMin) * 0.12
    vMin -= padV; vMax += padV

    val axisStyle = TextStyle(color = Palette.TextFaint, fontSize = 10.sp)
    val gestureMod = if (interactive) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            touchX = down.position.x
            var dx = 0f; var dy = 0f
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull() ?: break
                if (!c.pressed) break
                dx += abs(c.position.x - c.previousPosition.x)
                dy += abs(c.position.y - c.previousPosition.y)
                touchX = c.position.x
                // Забираем жест только если палец ведут по горизонтали — иначе страница скроллится
                if (dx > dy) c.consume()
            }
            touchX = null
        }
    } else Modifier

    Canvas(modifier.then(gestureMod)) {
        val left = if (showAxes) 40.dp.toPx() else 0f
        val bottom = if (showAxes) 20.dp.toPx() else 0f
        val top = if (showAxes) 18.dp.toPx() else 4.dp.toPx()
        val right = 6.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        fun x(t: Long) = left + ((t - t0).toFloat() / (t1 - t0).toFloat()) * w
        fun y(v: Double) = top + ((vMax - v) / (vMax - vMin)).toFloat() * h

        // Ночь после заката
        if (sunsetMs != null && sunsetMs < t1) {
            val xs = x(max(sunsetMs, t0))
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x00312A5C), Color(0x40312A5C)),
                    startX = xs, endX = x(duskMs ?: sunsetMs) + 1f,
                ),
                topLeft = Offset(xs, top), size = Size(x(duskMs ?: sunsetMs) - xs + 1f, h),
            )
            if (duskMs != null && duskMs < t1) {
                drawRect(Color(0x40312A5C), topLeft = Offset(x(duskMs), top), size = Size(left + w - x(duskMs), h))
            }
        }

        // Сетка и подписи по Y
        if (showAxes) {
            val step = niceStep((vMax - vMin) / 4)
            var v = ceil(vMin / step) * step
            while (v <= vMax) {
                val yy = y(v)
                drawLine(Palette.Outline, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
                val label = Fmt.num(v, if (step < 1) 1 else 0)
                val tl = measurer.measure(label, axisStyle)
                drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2))
                v += step
            }
            // Подписи времени по X: каждый час (или полчаса на коротком интервале)
            val stepMs = when {
                t1 - t0 <= 2 * HOUR -> HOUR / 2
                t1 - t0 <= 8 * HOUR -> HOUR
                else -> 3 * HOUR
            }
            val zone = ZoneId.systemDefault()
            val offset = zone.rules.getOffset(Instant.ofEpochMilli(t0)).totalSeconds * 1000L
            var t = (floor((t0 + offset).toDouble() / stepMs).toLong() + 1) * stepMs - offset
            while (t < t1) {
                val xx = x(t)
                val tl = measurer.measure(Fmt.time(t), axisStyle)
                if (xx - tl.size.width / 2 > left && xx + tl.size.width / 2 < left + w) {
                    drawText(tl, topLeft = Offset(xx - tl.size.width / 2, top + h + 4.dp.toPx()))
                }
                t += stepMs
            }
        }

        // Линия заката
        if (sunsetMs != null && sunsetMs in t0..t1) {
            val xs = x(sunsetMs)
            drawLine(
                Palette.Sunset, Offset(xs, top), Offset(xs, top + h), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
            if (showAxes) {
                val tl = measurer.measure("☀ закат ${Fmt.time(sunsetMs)}", TextStyle(color = Palette.Sunset, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
                val tx = min(xs + 4.dp.toPx(), left + w - tl.size.width)
                drawText(tl, topLeft = Offset(tx, 1.dp.toPx()))
            }
        }

        // Ряды
        val maxPts = max(60, (w / 2.5f).toInt())
        series.forEach { s -> drawSeries(s, maxPts, ::x, ::y, top + h) }

        // Ползунок
        val tx = touchX
        if (tx != null && tx in left..(left + w)) {
            val t = t0 + ((tx - left) / w * (t1 - t0)).toLong()
            drawLine(Palette.TextDim, Offset(tx, top), Offset(tx, top + h), strokeWidth = 1.dp.toPx())
            val lines = mutableListOf(Fmt.time(t))
            series.forEach { s ->
                val p = nearest(s.points, t) ?: return@forEach
                drawCircle(Palette.Bg, 6.dp.toPx(), Offset(x(p.first), y(p.second)))
                drawCircle(s.color, 4.dp.toPx(), Offset(x(p.first), y(p.second)))
                lines += "${s.label}: ${Fmt.num(p.second, digits)} $unit"
            }
            val text = lines.joinToString("\n")
            val tl = measurer.measure(text, TextStyle(color = Palette.Text, fontSize = 11.sp))
            val pad = 6.dp.toPx()
            val bw = tl.size.width + pad * 2
            val bx = if (tx + bw + 8.dp.toPx() < left + w) tx + 8.dp.toPx() else tx - bw - 8.dp.toPx()
            drawRoundRect(Palette.SurfaceHigh, Offset(bx, top + 4.dp.toPx()), Size(bw, tl.size.height + pad * 2), CornerRadius(8.dp.toPx()))
            drawText(tl, topLeft = Offset(bx + pad, top + 4.dp.toPx() + pad))
        }
    }
}

private const val HOUR = 3_600_000L
private const val GAP_MS = 5 * 60_000L

private fun DrawScope.drawSeries(
    s: ChartSeries,
    maxPts: Int,
    x: (Long) -> Float,
    y: (Double) -> Float,
    baseY: Float,
) {
    val pts = downsample(s.points, maxPts)
    if (pts.size < 2) return
    val line = Path()
    val fill = Path()
    var started = false
    var segStartX = 0f
    var lastX = 0f
    for (p in pts) {
        val xx = x(p.t); val yy = y(p.v)
        if (!started || p.breakBefore) {
            if (started && s.fill) { fill.lineTo(lastX, baseY); fill.lineTo(segStartX, baseY); fill.close() }
            line.moveTo(xx, yy)
            fill.moveTo(xx, yy)
            segStartX = xx
            started = true
        } else {
            line.lineTo(xx, yy)
            fill.lineTo(xx, yy)
        }
        lastX = xx
    }
    if (s.fill) {
        fill.lineTo(lastX, baseY); fill.lineTo(segStartX, baseY); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0f))))
    }
    drawPath(
        line, s.color,
        style = Stroke(
            width = (if (s.dashed) 1.5f else 2.5f) * density,
            cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
        ),
    )
}

/** Точка после прореживания; breakBefore — перед ней в исходных данных был пропуск. */
private class ChartPt(val t: Long, val v: Double, val breakBefore: Boolean)

/**
 * Прореживание по времени: среднее в корзинах одинаковой длительности.
 * Разрыв рисуем только там, где он был в исходных данных (а не из-за редкой записи на стоянке).
 */
private fun downsample(points: List<Pair<Long, Double>>, maxPts: Int): List<ChartPt> {
    if (points.isEmpty()) return emptyList()
    val t0 = points.first().first
    val span = max(1L, points.last().first - t0)
    val bucketMs = if (points.size <= maxPts) 0L else span / maxPts
    val out = ArrayList<ChartPt>(min(points.size, maxPts + 8))
    var i = 0
    while (i < points.size) {
        val brk = i > 0 && points[i].first - points[i - 1].first > GAP_MS
        val bucketEnd = points[i].first + bucketMs
        var sumV = 0.0
        var sumT = 0L
        var n = 0
        var j = i
        while (j < points.size && (j == i || (points[j].first <= bucketEnd && points[j].first - points[j - 1].first <= GAP_MS))) {
            sumV += points[j].second; sumT += points[j].first; n++; j++
        }
        out.add(ChartPt(sumT / n, sumV / n, brk))
        i = j
    }
    return out
}

private fun nearest(points: List<Pair<Long, Double>>, t: Long): Pair<Long, Double>? {
    if (points.isEmpty()) return null
    var lo = 0; var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < t) lo = mid + 1 else hi = mid
    }
    val a = points[lo]
    val b = points.getOrNull(lo - 1) ?: return a
    return if (abs(a.first - t) < abs(b.first - t)) a else b
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val exp = floor(kotlin.math.log10(raw))
    val base = Math.pow(10.0, exp)
    val f = raw / base
    val nice = when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
    return nice * base
}
