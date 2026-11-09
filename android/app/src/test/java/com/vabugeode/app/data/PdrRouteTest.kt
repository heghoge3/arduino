package com.vabugeode.app.data

import com.vabugeode.app.model.PositionSource as Src
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.protocol.Cmd
import com.vabugeode.app.protocol.Codec
import org.junit.Assert.*
import org.junit.Test

class PdrRouteTest {
    private fun p(t: Long, lat: Double, steps: Int, src: Src = Src.PDR, spoof: Boolean = false) = TrackPoint(
        t * 1000, lat, 30.0, null, 0, 1.0, 0.0, spoof, 0, null, null,
        20.0, 20.0, 50.0, 1000.0, 0.0, 4.0, 1.0, 80, steps, 0.0, src, null,
    )
    @Test fun driftCorrectionPreservesStartAndUsesSteps() {
        val track = listOf(p(0, 60.0, 0, Src.GPS), p(10, 60.0001, 5), p(20, 60.0002, 20), p(30, 60.0003, 25, Src.GPS))
        val corrected = correctedRoute(track)
        assertEquals(60.0, corrected[0].lat, 0.0)
        assertEquals(60.000125, corrected[1].lat, 1e-9) // 5/20 drift, not half elapsed time
        assertEquals(track.last().lat, corrected[2].lat, 1e-9)
        assertEquals(60.0002, track[2].lat, 0.0) // never overwrite raw history
        assertEquals(Src.PDR, routeSegments(track).single().source)
        assertEquals(0.03336, trackKm(track), 0.001)
    }
    @Test fun phoneAnchoredSegmentIsTranslatedOnFirstGoodGps() {
        val track = listOf(p(0, 60.0, 0), p(10, 60.0001, 10), p(20, 60.001, 20, Src.GPS))
        val corrected = correctedRoute(track)
        assertEquals(60.0009, corrected[0].lat, 1e-9)
        assertEquals(60.001, corrected[1].lat, 1e-9)
        assertEquals(0.01112, trackKm(track), 0.001)
    }
    @Test fun spoofCannotAnchorButIndependentPdrRemainsVisible() {
        val track = listOf(p(0, 60.0, 0), p(10, 60.0001, 10, spoof = true), p(20, 61.0, 20, Src.GPS, true))
        assertEquals(track, correctedRoute(track))
        assertEquals(1, routeSegments(track).size)
        assertEquals(0.01862, trackKm(track), 0.001) // plus ten unlocated steps after the PDR segment
    }
    @Test fun largeGpsMismatchDoesNotStretchOrJoinEstimatedRoute() {
        val track = listOf(p(0, 60.0, 0, Src.GPS), p(10, 60.0001, 10), p(20, 60.0002, 20), p(30, 61.0, 30, Src.GPS), p(40, 61.0001, 40, Src.GPS))
        assertEquals(track, correctedRoute(track))
        assertEquals(listOf(Src.PDR, Src.GPS), routeSegments(track).map { it.source })
        assertTrue(trackKm(track) < 0.1)
    }
    @Test fun missingPositionAndPauseBreakCorrections() {
        for (src in listOf(Src.NONE, Src.PAUSE)) {
            val track = listOf(p(0, 60.0, 0), p(10, 60.0001, 10), p(20, 60.0, 20, src), p(30, 60.001, 30, Src.GPS))
            assertEquals(track, correctedRoute(track))
            assertEquals(if(src==Src.NONE) 0.02612 else 0.01112, trackKm(track), 0.001)
        }
    }
    @Test fun longSamplingGapAndCounterResetBreakCorrection() {
        val track = listOf(p(0, 60.0, 100, Src.GPS), p(10, 60.0001, 5), p(20, 60.0002, 10), p(30, 60.001, 20, Src.GPS))
        assertEquals(track, correctedRoute(track))
        val gap = listOf(p(0, 60.0, 0), p(10, 60.0001, 10), p(200, 60.001, 20, Src.GPS))
        assertEquals(gap, correctedRoute(gap))
    }
    @Test fun reanchoredPdrIsNotJoinedAcrossPositionJump() {
        val track = listOf(p(0, 60.0, 0), p(10, 60.0001, 15), p(20, 60.01, 30), p(30, 60.0101, 45))
        assertEquals(2, routeSegments(track).size)
        assertTrue(trackKm(track) < 0.1)
    }
    @Test fun boundedDriftCorrectionDoesNotCreateArtificialSamplingGap() {
        val track = listOf(p(0, 60.0, 0, Src.GPS), p(10, 60.0001, 15), p(20, 60.0002, 30), p(30, 60.0009, 45, Src.GPS))
        val segment = routeSegments(track).single()
        assertEquals(Src.PDR, segment.source)
        assertEquals(4, segment.points.size)
        assertEquals(60.0009, segment.points.last().lat, 1e-9)
    }
    @Test fun pdrWireSettingsAndCalibrationRoundTrip() {
        for (cmd in listOf(Cmd.PdrSettings(75, -450, true), Cmd.PdrSettings(150, 1800, false), Cmd.CalibCompass(true), Cmd.CalibCompass(false))) {
            assertEquals(cmd, Codec.decodeCmd(Codec.encode(cmd)))
        }
    }
}
