package com.vabugeode.app.data

import android.content.Context
import com.vabugeode.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Прогноз на сегодня и завтра для места телефона — Open-Meteo (бесплатно, без ключа).
 * В походе интернета может не быть: последний прогноз хранится и показывается с временем загрузки.
 */
object Weather {
    data class Day(val date: String, val code: Int, val tMin: Double, val tMax: Double, val precipProb: Int, val windMaxMs: Double)
    data class Forecast(val fetchedMs: Long, val days: List<Day>, val place: String?)

    private const val PREFS = "weather"

    fun cached(ctx: Context): Forecast? {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val json = p.getString("json", null) ?: return null
        return runCatching { parse(json, p.getLong("at", 0), p.getString("place", null)) }.getOrNull()
    }

    /** Название места по координатам (нужен интернет — он и так есть, раз прогноз скачался); нет — координаты */
    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, lat: Double, lon: Double): String {
        val name = runCatching {
            android.location.Geocoder(ctx, Locale("ru")).getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
                it.locality ?: it.subAdminArea ?: it.adminArea
            }
        }.getOrNull()
        return name ?: String.format(Locale.US, "%.3f, %.3f", lat, lon)
    }

    suspend fun refresh(ctx: Context, lat: Double, lon: Double): Forecast? = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=2",
            lat, lon,
        )
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val now = System.currentTimeMillis()
            val place = placeName(ctx, lat, lon)
            val f = parse(body, now, place)
            ctx.getSharedPreferences(PREFS, 0).edit().putString("json", body).putLong("at", now).putString("place", place).apply()
            AppLog.d("WEATHER", "прогноз обновлён")
            f
        } catch (e: Exception) {
            // Нет интернета — остаётся прошлый прогноз
            AppLog.d("WEATHER", "прогноз не обновился: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parse(json: String, at: Long, place: String?): Forecast {
        val d = JSONObject(json).getJSONObject("daily")
        fun arr(k: String): JSONArray = d.getJSONArray(k)
        val n = arr("time").length()
        return Forecast(at, (0 until n).map { i ->
            Day(
                date = arr("time").getString(i),
                code = arr("weather_code").optInt(i),
                tMin = arr("temperature_2m_min").optDouble(i),
                tMax = arr("temperature_2m_max").optDouble(i),
                precipProb = arr("precipitation_probability_max").optInt(i),
                windMaxMs = arr("wind_speed_10m_max").optDouble(i),
            )
        }, place)
    }

    /** Коды погоды WMO → коротко по-русски */
    fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "почти ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61, 63, 80, 81 -> "дождь"
        65, 82 -> "ливень"
        66, 67 -> "ледяной дождь"
        71, 73, 77, 85 -> "снег"
        75, 86 -> "сильный снег"
        95, 96, 99 -> "гроза"
        else -> "—"
    }
}
