package com.vabugeode.app.achievements

import com.vabugeode.app.model.*
import com.vabugeode.app.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TrailAnalyzerTest {
    private val start=Instant.parse("2026-10-30T09:00:00Z").toEpochMilli()
    private val zone=ZoneId.of("UTC")
    private fun p(n:Int,steps:Int=n*100,src:PositionSource=PositionSource.NONE,lat:Double=60.0,lon:Double=30.0)=TrackPoint(
        start+n*60_000L,lat,lon,100.0,8,1.0,4.0,false,0,null,null,10.0,10.0,50.0,1013.0,100.0,4.0,80.0,80,steps,0.0,src,null)
    private fun analyze(t:List<TrackPoint>)=TrailAnalyzer.analyze(t,emptyList(),start,zone,nowMs=start+10*86_400_000)
    @Test fun catalogueIsExactly100With80PublicAnd20Hidden(){
        assertEquals(80,AchievementCatalog.public.size)
        assertEquals(100,AchievementCatalog.all.size)
        assertEquals(20,AchievementCatalog.all.count{it.secret})
        assertEquals(100,AchievementCatalog.all.map{it.id}.distinct().size)
        assertTrue(AchievementCatalog.all.all{it.target>0&&it.target.isFinite()})
    }
    @Test fun stepsWithoutGpsCountButGiveNoGeography(){
        val f=analyze((0..20).map{p(it)})
        assertEquals(2000.0,f.values["steps"]!!,.001)
        assertEquals(1.5,f.values["km"]!!,.001)
        assertEquals(1.5,f.values["offlineKm"]!!,.001)
        assertTrue(f.sets["cells"].isNullOrEmpty())
        assertEquals(20.0/60,f.values["movingHours"]!!,.001)
    }
    @Test fun gapsAndCounterResetsDoNotBecomeMovement(){
        val f=analyze(listOf(p(0),p(1),p(90),p(91,0),p(92,100)))
        assertEquals(2.0/60,f.values["movingHours"]!!,.001)
        assertEquals(1.0/60,f.values["recordingRunHours"]!!,.001)
        assertTrue((f.values["km"]?:0.0)<1)
    }
    @Test fun pausedTransferDoesNotCountStepsDistanceOrTime(){
        val f=analyze((0..10).map{p(it,src=PositionSource.PAUSE)})
        assertEquals(0.0,f.values["steps"]?:0.0,.001)
        assertEquals(0.0,f.values["km"]?:0.0,.001)
        assertEquals(0.0,f.values["movingHours"]?:0.0,.001)
    }
    @Test fun spoofedAndManualAnchorsCannotUnlockGeography(){
        val input=listOf(p(0,src=PositionSource.START),p(1,src=PositionSource.GPS,lat=59.99).copy(spoof=true),p(2,src=PositionSource.GPS,lat=60.01).copy(spoof=true))
        val f=analyze(input)
        assertTrue(f.sets["cells"].isNullOrEmpty())
        assertEquals(0.0,f.values["cross60"]?:0.0,.001)
    }
    @Test fun impossibleGpsJumpDoesNotCountDistanceOrCrossing(){
        val f=analyze(listOf(p(0,0,PositionSource.GPS,59.0),p(1,0,PositionSource.GPS,61.0)))
        assertEquals(0.0,f.values["km"]?:0.0,.001)
        assertEquals(0.0,f.values["cross60"]?:0.0,.001)
        assertEquals(0.0,f.values["excursionKm"]?:0.0,.001)
        assertEquals(1,f.sets["cells"]?.size)
    }
    @Test fun duplicatePacketsAndReorderedPointsAreNotDoubleCounted(){
        val t=(0..10).map{p(it)}
        val a=analyze(t);val b=analyze((t+t).reversed())
        assertEquals(a.values,b.values)
    }
    @Test fun stationaryGpsJitterDoesNotBecomeWalkingDistance(){
        val track=(0..30).map{p(it,0,PositionSource.GPS,60.0+(it%2)*.0001).copy(speedKmh=0.0)}
        val f=analyze(track)
        assertEquals(0.0,f.values["km"]?:0.0,.001)
        assertEquals(0.0,f.values["movingHours"]?:0.0,.001)
        assertEquals(0.0,TrailAnalyzer.aggregate(listOf(f))["directions"]!!,.001)
    }
    @Test fun repeatedInvalidEnvironmentCannotEarnDurationBadges(){
        val f=analyze((0..30).map{p(it).copy(tempCorr=Double.NaN,tempRaw=Double.NaN,humidity=Double.NaN,pressureHpa=Double.NaN)})
        listOf("coolMinutes","frostMinutes","warmMinutes","dryMinutes","humidMinutes","lowPressureMinutes").forEach{
            assertEquals(it,0.0,f.values[it]?:0.0,.001)
        }
    }
    @Test fun weatherDurationsRequireContinuousRecords(){
        val a=(0..8).map{p(it).copy(tempCorr=2.0)}
        val b=(20..28).map{p(it).copy(tempCorr=2.0)}
        val f=analyze(a+b)
        assertEquals(8.0,f.values["coolMinutes"]!!,.001)
    }
    @Test fun multipleHikesMergeCalendarDaysAndDoNotInventStreaks(){
        val day=LocalDate.parse("2026-10-30")
        val one=TrailFacts(days=mapOf(day to DayFacts(600.0,.4,600.0)))
        val two=TrailFacts(days=mapOf(day to DayFacts(600.0,.4,600.0),day.plusDays(2) to DayFacts(1200.0,1.0,600.0)))
        val metrics=TrailAnalyzer.aggregate(listOf(one,two))
        assertEquals(1200.0,metrics["daySteps"]!!,.001)
        assertEquals(2.0,metrics["activeDays"]!!,.001)
        assertEquals(1.0,metrics["streak"]!!,.001)
        assertEquals(2.0,metrics["months"]!!,.001)
    }
    @Test fun progressNeverDisplaysMoreThanTheGoal(){
        val a=AchievementCatalog.public.first()
        val progress=AchievementProgress(a,10000.0)
        assertEquals(1f,progress.fraction)
        assertFalse(progress.counter.contains("10000"))
    }
    @Test fun chimeProtocolHasNoMarkOrFindSideEffects(){
        val data=Codec.encode(Cmd.AchievementChime)
        assertEquals(Cmd.AchievementChime,Codec.decodeCmd(data))
        assertEquals(2,data.size)
        assertEquals(Proto.CMD_ACHIEVEMENT,Codec.cmdCode(data))
    }
}
