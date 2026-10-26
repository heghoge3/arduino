package com.vabugeode.app.astro

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Расчёт заката и сумерек офлайн (алгоритм NOAA, точность ~1 мин).
 * Интернет не нужен — только координаты и дата.
 */
object SunCalc {

    /** Угол высоты Солнца для событий, градусы. */
    enum class Event(val altitudeDeg: Double) {
        SUNSET(-0.833),        // верхний край касается горизонта с учётом рефракции
        CIVIL_DUSK(-6.0),      // конец гражданских сумерек
        NAUTICAL_DUSK(-12.0),
    }

    data class SunTimes(
        val sunriseUtcMs: Long?,
        val sunsetUtcMs: Long?,
        val civilDuskUtcMs: Long?,
    )

    fun times(date: LocalDate, lat: Double, lon: Double): SunTimes = SunTimes(
        sunriseUtcMs = eventUtc(date, lat, lon, Event.SUNSET.altitudeDeg, rising = true),
        sunsetUtcMs = eventUtc(date, lat, lon, Event.SUNSET.altitudeDeg, rising = false),
        civilDuskUtcMs = eventUtc(date, lat, lon, Event.CIVIL_DUSK.altitudeDeg, rising = false),
    )

    /**
     * Время события (восход/заход до заданной высоты) в UTC, мс.
     * null — если в этот день Солнце не опускается/не поднимается до этой высоты (полярный день/ночь).
     */
    fun eventUtc(date: LocalDate, lat: Double, lon: Double, altitudeDeg: Double, rising: Boolean): Long? {
        // Две итерации: сначала по полудню, потом уточняем по найденному времени
        var minutes = 720.0
        repeat(3) {
            val jd = julianDay(date) + minutes / 1440.0
            val t = (jd - 2451545.0) / 36525.0
            val decl = sunDeclination(t)
            val eqTime = equationOfTime(t)
            val ha = hourAngle(lat, decl, altitudeDeg) ?: return null
            val sign = if (rising) 1.0 else -1.0
            minutes = 720.0 - 4.0 * (lon + sign * Math.toDegrees(ha)) - eqTime
        }
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return start + (minutes * 60_000.0).toLong()
    }

    private fun julianDay(date: LocalDate): Double {
        var y = date.year
        var m = date.monthValue
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + date.dayOfMonth + b - 1524.5
    }

    private fun geomMeanLongSun(t: Double): Double {
        var l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
        l0 %= 360.0
        if (l0 < 0) l0 += 360.0
        return l0
    }

    private fun geomMeanAnomalySun(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun eccentricityEarthOrbit(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun sunEqOfCenter(t: Double): Double {
        val m = Math.toRadians(geomMeanAnomalySun(t))
        return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    }

    private fun sunApparentLong(t: Double): Double {
        val trueLong = geomMeanLongSun(t) + sunEqOfCenter(t)
        val omega = 125.04 - 1934.136 * t
        return trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
    }

    private fun obliquityCorrection(t: Double): Double {
        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0
        val omega = 125.04 - 1934.136 * t
        return e0 + 0.00256 * cos(Math.toRadians(omega))
    }

    private fun sunDeclination(t: Double): Double {
        val e = Math.toRadians(obliquityCorrection(t))
        val lambda = Math.toRadians(sunApparentLong(t))
        return Math.toDegrees(asin(sin(e) * sin(lambda)))
    }

    /** Уравнение времени, минуты. */
    private fun equationOfTime(t: Double): Double {
        val epsilon = Math.toRadians(obliquityCorrection(t))
        val l0 = Math.toRadians(geomMeanLongSun(t))
        val e = eccentricityEarthOrbit(t)
        val m = Math.toRadians(geomMeanAnomalySun(t))
        var y = tan(epsilon / 2.0)
        y *= y
        val eq = y * sin(2.0 * l0) - 2.0 * e * sin(m) + 4.0 * e * y * sin(m) * cos(2.0 * l0) -
            0.5 * y * y * sin(4.0 * l0) - 1.25 * e * e * sin(2.0 * m)
        return Math.toDegrees(eq) * 4.0
    }

    /** Часовой угол события, радианы. null — события нет. */
    private fun hourAngle(lat: Double, declDeg: Double, altitudeDeg: Double): Double? {
        val latR = Math.toRadians(lat)
        val sd = Math.toRadians(declDeg)
        val cosHa = (sin(Math.toRadians(altitudeDeg)) - sin(latR) * sin(sd)) / (cos(latR) * cos(sd))
        if (cosHa < -1.0 || cosHa > 1.0) return null
        return acos(cosHa)
    }

    /** Азимут не нужен сейчас, но удобно для отладки: высота Солнца в момент времени, градусы. */
    fun altitudeAt(timeUtc: ZonedDateTime, lat: Double, lon: Double): Double {
        val date = timeUtc.toLocalDate()
        val minutes = timeUtc.hour * 60 + timeUtc.minute + timeUtc.second / 60.0
        val jd = julianDay(date) + minutes / 1440.0
        val t = (jd - 2451545.0) / 36525.0
        val decl = Math.toRadians(sunDeclination(t))
        val trueSolarTime = minutes + equationOfTime(t) + 4.0 * lon
        val ha = Math.toRadians(trueSolarTime / 4.0 - 180.0)
        val latR = Math.toRadians(lat)
        val cosZen = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha)
        return 90.0 - Math.toDegrees(atan2(kotlin.math.sqrt(1 - cosZen * cosZen), cosZen))
    }
}
