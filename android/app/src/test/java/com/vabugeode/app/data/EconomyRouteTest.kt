package com.vabugeode.app.data

import com.vabugeode.app.model.*
import com.vabugeode.app.protocol.*
import org.junit.Assert.*
import org.junit.Test

class EconomyRouteTest {
    private fun p(t:Long,steps:Int,src:PositionSource,lat:Double=60.0,lon:Double=30.0,spoof:Boolean=false)=TrackPoint(
        t*1000,lat,lon,null,8,1.0,0.0,spoof,0,null,null,20.0,20.0,50.0,1000.0,0.0,4.0,1.0,80,steps,0.0,src,null)
    @Test fun gapIsDottedAndDistanceUsesStepsNotChord() {
        val points=listOf(p(0,100,PositionSource.GPS),p(60,180,PositionSource.NONE),p(120,300,PositionSource.GPS,60.001))
        val segment=routeSegments(points).single()
        assertEquals(PositionSource.NONE,segment.source)
        assertEquals(2,segment.points.size)
        assertEquals(.15,trackKm(points),.00001)
        assertEquals(.16,trackKm(points,.8),.00001)
    }
    @Test fun manualStartWorksWithoutTrainingAndIsNotGps() {
        val points=listOf(p(0,0,PositionSource.START),p(60,80,PositionSource.NONE),p(120,200,PositionSource.GPS,60.001))
        assertEquals(PositionSource.NONE,routeSegments(points).single().source)
        assertEquals(.15,trackKm(points),.00001)
        val msg=PointMsg(1793000000,0.0,0.0,0,false,0,null,null,20.0,20.0,50.0,1000.0,0.0,null,4000,80,0,null,0,Double.NaN,0.0,true)
        val decoded=Codec.decodeMsg(Codec.encode(msg)) as PointMsg
        assertTrue(decoded.startAnchor)
        assertEquals(0.0,decoded.lat!!,0.0)
        assertEquals(PositionSource.START,decoded.toTrackPoint().src)
    }
    @Test fun spoofPointsNeverBecomeGeometryButUnknownStepsCount() {
        val points=listOf(p(0,0,PositionSource.GPS),p(60,80,PositionSource.GPS,61.0,spoof=true),p(120,200,PositionSource.GPS,60.001))
        assertEquals(2,routeSegments(points).single().points.size)
        assertFalse(routeSegments(points).flatMap {it.points}.any {it.lat==61.0})
        assertEquals(.15,trackKm(points),.00001)
    }
    @Test fun pausePowerGapCounterResetAndImpossibleTransferAreNotConnected() {
        val start=p(0,100,PositionSource.GPS)
        val end=p(120,300,PositionSource.GPS,60.001)
        assertTrue(routeSegments(listOf(start,p(60,180,PositionSource.PAUSE),end)).isEmpty())
        assertEquals(0.0,trackKm(listOf(start,p(60,180,PositionSource.PAUSE),end)),0.0)
        assertTrue(routeSegments(listOf(start,p(600,180,PositionSource.NONE),end.copy(tsUtcMs=660000))).isEmpty())
        assertTrue(routeSegments(listOf(start,p(60,10,PositionSource.NONE),end.copy(steps=20))).isEmpty())
        assertTrue(routeSegments(listOf(start,p(60,180,PositionSource.NONE),end.copy(lat=61.0))).isEmpty())
    }
    @Test fun cachedGpsInsideNewBanBecomesSuspiciousButManualAnchorRemainsManual() {
        val zone=listOf(BlacklistPoint(60.1161,31.3864,5000,"known"))
        val old=p(0,0,PositionSource.GPS,60.11741,31.36770)
        val result=excludeBlacklisted(listOf(old),zone).single()
        assertTrue(result.spoof)
        assertFalse(result.isRoutePoint())
        assertEquals(old.lat,result.spoofLat!!,0.0)
        assertEquals(old.copy(src=PositionSource.START),excludeBlacklisted(listOf(old.copy(src=PositionSource.START)),zone).single())
    }
    @Test fun newCommandsAndDeviceSettingsRoundTrip() {
        for(cmd in listOf(Cmd.SetStart(1793000000,60.0,30.0),Cmd.TemperatureOffset(-350),Cmd.Blacklist(listOf(Cmd.Blacklist.Entry(60.0,30.0,1000)),true)))
            assertEquals(cmd,Codec.decodeCmd(Codec.encode(cmd)))
        val info=InfoMsg(0,1,16,1,20,7600,7500,0,1793000000,emptyList(),tempOffset100=-350,strideCm=82)
        assertEquals(info,Codec.decodeMsg(Codec.encode(info)))
    }
}
