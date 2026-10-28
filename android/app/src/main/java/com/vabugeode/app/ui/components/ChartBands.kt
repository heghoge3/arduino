package com.vabugeode.app.ui.components

import androidx.compose.ui.graphics.Color
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.theme.Palette

/** Отрезок времени, подсвеченный на графике: привал, пропуск записи, подмена GPS */
data class ChartBand(val startMs: Long, val endMs: Long, val label: String, val color: Color)

private const val MIN = 60_000L

/**
 * Что происходило в походе — из самих точек трека:
 * — «привал»: шагов не прибавилось 10 мин и дольше;
 * — «нет записи»: между точками больше 5 мин (пауза, гаджет выключен);
 * — «подмена»: подряд подозрительные точки дольше минуты.
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
    // Привалы: шаги стоят (пропуски внутри не считаем — их уже показали отдельно).
    // Без акселерометра шагов нет вовсе — тогда привалы не считаем
    if (track.none { it.steps > 0 }) return out + spoofBands(track)
    var runStart = 0
    for (i in 1..track.size) {
        val end = i == track.size || track[i].steps != track[runStart].steps || track[i].tsUtcMs - track[i - 1].tsUtcMs > 5 * MIN
        if (end) {
            val a = track[runStart].tsUtcMs
            val b = track[i - 1].tsUtcMs
            if (b - a >= 10 * MIN) out += ChartBand(a, b, "привал", Palette.Good)
            runStart = i
        }
    }
    return out + spoofBands(track)
}

private fun spoofBands(track: List<TrackPoint>): List<ChartBand> {
    val out = ArrayList<ChartBand>()
    var s = -1
    for (i in 0..track.size) {
        val spoof = i < track.size && track[i].spoof
        if (spoof && s < 0) s = i
        if (!spoof && s >= 0) {
            val a = track[s].tsUtcMs
            val b = track[i - 1].tsUtcMs
            if (b - a >= MIN) out += ChartBand(a, b, "подмена", Palette.Bad)
            s = -1
        }
    }
    return out
}
