package com.vabugeode.app.data

import android.content.SharedPreferences
import com.vabugeode.app.model.BlacklistPoint
import kotlin.math.roundToInt

/** В интерфейсе диаметр в км, в протоколе радиус в целых метрах. */
fun blockingRadiusFromDiameterKm(text: String): Int? {
    val km = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!km.isFinite() || km !in 0.2..100.0) return null
    return (km * 500).roundToInt()
}

class BlacklistSettings(private val prefs: SharedPreferences) {
    private fun key(p: BlacklistPoint) = "radius_${p.lat}_${p.lon}"

    fun load(defaults: List<BlacklistPoint>): List<BlacklistPoint> = defaults.map { p ->
        val saved = prefs.getInt(key(p), p.radiusM)
        val radius = if (p.radiusM==5000 && saved==1000 && !prefs.getBoolean("explicit_${key(p)}",false)) {
            prefs.edit().putInt(key(p),5000).apply(); 5000
        } else saved
        p.copy(radiusM = radius.takeIf { it in 100..50000 } ?: p.radiusM)
    }

    fun save(points: List<BlacklistPoint>) {
        prefs.edit().also { edit -> points.forEach { edit.putInt(key(it), it.radiusM).putBoolean("explicit_${key(it)}",true) } }.apply()
    }
}

fun isBlocked(lat: Double?,lon: Double?,points: List<BlacklistPoint>): Boolean =
    lat!=null && lon!=null && lat.isFinite() && lon.isFinite() && points.any { distanceM(lat,lon,it.lat,it.lon)<=it.radiusM }
fun excludeBlacklisted(track: List<com.vabugeode.app.model.TrackPoint>,points: List<BlacklistPoint>) = track.map { p ->
    if (p.src==com.vabugeode.app.model.PositionSource.GPS && isBlocked(p.lat,p.lon,points))
        p.copy(src=com.vabugeode.app.model.PositionSource.NONE,spoof=true,spoofMask=p.spoofMask or 1,
            spoofLat=p.spoofLat ?: p.lat,spoofLon=p.spoofLon ?: p.lon,lat=Double.NaN,lon=Double.NaN)
    else p
}
fun excludeBlacklistedMarks(marks: List<com.vabugeode.app.model.Mark>,points: List<BlacklistPoint>) = marks.map {
    if (isBlocked(it.lat,it.lon,points)) it.copy(lat=null,lon=null) else it
}
