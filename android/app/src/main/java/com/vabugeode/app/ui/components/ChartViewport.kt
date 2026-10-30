package com.vabugeode.app.ui.components

import kotlin.math.max

/** Видимая часть временного диапазона, от 0 до 1. */
internal data class ChartViewport(val start: Double = 0.0, val span: Double = 1.0) {
    val zoom get() = 1.0 / span
    val zoomed get() = span < 0.999999

    fun transform(scale: Double, anchor: Double, pan: Double, minimumSpan: Double): ChartViewport {
        if (!scale.isFinite() || scale <= 0 || !anchor.isFinite() || !pan.isFinite()) return this
        val nextSpan = (span / scale).coerceIn(minimumSpan.coerceIn(0.0, 1.0), 1.0)
        val focus = anchor.coerceIn(0.0, 1.0)
        val nextStart = start + focus * span - (focus + pan) * nextSpan
        return ChartViewport(nextStart.coerceIn(0.0, 1.0 - nextSpan), nextSpan)
    }
}

/** Для линии сохраняем по одной соседней точке за краями окна. Прореживаем уже этот участок. */
internal fun chartWindowPoints(points: List<Pair<Long, Double>>, start: Long, end: Long): List<Pair<Long, Double>> {
    if (points.isEmpty()) return points
    fun bound(time: Long, inclusive: Boolean): Int {
        var lo = 0; var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].first < time || (inclusive && points[mid].first == time)) lo = mid + 1 else hi = mid
        }
        return lo
    }
    return points.subList(max(0, bound(start, false) - 1), (bound(end, true) + 1).coerceAtMost(points.size))
}

internal fun chartTimeStep(spanMs: Long): Long =
    listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L,
        1_800_000L, 3_600_000L, 10_800_000L, 21_600_000L, 43_200_000L, 86_400_000L)
        .firstOrNull { it >= spanMs / 4 } ?: ((spanMs / 4 / 86_400_000L + 1) * 86_400_000L)
