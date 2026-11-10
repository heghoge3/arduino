package com.vabugeode.app.data
import com.vabugeode.app.protocol.*
import com.vabugeode.app.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
class GpsPolicyTest {
 private fun point()=PointMsg(100,60.1161,31.3864,1,false,1,null,null,12.0,12.0,50.0,1000.0,70.0,70.0,4000,80,8,1.0,100,0.0,4.0,gpsPolicy=2)
 @Test fun policyAndBoundarySurviveWireAndHistory(){
   val m=point().copy(breakBefore=true,uncertain=true)
   val decoded=Codec.decodeMsg(Codec.encode(m)) as PointMsg
   assertEquals(m,decoded);val p=decoded.toTrackPoint()
   assertEquals(2,p.gpsPolicy);assertTrue(p.breakBefore);assertTrue(p.uncertain)
 }
 @Test fun geographyCannotRejectActualGpsOrEraseMark(){
   val zone=listOf(BlacklistPoint(60.1161,31.3864,5000,"test"))
   val raw=point().toTrackPoint()
   val kept=excludeBlacklisted(listOf(raw),zone).single()
   assertEquals(PositionSource.GPS,kept.src);assertFalse(kept.spoof)
   val mark=Mark(1,100000,MarkType.MARK,"test",60.1161,31.3864)
   assertEquals(mark,excludeBlacklistedMarks(listOf(mark),zone).single())
 }
 @Test fun gapDoesNotCreateChordInMapOrDistance(){
   val a=point().toTrackPoint();val b=a.copy(tsUtcMs=110000,lat=a.lat+0.01,breakBefore=true)
   assertEquals(0.0,trackKm(listOf(a,b)),0.0001)
   assertTrue(routeSegments(listOf(a,b)).isEmpty())
 }
 @Test fun correctionDoesNotPullPdrAcrossBoundary(){
   val a=point().toTrackPoint()
   val pdr=a.copy(tsUtcMs=101000,src=PositionSource.PDR,steps=a.steps+10)
   val gps=a.copy(tsUtcMs=102000,lat=a.lat+0.0002,steps=a.steps+20,breakBefore=true)
   assertEquals(pdr,correctedRoute(listOf(a,pdr,gps))[1])
 }
 @Test fun uncertainCoordinatesAreRetainedButNotTrustedGeometry(){
   val m=point().copy(src=0,uncertain=true,spoofLat=61.0,spoofLon=32.0,spoofMask=0x84)
   val p=(Codec.decodeMsg(Codec.encode(m)) as PointMsg).toTrackPoint()
   assertFalse(p.isRoutePoint());assertFalse(p.spoof);assertTrue(p.uncertain)
   assertEquals(61.0,p.spoofLat!!,0.000001)
 }
 @OptIn(ExperimentalCoroutinesApi::class)
 @Test fun statusPolicyDoesNotInventSpoofInsideArea()=runTest {
   val g=HikeLifecycleTest.Gadget()
   val r=ProtocolDeviceRepository(backgroundScope,g,listOf(BlacklistPoint(60.1161,31.3864,5000,"test")),elapsedMs={testScheduler.currentTime})
   runCurrent();r.connect();runCurrent()
   g.emit(InfoMsg(0,1,25,1,1,100,90,0,100,emptyList()));runCurrent()
   val hex=Regex("\"status\": \"([0-9a-f]+)\"").find(File("../../docs/protocol_vectors.json").readText())!!.groupValues[1]
   val base=Codec.decodeMsg(hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()) as StatusMsg
   g.emit(base.copy(seq=base.seq+1,src=0x81,flags=Proto.F_GPS_ON or Proto.F_SD_OK,spoofMask=1,lat=60.1161,lon=31.3864));runCurrent()
   assertFalse(r.status.value!!.gps.spoof);assertEquals(2,r.status.value!!.gps.gpsPolicy)
   assertEquals(PositionSource.GPS,r.status.value!!.positionSource)
   g.emit(base.copy(seq=base.seq+2,src=0xC0,flags=Proto.F_GPS_ON,spoofMask=0x84));runCurrent()
   assertTrue(r.status.value!!.gps.recovering);assertFalse(r.status.value!!.gps.spoof)
   assertEquals(PositionSource.NONE,r.status.value!!.positionSource)
 }
 @Test fun phoneEvidenceHasAgeAndProvider(){
   val c=Cmd.PhoneEvidence(60.0,30.0,20,21000,1)
   assertEquals(c,Codec.decodeCmd(Codec.encode(c)))
 }
}
