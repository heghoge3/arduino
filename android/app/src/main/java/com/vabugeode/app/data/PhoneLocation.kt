package com.vabugeode.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Looper
import android.os.Bundle
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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

    /** Разовый запрос сетевой позиции для начала PDR, без постоянного GPS телефона. */
    @Suppress("DEPRECATION")
    suspend fun refresh(ctx: Context, blacklist: List<BlacklistPoint>): Fix? {
        last(ctx, blacklist, maxAgeMs = 30_000)?.takeIf { fix ->
            fix.accuracyM <= 1000 && blacklist.none { distanceM(fix.lat, fix.lon, it.lat, it.lon) <= it.radiusM }
        }?.let { return it }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        val acceptable = l.hasAccuracy() && l.accuracy.isFinite() && l.accuracy in 0f..1000f &&
                            l.latitude.isFinite() && l.longitude.isFinite() &&
                            System.currentTimeMillis() - l.time in 0..30_000 &&
                            blacklist.none { distanceM(l.latitude, l.longitude, it.lat, it.lon) <= it.radiusM }
                        if (acceptable && continuation.isActive) {
                            lm.removeUpdates(this)
                            continuation.resume(Fix(l.latitude, l.longitude, l.accuracy.roundToInt().coerceAtLeast(1)))
                        }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (_: SecurityException) {} }
                try {
                    if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) continuation.resume(null)
                    else lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(null) }
                catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    fun last(ctx: Context, blacklist: List<BlacklistPoint> = emptyList(), maxAgeMs: Long = 30 * 60_000): Fix? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val fresh = try {
            lm.getProviders(true)
                .mapNotNull { p -> lm.getLastKnownLocation(p)?.let { p to it } }
                .filter { (_, l) -> System.currentTimeMillis() - l.time in 0 until maxAgeMs && l.hasAccuracy() && l.accuracy.isFinite() }
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
