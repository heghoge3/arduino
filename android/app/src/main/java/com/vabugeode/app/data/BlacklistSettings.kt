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
        val radius = prefs.getInt(key(p), p.radiusM)
        p.copy(radiusM = radius.takeIf { it in 100..50000 } ?: p.radiusM)
    }

    fun save(points: List<BlacklistPoint>) {
        prefs.edit().also { edit -> points.forEach { edit.putInt(key(it), it.radiusM) } }.apply()
    }
}
