package com.vabugeode.app.data

import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.protocol.MarkMsg
import com.vabugeode.app.protocol.PointMsg
import com.vabugeode.app.store.StoreDao
import com.vabugeode.app.transport.VIRTUAL_ID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Сводка похода для списка */
data class HikeSummary(
    val device: String,
    val hike: Long,          // hike_start, Unix-с — он же ключ в базе
    val name: String?,       // своё название; null — показываем дату
    val startMs: Long,
    val endMs: Long,
    val km: Double,
    val tempMin: Double,
    val tempMax: Double,
    val marks: Int,
    val spoofPoints: Int,
    val points: Int,
) {
    val demo get() = device == VIRTUAL_ID
}

/**
 * Длина пути по достоверным точкам GPS/PDR. Сохранённая последняя позиция при src=NONE
 * не считается новым измерением; через пропуски и подмену расстояние не соединяем.
 * Сдвиги меньше 3 м не считаем — это дрожание GPS на месте.
 */
fun TrackPoint.isRoutePoint(): Boolean = lat.isFinite() && lon.isFinite() &&
    lat in -90.0..90.0 && lon in -180.0..180.0 &&
    (src == PositionSource.PDR || (src == PositionSource.GPS && !spoof))

data class RouteSegment(val points: List<TrackPoint>, val source: PositionSource)

private fun continuous(a: TrackPoint, b: TrackPoint): Boolean {
    if (b.tsUtcMs - a.tsUtcMs !in 0..120_000) return false
    if (a.src == PositionSource.PDR && b.src == PositionSource.PDR) {
        val steps = b.steps - a.steps
        if (steps < 0 || distanceM(a.lat, a.lon, b.lat, b.lon) > steps * 1.6 + 5) return false
    }
    return true
}

/** Только представление: SD и исходные POINT не меняются. Коррекция — лишь к чистому GPS. */
fun correctedRoute(track: List<TrackPoint>): List<TrackPoint> {
    val out = track.toMutableList()
    var i = 0
    while (i < track.size) {
        if (track[i].src != PositionSource.PDR || !track[i].isRoutePoint()) { i++; continue }
        val start = i
        while (i + 1 < track.size && track[i + 1].src == PositionSource.PDR &&
            track[i + 1].isRoutePoint() && continuous(track[i], track[i + 1])) i++
        val end = i++
        val gps = track.getOrNull(end + 1) ?: continue
        if (gps.src != PositionSource.GPS || !gps.isRoutePoint() || !continuous(track[end], gps)) continue
        val anchor = track.getOrNull(start - 1)?.takeIf {
            it.src == PositionSource.GPS && it.isRoutePoint() && continuous(it, track[start])
        }
        val firstSteps = anchor?.steps ?: track[start].steps
        val steps = track[end].steps - firstSteps
        if (steps < 0 || (anchor != null && steps == 0) || (start..end).any { track[it].steps < firstSteps }) continue
        val error = distanceM(track[end].lat, track[end].lon, gps.lat, gps.lon)
        // Грубый скачок не растягивает пройденный путь через весь город.
        val limit = if (anchor == null) 2000.0 else minOf(2000.0, maxOf(100.0, steps * 0.75 * 1.5))
        if (error > limit) continue
        val dLat = gps.lat - track[end].lat
        val dLon = ((gps.lon - track[end].lon + 540.0) % 360.0) - 180.0
        for (j in start..end) {
            val weight = if (anchor == null) 1.0 else (track[j].steps - firstSteps).toDouble() / steps
            if (weight !in 0.0..1.0) continue
            val lat = track[j].lat + dLat * weight
            val lon = ((track[j].lon + dLon * weight + 540.0) % 360.0) - 180.0
            if (lat in -90.0..90.0) out[j] = track[j].copy(lat = lat, lon = lon)
        }
    }
    return out
}

/** GPS — сплошной; оценка по шагам, включая стыки с GPS, — пунктир. */
fun routeSegments(track: List<TrackPoint>): List<RouteSegment> {
    val out = ArrayList<RouteSegment>()
    var cur = ArrayList<TrackPoint>()
    fun flush() {
        if (cur.size > 1) out += RouteSegment(cur.toList(), cur.first().src)
        cur = ArrayList()
    }
    for ((index, p) in correctedRoute(track).withIndex()) {
        if (!p.isRoutePoint()) { flush(); continue }
        val prev = cur.lastOrNull()
        if (prev != null && !continuous(track[index - 1], track[index])) flush()
        else if (prev != null && prev.src != p.src) {
            val d = distanceM(prev.lat, prev.lon, p.lat, p.lon)
            if (prev.src == PositionSource.GPS && p.src == PositionSource.PDR &&
                p.steps >= prev.steps && d <= maxOf(100.0, (p.steps - prev.steps) * 1.5)) {
                flush(); cur += prev.copy(src = PositionSource.PDR)
            } else {
                // Совмещённый конец приблизительного участка после коррекции.
                if (prev.src == PositionSource.PDR && d < 3) cur += p.copy(src = PositionSource.PDR)
                flush()
            }
        }
        cur += p
    }
    flush()
    return out
}

fun trackKm(track: List<TrackPoint>): Double {
    var m = 0.0
    for (segment in routeSegments(track)) {
        var prev = segment.points.first()
        for (p in segment.points.drop(1)) {
            val d = distanceM(prev.lat, prev.lon, p.lat, p.lon)
            if (d >= 3.0) { m += d; prev = p }
        }
    }
    return m / 1000.0
}

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * r * asin(sqrt(a))
}

fun summarize(device: String, hike: Long, name: String?, track: List<TrackPoint>, marks: List<Mark>): HikeSummary {
    val temps = track.map { it.tempCorr }.filter { !it.isNaN() }
    return HikeSummary(
        device = device, hike = hike, name = name,
        startMs = hike * 1000,
        // Конец не раньше начала (старые записи по неверным часам)
        endMs = maxOf(track.lastOrNull()?.tsUtcMs ?: 0L, hike * 1000),
        km = trackKm(track),
        tempMin = temps.minOrNull() ?: Double.NaN,
        tempMax = temps.maxOrNull() ?: Double.NaN,
        marks = marks.size,
        spoofPoints = track.count { it.spoof },
        points = track.size,
    )
}

/** Трек похода из базы (сырые POINT → точки) */
suspend fun StoreDao.loadTrack(device: String, hike: Long): List<TrackPoint> =
    points(device, hike).mapNotNull { row ->
        (runCatching { Codec.decodeMsg(row.data) }.getOrNull() as? PointMsg)?.toTrackPoint()
    }

suspend fun StoreDao.loadMarks(device: String, hike: Long): List<Mark> =
    marks(device, hike).mapNotNull { row ->
        (runCatching { Codec.decodeMsg(row.data) }.getOrNull() as? MarkMsg)?.toMark()
    }
