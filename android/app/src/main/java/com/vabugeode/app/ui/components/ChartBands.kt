package com.vabugeode.app.ui.components

import androidx.compose.ui.graphics.Color
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.theme.Palette

/** Отрезок времени, подсвеченный на графике: привал, пропуск записи */
data class ChartBand(val startMs: Long, val endMs: Long, val label: String, val color: Color)

private const val MIN = 60_000L

/**
 * Что происходило в походе — из самих точек трека:
 * — «привал»: шагов не прибавилось 20 мин и дольше;
 * — «GPS выкл»: GPS выключен кнопкой (едем на машине) — датчики пишутся, координат нет;
 * — «нет записи»: между точками больше 5 мин (гаджет выключен).
 * Подмену здесь не показываем: датчики в это время пишут верно, а для позиции есть карта.
 */
fun chartBands(track: List<TrackPoint>): List<ChartBand> {
    if (track.size < 2) return emptyList()
    val out = ArrayList<ChartBand>()
    // Пропуски записи
    for (i in 1 until track.size) {
        val a = track[i - 1].tsUtcMs
        val b = track[i].tsUtcMs
        if (b - a > 5 * MIN) out += ChartBand(a, b, "нет записи", Palette.TextFaint)
    }
    // Пауза записи: подряд точки с пометкой «пауза»
    var p0 = -1
    for (i in 0..track.size) {
        val paused = i < track.size && track[i].src == PositionSource.PAUSE
        if (paused && p0 < 0) p0 = i
        if (!paused && p0 >= 0) {
            out += ChartBand(track[p0].tsUtcMs, track[i - 1].tsUtcMs, "GPS выкл", Palette.TextDim)
            p0 = -1
        }
    }
    // Привалы: шаги стоят (пропуски внутри не считаем — их уже показали отдельно).
    // Без акселерометра шагов нет вовсе — тогда привалы не считаем
    if (track.none { it.steps > 0 }) return out
    var runStart = 0
    for (i in 1..track.size) {
        // На паузе едем, а не стоим — привалом это не считаем
        val end = i == track.size || track[i].steps != track[runStart].steps || track[i].tsUtcMs - track[i - 1].tsUtcMs > 5 * MIN ||
            track[i].src == PositionSource.PAUSE
        if (end) {
            val a = track[runStart].tsUtcMs
            val b = track[i - 1].tsUtcMs
            if (b - a >= 20 * MIN) out += ChartBand(a, b, "привал", Palette.Good)
            runStart = i
        }
    }
    return out
}
