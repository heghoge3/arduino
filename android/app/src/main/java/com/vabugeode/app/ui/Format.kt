package com.vabugeode.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Форматирование для интерфейса. Время показываем в часовом поясе телефона. */
object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun time(ms: Long): String = hm.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun timeSec(ms: Long): String = hms.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** 2 ч 05 мин / 35 мин */
    fun duration(ms: Long): String {
        val totalMin = abs(ms) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h ч ${"%02d".format(m)} мин" else "$m мин"
    }

    /** +01:23:45 — для монтажа */
    fun clock(ms: Long): String {
        val s = abs(ms) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    fun num(v: Double, digits: Int = 1): String = if (v.isNaN()) "—" else String.format(Locale.US, "%.${digits}f", v).replace('.', ',')

    fun signed(v: Double, digits: Int = 1): String = if (v.isNaN()) "—" else (if (v > 0) "+" else if (v < 0) "−" else "") + num(abs(v), digits)

    fun coord(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun compass(deg: Double): String {
        val names = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        return names[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
    }
}
