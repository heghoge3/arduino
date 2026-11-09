package com.vabugeode.app.achievements

import com.vabugeode.app.model.*
import com.vabugeode.app.ui.screens.color
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.math.*

class ExpeditionFactsTest {
    private val start=Instant.parse("2026-10-30T09:00:00Z").toEpochMilli()
    private val utc=ZoneId.of("UTC")
    private fun p(n:Int,steps:Int=n*20,src:PositionSource=PositionSource.NONE,lat:Double=60.0,lon:Double=30.0)=TrackPoint(
        start+n*60_000L,lat,lon,100.0,8,1.0,4.0,false,0,null,null,10.0,10.0,50.0,1013.0,100.0,4.0,80.0,80,steps,0.0,src,null)
    private fun analyze(track:List<TrackPoint>,marks:List<Mark> = emptyList(),hike:Long=start)=
        TrailAnalyzer.analyze(track,marks,hike,utc,nowMs=maxOf(start+86400_000,track.lastOrNull()?.tsUtcMs?:start))
    private fun m(id:Int,t:Long,type:MarkType,text:String="",lat:Double?=null,lon:Double?=null)=Mark(id,t,type,text,lat,lon)
    @Test fun colorsAreDistinctAndRareIsCoral(){
        assertEquals(5,Rarity.entries.map{it.color()}.distinct().size)
        assertEquals(Color(0xFFFF7866),Rarity.RARE.color())
        assertEquals(Color(0xFFA8ADB5),Rarity.COMMON.color())
    }
    @Test fun allOriginalIdsAndTargetsRemainStable(){
        val original=AchievementCatalog.public.take(80)
        assertEquals("r01",original.first().id);assertEquals("s3",original.last().id)
        assertEquals(1000.0,original.single{it.id=="r11"}.target,0.0)
        assertTrue((1..20).all{i->AchievementCatalog.all.any{it.id=="hidden$i"}})
        val additions=AchievementCatalog.public.drop(80)
        assertEquals(35,additions.size)
        assertEquals(Rarity.entries.toSet(),additions.map{it.rarity}.toSet())
    }
    @Test fun regionVisitRequiresWalkingTimeAndTrustedGps(){
        val city=ExpeditionPlaces.all.first()
        val track=(0..5).map{p(it,src=PositionSource.GPS,lat=city.lat,lon=city.lon)}
        assertEquals(1.0,analyze(track).values["expRegion:${city.id}"]!!,0.0)
        val car=track.map{it.copy(steps=0,speedKmh=30.0)}
        assertEquals(0.0,analyze(car).values["expRegion:${city.id}"]?:0.0,0.0)
        val spoof=track.map{it.copy(spoof=true)}
        assertTrue(analyze(spoof).sets["expRegions"].isNullOrEmpty())
        assertTrue(analyze(track.map{it.copy(src=PositionSource.START)}).sets["expRegions"].isNullOrEmpty())
    }
    @Test fun regionDurationDoesNotBridgeGapsOrPause(){
        val city=ExpeditionPlaces.all.first()
        val track=(0..3).map{p(it,src=PositionSource.GPS,lat=city.lat,lon=city.lon)}+
            (10..13).map{p(it,src=PositionSource.GPS,lat=city.lat,lon=city.lon)}
        assertTrue(analyze(track).sets["expRegions"].isNullOrEmpty())
    }
    @Test fun foundClapPairsDoNotReuseTheSameClap(){
        val track=(0..10).map{p(it)}
        val marks=(1..3).map{m(it,start+60_000L*it,MarkType.FOUND)}+m(4,start+240_000,MarkType.SYNC)
        assertEquals(1.0,analyze(track,marks).values["expFoundClaps"]!!,0.0)
        val more=marks+listOf(m(5,start+250_000,MarkType.SYNC),m(6,start+260_000,MarkType.SYNC))
        assertEquals(3.0,analyze(track,more).values["expFoundClaps"]!!,0.0)
    }
    @Test fun blindJournalRequiresDurationStepsAndTwoMarks(){
        val track=(0..20).map{p(it,steps=it*30)}
        val marks=listOf(m(1,start+300_000,MarkType.ENTER),m(2,start+600_000,MarkType.FOUND))
        assertEquals(1.0,analyze(track,marks).values["expBlindJournal"]!!,0.0)
        assertEquals(0.0,analyze(track,marks.take(1)).values["expBlindJournal"]?:0.0,0.0)
        assertEquals(0.0,analyze(track.map{it.copy(steps=0)},marks).values["expBlindJournal"]?:0.0,0.0)
        assertEquals(0.0,analyze(track.filterIndexed{i,_->i !in 9..12},marks).values["expBlindJournal"]?:0.0,0.0)
    }
    @Test fun invalidAndMissingEnvironmentDoesNotCreateWeatherChanges(){
        val warm=(0..30).map{p(it).copy(tempCorr=10.0+it/5.0,tempRaw=10.0+it/5.0)}
        assertTrue((analyze(warm).values["expWarmShift"]?:0.0)>=5)
        val bad=warm.mapIndexed{i,t->if(i==0)t.copy(humidity=999.0)else t}
        assertEquals(0.0,analyze(bad).values["expWarmShift"]?:0.0,0.0)
        val gap=warm.filterIndexed{i,_->i !in 10..15}
        assertEquals(0.0,analyze(gap).values["expWarmShift"]?:0.0,0.0)
    }
    @Test fun distinctGpsFindsCanIgnoreAnEarlierCentralPoint(){
        val centerLat=60.0;val centerLon=30.0
        val coords=listOf(0.0 to 0.0,100.0 to 0.0,-50.0 to 86.603,-50.0 to -86.603)
        val track=coords.mapIndexed{i,(north,east)->p(i*2,steps=i*200,src=PositionSource.GPS,
            lat=centerLat+north/111195,lon=centerLon+east/(111195*.5))}
        val marks=track.mapIndexed{i,t->m(i,t.tsUtcMs,MarkType.FOUND,lat=t.lat,lon=t.lon)}
        assertEquals(3.0,analyze(track,marks).values["expDistinctFound"]!!,0.0)
        assertEquals(0.0,analyze(track.map{it.copy(spoof=true)},marks).values["expDistinctFound"]?:0.0,0.0)
    }
    @Test fun closedGpsContourNeedsAContinuousRealPolygon(){
        val vertices=listOf(0.0 to 0.0,700.0 to 0.0,700.0 to 700.0,0.0 to 700.0,0.0 to 0.0)
        val track=mutableListOf<TrackPoint>()
        vertices.zipWithNext().forEach{(a,b)->repeat(10){j->
            val f=j/10.0;val north=a.first+(b.first-a.first)*f;val east=a.second+(b.second-a.second)*f
            track+=p(track.size,steps=track.size*100,src=PositionSource.GPS,lat=60+north/111195,lon=30+east/(111195*.5))
        }}
        track+=p(40,4000,PositionSource.GPS)
        assertTrue((analyze(track).values["expAreaKm2"]?:0.0)>.45)
        val lost=track.mapIndexed{i,t->if(i==20)t.copy(src=PositionSource.NONE)else t}
        assertEquals(0.0,analyze(lost).values["expAreaKm2"]?:0.0,0.0)
    }
    @Test fun weekendAndWeeklyCalendarUseDatesWithoutDuplicates(){
        val saturday=LocalDate.parse("2026-10-31")
        val facts=TrailFacts(days=mapOf(saturday to DayFacts(2000.0,6.0),saturday.plusDays(1) to DayFacts(2000.0,4.0)))
        assertEquals(10.0,TrailAnalyzer.aggregate(listOf(facts))["expWeekendKm"]!!,0.0)
        val weekdays=TrailFacts(days=(0..3).associate{saturday.minusWeeks(it.toLong()) to DayFacts(1500.0,1.0)})
        assertEquals(4.0,TrailAnalyzer.aggregate(listOf(weekdays))["expWeeklyStreak"]!!,0.0)
        val visits=TrailFacts(sets=mapOf("expRegions" to setOf("vyborg"),"expFoundVisits" to setOf("1:2|2026-10-30")))
        val merged=TrailAnalyzer.aggregate(listOf(visits,visits))
        assertEquals(1.0,merged["expRegions"]!!,0.0);assertEquals(1.0,merged["expFoundCells"]!!,0.0)
    }
    @Test fun aRestResearchPatternRequiresAllThreePhases(){
        val track=(0..30).map{n->p(n,steps=when{n<=10->n*50;n<=20->500;else->500+(n-20)*50})}
        assertEquals(1.0,analyze(track).values["expWalkRestWalk"]!!,0.0)
        assertEquals(0.0,analyze(track.take(25)).values["expWalkRestWalk"]?:0.0,0.0)
    }
}
