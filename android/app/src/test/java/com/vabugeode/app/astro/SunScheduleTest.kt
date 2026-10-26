package com.vabugeode.app.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Двухдневный поход под Петербургом: два заката, восход между ними, ночь затеняется целиком. */
class SunScheduleTest {
    private val msk = ZoneId.of("Europe/Moscow")
    private fun t(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, msk).toInstant().toEpochMilli()
    private fun nov1(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 1, h, m, 0, 0, msk).toInstant().toEpochMilli()

    @Test
    fun twoDayHike() {
        val from = t(31, 12)
        val to = nov1(20)
        val s = SunSchedule.compute(from, to, 60.0, 30.5)
        val sunsets = s.of(SunSchedule.Kind.SUNSET).filter { it.timeMs in from..to }
        val sunrises = s.of(SunSchedule.Kind.SUNRISE).filter { it.timeMs in from..to }
        assertEquals(2, sunsets.size)
        assertEquals(1, sunrises.size)
        // Восход 1 ноября около 8:30 МСК, закат 31 октября около 17:00
        assertTrue(sunrises[0].timeMs in nov1(7, 50)..nov1(9, 10))
        assertTrue(sunsets[0].timeMs in t(31, 16, 30)..t(31, 17, 40))

        val nights = s.nights(from, to)
        assertEquals("одна ночь целиком и начало второй", 2, nights.size)
        assertTrue(nights[0].second > nights[0].first)
        assertTrue(s.isNight(nov1(2)))
        assertFalse(s.isNight(t(31, 13)))
    }
}
