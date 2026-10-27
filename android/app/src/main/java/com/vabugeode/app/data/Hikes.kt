package com.vabugeode.app.data

import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.TrackPoint
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
 * Длина пути по точкам. В lat/lon уже лучшая оценка (GPS или по шагам),
 * ложные координаты подмены лежат отдельно (spoofLat/Lon) и сюда не попадают.
 * Сдвиги меньше 3 м не считаем — это дрожание GPS на месте.
 */
fun trackKm(track: List<TrackPoint>): Double {
    var m = 0.0
    var prev: TrackPoint? = null
    for (p in track) {
        if (p.lat.isNaN() || p.lon.isNaN()) continue
        val q = prev
        if (q == null) { prev = p; continue }
        val d = distanceM(q.lat, q.lon, p.lat, p.lon)
        if (d >= 3.0) { m += d; prev = p }
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
