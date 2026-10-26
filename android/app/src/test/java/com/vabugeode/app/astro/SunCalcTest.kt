package com.vabugeode.app.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SunCalcTest {

    private val msk = ZoneId.of("Europe/Moscow")

    private fun minutesOfDay(ms: Long): Int {
        val t = Instant.ofEpochMilli(ms).atZone(msk)
        return t.hour * 60 + t.minute
    }

    @Test
    fun sunsetPetersburgSummerSolstice() {
        // Белые ночи: закат 21 июня в Петербурге ~22:25 МСК
        val t = SunCalc.times(LocalDate.of(2026, 6, 21), 59.9386, 30.3141)
        assertEquals((22 * 60 + 26).toDouble(), minutesOfDay(t.sunsetUtcMs!!).toDouble(), 5.0)
        // Архангельск в солнцестояние: гражданские сумерки не заканчиваются всю ночь
        assertNull(SunCalc.times(LocalDate.of(2026, 6, 21), 64.54, 40.54).civilDuskUtcMs)
    }

    @Test
    fun sunsetPetersburgLateOctober() {
        // 26 октября в Петербурге: полдень ~12:43, день ~9 ч 14 мин → восход ~08:06, закат ~17:20 МСК
        val t = SunCalc.times(LocalDate.of(2026, 10, 26), 59.9386, 30.3141)
        assertEquals((17 * 60 + 20).toDouble(), minutesOfDay(t.sunsetUtcMs!!).toDouble(), 6.0)
        assertEquals((8 * 60 + 6).toDouble(), minutesOfDay(t.sunriseUtcMs!!).toDouble(), 6.0)
    }

    @Test
    fun noSunriseInPolarNight() {
        // Мурманск, 20 декабря — полярная ночь
        val t = SunCalc.times(LocalDate.of(2026, 12, 20), 68.97, 33.07)
        assertNull(t.sunsetUtcMs)
    }
}
