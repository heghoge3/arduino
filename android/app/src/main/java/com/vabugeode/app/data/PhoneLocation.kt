package com.vabugeode.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.content.ContextCompat
import com.vabugeode.app.log.AppLog
import com.vabugeode.app.model.BlacklistPoint
import kotlin.math.roundToInt

/**
 * Последняя известная позиция телефона — без включения GPS, почти бесплатно.
 * Сначала позиция по вышкам и Wi-Fi: она меньше зависит от GNSS, но может быть грубой
 * или устаревшей. GPS телефона может ошибаться одновременно с GPS гаджета.
 * GPS/fused — только если сетевой позиции нет; география не ограничивает выбор.
 * Телефон — справочная сверка, а не доказательство подмены GPS гаджета.
 */
object PhoneLocation {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Int,val ageMs:Long=0,val provider:Int=0)

    /** Разовый запрос сетевой позиции для начала PDR, без постоянного GPS телефона. */
    @Suppress("DEPRECATION")
    suspend fun refresh(ctx: Context, blacklist: List<BlacklistPoint>): Fix? {
        last(ctx, blacklist, maxAgeMs = 30_000)?.takeIf { fix ->
            fix.accuracyM <= 1000
        }?.let { return it }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        val acceptable = selectCached(listOf(LocationManager.NETWORK_PROVIDER to l),
                            blacklist, 30_000, 1000f) != null
                        if (acceptable && continuation.isActive) {
                            lm.removeUpdates(this)
                            continuation.resume(Fix(l.latitude, l.longitude, l.accuracy.roundToInt().coerceAtLeast(1),(SystemClock.elapsedRealtimeNanos()-l.elapsedRealtimeNanos)/1_000_000,1))
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

    /** Монотонное время фикса не позволяет смене часов «омолодить» старый кэш. */
    internal fun selectCached(
        locations: List<Pair<String, Location>>,
        blacklist: List<BlacklistPoint>,
        maxAgeMs: Long,
        maxAccuracyM: Float = Float.MAX_VALUE,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Pair<String, Location>? {
        val fresh = locations.filter { (_, l) ->
            l.elapsedRealtimeNanos > 0 && l.elapsedRealtimeNanos <= nowNanos &&
                (nowNanos - l.elapsedRealtimeNanos) / 1_000_000 < maxAgeMs &&
                l.hasAccuracy() && l.accuracy.isFinite() && l.accuracy > 0 && l.accuracy <= maxAccuracyM &&
                l.latitude.isFinite() && l.latitude in -90.0..90.0 &&
                l.longitude.isFinite() && l.longitude in -180.0..180.0
        }
        return fresh.firstOrNull { it.first == LocationManager.NETWORK_PROVIDER }
            ?: fresh.minByOrNull { it.second.accuracy }
    }

    fun last(ctx: Context, blacklist: List<BlacklistPoint> = emptyList(), maxAgeMs: Long = 30 * 60_000,
             maxAccuracyM: Float = Float.MAX_VALUE): Fix? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val locations = try {
            lm.getProviders(true)
                .mapNotNull { p -> lm.getLastKnownLocation(p)?.let { p to it } }
        } catch (_: SecurityException) {
            return null
        }
        val (provider, loc) = selectCached(locations, blacklist, maxAgeMs, maxAccuracyM) ?: run {
            AppLog.d("LOC", "нет пригодной позиции: провайдеров ${locations.size}, возраст <${maxAgeMs / 1000} с")
            return null
        }
        val ageS = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000
        AppLog.d("LOC", "позиция $provider: возраст ${ageS} с, точность +/- ${loc.accuracy.roundToInt()} м")
        return Fix(loc.latitude, loc.longitude, loc.accuracy.roundToInt().coerceIn(1, 65535),(SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos)/1_000_000,if(provider==LocationManager.NETWORK_PROVIDER)1 else if(provider==LocationManager.GPS_PROVIDER)2 else 3)
    }
}
