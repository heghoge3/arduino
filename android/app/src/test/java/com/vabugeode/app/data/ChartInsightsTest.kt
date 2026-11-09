package com.vabugeode.app.data

import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class ChartInsightsTest {
    private fun hour() = (0..60).map { m -> TrackPoint(
        tsUtcMs = m * 60_000L, lat = Double.NaN, lon = Double.NaN, altGps = null,
        sats = 0, hdop = 99.0, speedKmh = 0.0, spoof = false, spoofMask = 0,
        spoofLat = null, spoofLon = null, tempRaw = 10.0, tempCorr = 10.0,
        humidity = 50.0, pressureHpa = 1000.0, altBaro = 0.0, battV = 3.9,
        battMa = 0.0, battPercent = 70, steps = m, headingDeg = 0.0, src = PositionSource.NONE, markId = null,
    ) }

    @Test fun shortMissingAndInterruptedRecordsStaySilent() {
        val points = hour()
        for (metric in listOf("TEMP", "PRESSURE", "HUMIDITY", "ALTITUDE", "BATTERY")) {
            assertNull(chartInsight(metric, points.take(20)))
            assertNull(chartInsight(metric, points.filterIndexed { i, _ -> i !in 20..35 }))
        }
        assertNull(chartInsight("TEMP", points.map { it.copy(tempCorr = Double.NaN) }))
        assertNull(chartInsight("PRESSURE", points.map { it.copy(pressureHpa = 0.0) }))
        assertNull(chartInsight("BATTERY", points.map { it.copy(battV = 0.0, battPercent = 0) }))
    }

    @Test fun pressureAndTemperatureAreInterpretedTogetherWithoutCertainForecast() {
        val points = hour().mapIndexed { i, p -> p.copy(pressureHpa = 1000.0 - i * 0.05,
            tempCorr = 10.0 + i * 0.05, tempRaw = 10.0 + i * 0.05) }
        val text = chartInsight("PRESSURE", points)!!
        assertTrue(text.contains("температура выросла"))
        assertTrue(text.contains("осадки не гарантированы"))
        val climbing = points.mapIndexed { i, p -> p.copy(altBaro = i * 2.0) }
        assertTrue(chartInsight("PRESSURE", climbing)!!.contains("нельзя отличить"))
    }

    @Test fun freezingCondensationAndBatteryDropHaveDifferentExplanations() {
        val points = hour()
        val freezing = points.mapIndexed { i, p -> p.copy(tempCorr = 1.0 - i * 0.05, tempRaw = 1.0 - i * 0.05) }
        assertTrue(chartInsight("TEMP", freezing)!!.contains("пересекла 0"))
        assertTrue(chartInsight("HUMIDITY", points.map { it.copy(humidity = 96.0) })!!.contains("конденсат"))
        val falling = points.mapIndexed { i, p -> p.copy(battPercent = 90 - i / 2) }
        assertTrue(chartInsight("BATTERY", falling)!!.contains("упала"))
        assertNull(chartInsight("BATTERY", points)) // стабильные 70% — нет подходящего сообщения
    }
}
