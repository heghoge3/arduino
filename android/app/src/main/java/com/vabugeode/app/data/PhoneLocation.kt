package com.vabugeode.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.vabugeode.app.log.AppLog
import com.vabugeode.app.model.BlacklistPoint
import kotlin.math.roundToInt

/**
 * Последняя известная позиция телефона — без включения GPS, почти бесплатно.
 * Нужна гаджету для сверки (ТЗ, раздел 7, проверка 2), поэтому сначала позиция по вышкам и Wi-Fi:
 * подмена GNSS бьёт и по GPS телефона (дома в СПб он тоже «видит» ложную точку с ±5 м), а сеть не трогает.
 * GPS/fused — только если сетевой позиции нет, и не из чёрного списка.
 */
object PhoneLocation {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Int)

    fun last(ctx: Context, blacklist: List<BlacklistPoint> = emptyList(), maxAgeMs: Long = 30 * 60_000): Fix? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val fresh = try {
            lm.getProviders(true)
                .mapNotNull { p -> lm.getLastKnownLocation(p)?.let { p to it } }
                .filter { (_, l) -> System.currentTimeMillis() - l.time < maxAgeMs }
        } catch (_: SecurityException) {
            return null
        }
        val net = fresh.firstOrNull { it.first == LocationManager.NETWORK_PROVIDER }?.second
        val loc: Location = net ?: fresh
            .map { it.second }
            .filter { l -> blacklist.none { distanceM(l.latitude, l.longitude, it.lat, it.lon) <= it.radiusM } }
            .minByOrNull { it.accuracy }
            ?: run {
                if (fresh.isNotEmpty()) AppLog.w("LOC", "позиция телефона только из чёрного списка — гаджету не шлём")
                return null
            }
        return Fix(loc.latitude, loc.longitude, loc.accuracy.roundToInt().coerceIn(1, 65535))
    }
}
