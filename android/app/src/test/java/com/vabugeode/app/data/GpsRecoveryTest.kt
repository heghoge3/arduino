package com.vabugeode.app.data

import com.vabugeode.app.model.*
import com.vabugeode.app.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GpsRecoveryTest {
    private fun message(mask: Int = 0x80, flagged: Boolean = true) = PointMsg(
        1793000000,60.0,30.0,1,flagged,mask,60.001,30.001,
        20.0,20.0,50.0,1000.0,0.0,100.0,4000,80,8,1.0,100,0.0,4.0,false)

    @Test fun legacyRecoveryPointIsNeitherRedNorPromotedToGps() {
        for (mask in listOf(0x80,0x88,0x82,0xC0,0xCA)) {
            val raw = message(mask)
            val p = (Codec.decodeMsg(Codec.encode(raw)) as PointMsg).toTrackPoint()
            assertFalse(p.spoof)
            assertEquals(mask,p.spoofMask)
            assertNull(p.spoofLat); assertNull(p.spoofLon)
            assertEquals(PositionSource.NONE,p.src)
            assertFalse(p.isRoutePoint())
            assertTrue(p.lat.isNaN()); assertTrue(p.lon.isNaN())
            assertNull(p.altGps); assertEquals(0.0,p.speedKmh,0.0)
            assertEquals(100,p.steps); assertEquals(20.0,p.tempCorr,0.0)
            assertEquals(0,summarize("test",1793000000,null,listOf(p),emptyList()).spoofPoints)
        }
    }

    @Test fun newFirmwareRecoveryPointPreservesGapAndStepDistance() {
        val before=message(0,false).toTrackPoint().copy(tsUtcMs=0,steps=100)
        val waiting=message(flagged=false).copy(src=0,timeUtc=60).toTrackPoint().copy(steps=180)
        val after=before.copy(tsUtcMs=120_000,steps=300,lat=60.001)
        val route=routeSegments(listOf(before,waiting,after)).single()
        assertEquals(PositionSource.NONE,route.source)
        assertEquals(listOf(before,after),route.points)
        assertEquals(.15,trackKm(listOf(before,waiting,after)),.00001)
    }

    @Test fun mixedEvidenceAndLegacyUnknownSpoofRemainRejectedAndRed() {
        for (mask in listOf(0,1,4,0x40,0x81,0x85,0x98,0xA8,0xB0)) {
            val p=message(mask).toTrackPoint()
            assertFalse("mask=$mask",GpsEvidence.recoveryOnly(mask))
            assertTrue(p.spoof)
            assertNotNull(p.spoofLat); assertNotNull(p.spoofLon)
            assertFalse(p.isRoutePoint())
            assertEquals(1,summarize("test",1793000000,null,listOf(p),emptyList()).spoofPoints)
        }
    }

    @Test fun explicitManualAnchorRemainsManualDuringRecovery() {
        val anchor=message().copy(startAnchor=true).toTrackPoint()
        assertEquals(PositionSource.START,anchor.src)
        assertEquals(60.0,anchor.lat,0.0)
        assertFalse(anchor.spoof)
        assertNull(anchor.spoofLat)
        assertTrue(anchor.isRoutePoint())
    }
}
