package com.vabugeode.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Последняя известная позиция телефона (вышки, Wi-Fi, GPS) — без включения GPS, почти бесплатно. */
object PhoneLocation {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Int)

    fun last(ctx: Context, maxAgeMs: Long = 30 * 60_000): Fix? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = try {
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < maxAgeMs }
                .minByOrNull { it.accuracy }
        } catch (_: SecurityException) {
            null
        } ?: return null
        return Fix(loc.latitude, loc.longitude, loc.accuracy.roundToInt().coerceIn(1, 65535))
    }
}
