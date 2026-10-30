package com.vabugeode.app.data

import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.model.SpoofReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test
    fun recoveryReasonUsesHighBitOfWireMask() {
        assertEquals(listOf(SpoofReason.RECOVERY), SpoofReason.fromMask(0x80))
    }
    private fun point(lat: Double, src: PositionSource = PositionSource.GPS, spoof: Boolean = false) = TrackPoint(
        tsUtcMs = 0, lat = lat, lon = 30.0, altGps = null, sats = 8, hdop = 1.0,
        speedKmh = 0.0, spoof = spoof, spoofMask = 0, spoofLat = null, spoofLon = null,
        tempRaw = 20.0, tempCorr = 20.0, humidity = 50.0, pressureHpa = 1000.0,
        altBaro = 0.0, battV = 4.0, battMa = 0.0, battPercent = 80, steps = 0,
        headingDeg = 0.0, src = src, markId = null,
    )

    @Test
    fun invalidPositionsBreakLineAndDistance() {
        val invalid = listOf(point(60.5, PositionSource.NONE), point(60.5, spoof = true),
            point(60.5, PositionSource.PAUSE), point(Double.NaN))
        for (p in invalid) {
            val track = listOf(point(60.0), point(60.001), p, point(61.0), point(61.001))
            val segments = routeSegments(track)
            assertEquals(2, segments.size)
            assertEquals(listOf(2, 2), segments.map { it.points.size })
            assertEquals(0.2224, trackKm(track), 0.001)
        }
    }

    @Test
    fun onlySpoofOrUnknownPositionsProduceNoRoute() {
        val track = listOf(point(60.0, PositionSource.NONE), point(60.1, spoof = true))
        assertTrue(routeSegments(track).isEmpty())
        assertEquals(0.0, trackKm(track), 0.0)
    }
}
