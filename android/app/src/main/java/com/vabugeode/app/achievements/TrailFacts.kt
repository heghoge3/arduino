package com.vabugeode.app.achievements

import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.distanceM
import com.vabugeode.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

/** Только наблюдённые интервалы: разрыв, пауза и сброс счётчика не превращаются в движение. */
data class DayFacts(val steps: Double = 0.0, val km: Double = 0.0, val seconds: Double = 0.0,
    val low: Double = Double.POSITIVE_INFINITY, val high: Double = Double.NEGATIVE_INFINITY) {
    operator fun plus(b: DayFacts) = DayFacts(steps+b.steps,km+b.km,seconds+b.seconds,min(low,b.low),max(high,b.high))
}
data class TrailFacts(val values: Map<String,Double> = emptyMap(), val days: Map<LocalDate,DayFacts> = emptyMap(),
    val sets: Map<String,Set<String>> = emptyMap())

object TrailAnalyzer {
    private val additive = setOf("km","steps","movingHours","recordedHours","nightKm","nightHours","hikes",
        "marks","found","sync","offlineKm","recoveries") + (0..7).map { "dir$it" } + ExpeditionAnalyzer.additiveKeys
    fun aggregate(parts: Collection<TrailFacts>): Map<String,Double> {
        val out=mutableMapOf<String,Double>(); val days=mutableMapOf<LocalDate,DayFacts>(); val sets=mutableMapOf<String,MutableSet<String>>()
        parts.forEach { f ->
            f.values.forEach { (k,v) -> if(v.isFinite()&&v>=0) out[k]=if(k in additive) (out[k]?:0.0)+v else max(out[k]?:0.0,v) }
            f.days.forEach { (d,v) -> days[d]=(days[d]?:DayFacts())+v }
            f.sets.forEach { (k,v) -> sets.getOrPut(k){mutableSetOf()}.addAll(v) }
        }
        out["daySteps"]=days.values.maxOfOrNull {it.steps}?:0.0
        out["dayKm"]=days.values.maxOfOrNull {it.km}?:0.0
        out["dayTempSpan"]=days.values.filter {it.seconds>=3600 && it.low.isFinite()&&it.high.isFinite()}.maxOfOrNull {it.high-it.low}?:0.0
        val active=days.filterValues {it.steps>=1000 || it.km>=1}.keys.sorted()
        out["activeDays"]=active.size.toDouble()
        out["months"]=active.map {it.monthValue}.toSet().size.toDouble()
        out["seasons"]=active.map {(it.monthValue%12)/3}.toSet().size.toDouble()
        var streak=0; var best=0; var last: LocalDate?=null
        active.forEach { d -> streak=if(last?.plusDays(1)==d) streak+1 else 1;best=max(best,streak);last=d }
        out["streak"]=best.toDouble()
        listOf("cells","markTypes","sunrises","sunsets").forEach {out[it]=(sets[it]?.size?:0).toDouble()}
        out["bothSun"]=(sets["sunrises"].orEmpty() intersect sets["sunsets"].orEmpty()).size.toDouble()
        out["directions"]=(0..7).count {(out["dir$it"]?:0.0)>=.1}.toDouble()
        ExpeditionAnalyzer.aggregate(out,days,sets)
        return out
    }
    fun gps(p: TrackPoint) = p.src==PositionSource.GPS && !p.spoof && p.sats>=4 && p.hdop in .1..5.0 &&
        p.lat.isFinite()&&p.lon.isFinite()&&p.lat in -90.0..90.0&&p.lon in -180.0..180.0

    fun analyze(input: List<TrackPoint>, marks: List<Mark>, hikeMs: Long, zone: ZoneId, stride: Double=.75,
                nowMs: Long=System.currentTimeMillis()): TrailFacts {
        val track=input.filter {it.tsUtcMs>=hikeMs && it.tsUtcMs<=nowMs+300_000 && it.tsUtcMs>=1_577_836_800_000}
            .distinctBy {it.tsUtcMs}.sortedBy {it.tsUtcMs}
        if(track.isEmpty()) return TrailFacts()
        val v=mutableMapOf<String,Double>(); val days=mutableMapOf<LocalDate,DayFacts>();val sets=mutableMapOf<String,MutableSet<String>>()
        fun add(k:String,n:Double){v[k]=(v[k]?:0.0)+n}
        fun peak(k:String,n:Double){v[k]=max(v[k]?:0.0,n)}
        fun flag(k:String){v[k]=1.0}
        fun collect(k:String,s:String){sets.getOrPut(k){mutableSetOf()}.add(s)}
        val runs=mutableMapOf<String,Double>()
        fun run(k:String,condition:Boolean,seconds:Double){val n=if(condition) (runs[k]?:0.0)+seconds/60 else 0.0;runs[k]=n;peak(k,n)}
        fun temp(p:TrackPoint)=p.tempCorr.isFinite()&&p.tempRaw.isFinite()&&p.tempCorr in -50.0..65.0&&p.tempRaw in -50.0..85.0
        fun pressure(p:TrackPoint)=p.pressureHpa.isFinite()&&p.pressureHpa in 300.0..1100.0
        val sunCache=mutableMapOf<String,SunCalc.SunTimes>()
        val acceptedGps=mutableSetOf<Long>()
        var recordingRun=0.0; var gpsRun=0.0; var lost=0.0;var lostSteps=0.0;var firstGps:TrackPoint?=null
        var geographicAnchor:TrackPoint?=null
        var pressureStart:TrackPoint?=null;var stableStart:TrackPoint?=null;var stableLow=0.0;var stableHigh=0.0
        val stepM=stride.takeIf {it.isFinite()&&it in .3..1.5}?:.75
        val first=track.first();val firstAge=(first.tsUtcMs-hikeMs)/1000.0
        if(firstAge in 1.0..180.0 && first.steps in 0..(firstAge*6).toInt() && first.src!=PositionSource.PAUSE) add("steps",first.steps.toDouble())
        track.forEachIndexed { i,p ->
            val date=Instant.ofEpochMilli(p.tsUtcMs).atZone(zone).toLocalDate()
            if(p.src==PositionSource.PAUSE)geographicAnchor=null
            val ref=geographicAnchor
            val age=ref?.let{(p.tsUtcMs-it.tsUtcMs)/1000.0}
            val good=gps(p) && (ref==null || age==null || age>180 ||
                distanceM(ref.lat,ref.lon,p.lat,p.lon)<=age*15+10)
            if(good){
                acceptedGps+=p.tsUtcMs
                geographicAnchor=p
                if(firstGps==null) firstGps=p
                val a=firstGps!!
                peak("excursionKm",distanceM(a.lat,a.lon,p.lat,p.lon)/1000)
                // Сетка ограничивает рост памяти; эллипсоидные клетки одинаковы по площади лишь приближённо.
                val row=floor((p.lat+90)*111.195/5).toInt()
                val bandLat=(row+.5)*5/111.195-90
                val col=floor((p.lon+180)*111.195*max(.01,cos(Math.toRadians(bandLat)))/5).toInt()
                collect("cells","$row:$col")
                if((v["km"]?:0.0)>=2 && distanceM(a.lat,a.lon,p.lat,p.lon)<=100)flag("loop")
            }
            if(temp(p)){
                val d=days[date]?:DayFacts();days[date]=d.copy(low=min(d.low,p.tempCorr),high=max(d.high,p.tempCorr))
            }
            val a=track.getOrNull(i-1)?:return@forEachIndexed
            val dt=(p.tsUtcMs-a.tsUtcMs)/1000.0
            val paused=a.src==PositionSource.PAUSE||p.src==PositionSource.PAUSE
            val continuous=dt in 1.0..180.0 && !paused && p.steps>=a.steps
            val ds=(p.steps.toLong()-a.steps.toLong()).takeIf {!paused && dt>0 && it>=0 && it<=dt*6}?.toDouble()?:0.0
            if(!continuous){
                runs.clear();recordingRun=0.0;gpsRun=0.0;lost=0.0;lostSteps=0.0;pressureStart=null;stableStart=null
                v.keys.filter {it.startsWith("run:")}.toList().forEach {v.remove(it)}
                return@forEachIndexed
            }
            add("steps",ds)
            val pair=good&&gps(a)
            val d=if(pair) distanceM(a.lat,a.lon,p.lat,p.lon) else 0.0
            val validPair=pair && d<=dt*15+10
            val gpsMeters=if(validPair && (ds>0 || a.speedKmh>=1.5 || p.speedKmh>=1.5) && d>=max(5.0,max(p.hdop,a.hdop)*3))d else 0.0
            val km=if(validPair)gpsMeters/1000 else ds*stepM/1000
            val moving=ds>0 || (gpsMeters>0 && p.speedKmh>=1.5)
            add("km",km);add("recordedHours",dt/3600)
            if(moving)add("movingHours",dt/3600)
            recordingRun+=dt;peak("recordingRunHours",recordingRun/3600)
            if(!pair)add("offlineKm",ds*stepM/1000)
            val middle=Instant.ofEpochMilli((a.tsUtcMs+p.tsUtcMs)/2).atZone(zone)
            // Короткий интервал через полночь делится между днями.
            val startDay=Instant.ofEpochMilli(a.tsUtcMs).atZone(zone).toLocalDate()
            if(startDay!=date){
                val boundary=date.atStartOfDay(zone).toInstant().toEpochMilli()
                val left=((boundary-a.tsUtcMs)/1000.0/dt).coerceIn(0.0,1.0)
                days[startDay]=(days[startDay]?:DayFacts())+DayFacts(ds*left,km*left,dt*left)
                days[date]=(days[date]?:DayFacts())+DayFacts(ds*(1-left),km*(1-left),dt*(1-left))
            }else days[date]=(days[date]?:DayFacts())+DayFacts(ds,km,dt)
            if(middle.hour<4){add("nightKm",km);if(moving)add("nightHours",dt/3600)}
            if(moving && good){
                val sunKey="$date:${(p.lat*10).roundToInt()}:${(p.lon*10).roundToInt()}"
                val sun=sunCache.getOrPut(sunKey){SunCalc.times(date,p.lat,p.lon)}
                if(sun.sunriseUtcMs?.let{abs(p.tsUtcMs-it)<=300_000}==true)collect("sunrises",date.toString())
                if(sun.sunsetUtcMs?.let{abs(p.tsUtcMs-it)<=300_000}==true)collect("sunsets",date.toString())
            }
            if(validPair){
                gpsRun+=gpsMeters/1000;peak("gpsRunKm",gpsRun)
                if(lost>=300 && lostSteps>=50)add("recoveries",1.0)
                lost=0.0;lostSteps=0.0
                if((a.lat<60 && p.lat>=60)||(a.lat>60&&p.lat<=60))flag("cross60")
                if((a.lon<30 && p.lon>=30)||(a.lon>30&&p.lon<=30))flag("cross30")
                if(gpsMeters>0){
                    val dy=p.lat-a.lat;val dx=(p.lon-a.lon)*cos(Math.toRadians(p.lat))
                    val bearing=(Math.toDegrees(atan2(dx,dy))+360)%360
                    add("dir${((bearing+22.5)/45).toInt()%8}",gpsMeters/1000)
                }
            }else{gpsRun=0.0;lost+=dt;lostSteps+=ds}
            val envOk=temp(a)&&temp(p)
            run("coolMinutes",envOk&&moving&&a.tempCorr<=5&&p.tempCorr<=5,dt)
            run("frostMinutes",envOk&&moving&&a.tempCorr<=0&&p.tempCorr<=0,dt)
            run("warmMinutes",envOk&&moving&&a.tempCorr>=25&&p.tempCorr>=25,dt)
            run("dryMinutes",a.humidity in 0.0..35.0&&p.humidity in 0.0..35.0&&a.humidity<35&&p.humidity<35,dt)
            run("humidMinutes",a.humidity in 90.0..100.0&&p.humidity in 90.0..100.0,dt)
            run("lowPressureMinutes",pressure(a)&&pressure(p)&&a.pressureHpa<995&&p.pressureHpa<995,dt)
            run("highPressureMinutes",pressure(a)&&pressure(p)&&a.pressureHpa>=1025&&p.pressureHpa>=1025,dt)
            val altOk=validPair&&p.sats>=6&&a.sats>=6&&p.hdop<=2.5&&a.hdop<=2.5
            run("alt200Minutes",altOk&&(a.altGps?:-999.0)>=200&&(p.altGps?:-999.0)>=200,dt)
            run("alt500Minutes",altOk&&(a.altGps?:-999.0)>=500&&(p.altGps?:-999.0)>=500,dt)
            if(pressure(a)&&pressure(p)){
                if(pressureStart==null)pressureStart=a
                val start=pressureStart!!;val span=p.tsUtcMs-start.tsUtcMs
                if(span in 3_600_000..10_800_000){peak("pressureFall",start.pressureHpa-p.pressureHpa);peak("pressureRise",p.pressureHpa-start.pressureHpa)}
                if(span>=10_800_000)pressureStart=p
            }else pressureStart=null
            updateHiddenFacts(v,p,a,dt,km,moving,lost,envOk,date,middle.hour,middle.minute)
            if(envOk){
                if(stableStart==null){stableStart=a;stableLow=a.tempCorr;stableHigh=a.tempCorr}
                stableLow=min(stableLow,p.tempCorr);stableHigh=max(stableHigh,p.tempCorr)
                if(stableHigh-stableLow>.6){stableStart=p;stableLow=p.tempCorr;stableHigh=p.tempCorr}
                peak("xStableHours",(p.tsUtcMs-stableStart!!.tsUtcMs)/3_600_000.0)
            }else stableStart=null
        }
        val filteredMarks=marks.filter {it.tsUtcMs>=hikeMs&&it.tsUtcMs<=nowMs+300_000}.distinctBy {it.id}
        v["marks"]=filteredMarks.size.toDouble();v["hikeMarks"]=v["marks"]!!
        v["sync"]=filteredMarks.count {it.type==MarkType.SYNC}.toDouble()
        v["found"]=filteredMarks.count {it.type==MarkType.FOUND}.toDouble()
        filteredMarks.forEach {collect("markTypes",it.type.name)}
        v["hikeKm"]=v["km"]?:0.0
        v["hikes"]=if((v["km"]?:0.0)>=1 && (v["movingHours"]?:0.0)>=5.0/60)1.0 else 0.0
        finalizeHiddenFacts(v,track,filteredMarks,zone)
        val expedition=ExpeditionAnalyzer.analyze(track,filteredMarks,acceptedGps,days,v,zone,stepM)
        return TrailFacts(v+expedition.values,days,sets+expedition.sets)
    }
}
