package com.vabugeode.app.achievements

import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.distanceM
import com.vabugeode.app.model.*
import java.time.*
import kotlin.math.*

internal data class ExpeditionSample(val p:TrackPoint,val part:Int,val walked:Double,val rest:Double,val good:Boolean)
internal data class ExpeditionMark(val mark:Mark,val sample:ExpeditionSample)

/** Один дополнительный проход по существующему журналу; новые измерения не запрашиваются. */
internal object ExpeditionAnalyzer {
    val additiveKeys=setOf("expNotes")
    fun aggregate(out:MutableMap<String,Double>,days:Map<LocalDate,DayFacts>,sets:Map<String,Set<String>>) {
        val active=days.filterValues{it.steps>=1000||it.km>=1}.keys
        var weekly=0
        active.groupBy{it.dayOfWeek}.values.forEach { dates ->
            var run=0;var previous:LocalDate?=null
            dates.sorted().forEach {d->run=if(previous?.plusDays(7)==d)run+1 else 1;weekly=max(weekly,run);previous=d}
        }
        out["expWeeklyStreak"]=weekly.toDouble()
        out["expWeekendKm"]=days.keys.filter{it.dayOfWeek==DayOfWeek.SATURDAY}.maxOfOrNull {d->
            val a=days[d]?.km?:0.0;val b=days[d.plusDays(1)]?.km?:0.0
            if(a>=1&&b>=1)a+b else 0.0
        }?:0.0
        out["expCameraDays"]=sets["expCameraDays"].orEmpty().size.toDouble()
        val visits=sets["expFoundVisits"].orEmpty().map{it.substringBefore('|') to it.substringAfter('|')}
        out["expFoundCells"]=visits.map{it.first}.distinct().size.toDouble()
        out["expReturnDays"]=visits.groupBy{it.first}.values.maxOfOrNull{it.map{p->p.second}.distinct().size.toDouble()}?:0.0
        out["expRegions"]=sets["expRegions"].orEmpty().size.toDouble()
        out["expDayOutings"]=sets["expOutings"].orEmpty().groupBy{it.substringBefore('|')}.values.maxOfOrNull{it.size.toDouble()}?:0.0
        aggregateExpeditionHidden(out,days,sets)
    }
    fun cell(lat:Double,lon:Double,meters:Double):String {
        val row=floor((lat+90)*111195/meters).toInt()
        val middle=(row+.5)*meters/111195-90
        val col=floor((lon+180)*111195*max(.01,cos(Math.toRadians(middle)))/meters).toInt()
        return "$row:$col"
    }
    fun analyze(track:List<TrackPoint>,marks:List<Mark>,goodTimes:Set<Long>,days:Map<LocalDate,DayFacts>,
                values:Map<String,Double>,zone:ZoneId,stride:Double):TrailFacts {
        if(track.isEmpty())return TrailFacts()
        val v=mutableMapOf<String,Double>();val sets=mutableMapOf<String,MutableSet<String>>()
        fun flag(k:String){v[k]=1.0}
        fun peak(k:String,n:Double){v[k]=max(v[k]?:0.0,n)}
        fun collect(k:String,s:String){sets.getOrPut(k){mutableSetOf()}.add(s)}
        val samples=ArrayList<ExpeditionSample>(track.size)
        val sunCache=mutableMapOf<String,SunCalc.SunTimes>()
        fun sun(p:TrackPoint):SunCalc.SunTimes? {
            if(p.tsUtcMs !in goodTimes)return null
            val date=Instant.ofEpochMilli(p.tsUtcMs).atZone(zone).toLocalDate()
            return sunCache.getOrPut("$date:${(p.lat*10).roundToInt()}:${(p.lon*10).roundToInt()}"){SunCalc.times(date,p.lat,p.lon)}
        }
        fun at(time:Long):ExpeditionSample? {
            val i=samples.binarySearchBy(time){it.p.tsUtcMs}
            val index=if(i>=0)i else -i-2
            return samples.getOrNull(index)
        }
        var part=0;var walked=0.0;var rest=0.0;var moveRun=0.0;var beforeRest=0.0;var afterRest=0.0
        var weatherStart=track.first().tsUtcMs
        var blindStart:Long?=null;var blindSteps=0.0
        val blindRuns=mutableListOf<Triple<Long,Long,Double>>()
        fun closeBlind(end:Long){blindStart?.let{blindRuns+=Triple(it,end,blindSteps)};blindStart=null;blindSteps=0.0}
        val regionTime=DoubleArray(ExpeditionPlaces.all.size);val regionSteps=DoubleArray(regionTime.size)
        val directions=DoubleArray(4)
        val halves=mutableMapOf<LocalDate,DoubleArray>()
        var contourStart:TrackPoint?=null;var contourLast:TrackPoint?=null;var areaSum=0.0;var contourMeters=0.0
        fun clearContour(){contourStart=null;contourLast=null;areaSum=0.0;contourMeters=0.0}
        fun environment(p:TrackPoint)=p.tempCorr.isFinite()&&p.tempRaw.isFinite()&&p.tempCorr in -50.0..65.0&&p.tempRaw in -50.0..85.0&&p.humidity in 0.0..100.0&&p.pressureHpa in 300.0..1100.0
        track.forEachIndexed {index,p->
            val a=track.getOrNull(index-1)
            val good=p.tsUtcMs in goodTimes
            val dt=if(a==null)0.0 else (p.tsUtcMs-a.tsUtcMs)/1000.0
            val continuous=a!=null&&dt in 1.0..180.0&&a.src!=PositionSource.PAUSE&&p.src!=PositionSource.PAUSE&&p.steps>=a.steps&&p.steps.toLong()-a.steps.toLong()<=dt*6
            val ds=if(continuous && p.steps.toLong()-a!!.steps.toLong()<=dt*6)(p.steps-a.steps).toDouble() else 0.0
            if(!continuous){
                closeBlind(a?.tsUtcMs?:p.tsUtcMs);part++;walked=0.0;rest=0.0;moveRun=0.0;beforeRest=0.0;afterRest=0.0
                regionTime.fill(0.0);regionSteps.fill(0.0);clearContour();weatherStart=p.tsUtcMs
                samples+=ExpeditionSample(p,part,walked,rest,good)
                if(good){contourStart=p;contourLast=p}
                return@forEachIndexed
            }
            val pair=good&&a!!.tsUtcMs in goodTimes
            val dm=if(pair)distanceM(a!!.lat,a.lon,p.lat,p.lon)else 0.0
            val gpsMove=pair&&dm>=max(5.0,max(p.hdop,a!!.hdop)*3)&&dm<=dt*15+10&&(ds>0||p.speedKmh>=1.5||a.speedKmh>=1.5)
            val moving=ds>0||(gpsMove&&p.speedKmh>=1.5)
            walked+=ds
            if(moving){
                if(rest>0){afterRest=if(rest>=600&&beforeRest>=600)dt else 0.0}
                else if(afterRest>0)afterRest+=dt
                moveRun+=dt;rest=0.0
                if(afterRest>=600)flag("expWalkRestWalk")
            }else{
                if(moveRun>0){beforeRest=moveRun;moveRun=0.0;afterRest=0.0}
                rest+=dt
            }
            val s=ExpeditionSample(p,part,walked,rest,good);samples+=s
            val local=Instant.ofEpochMilli(p.tsUtcMs).atZone(zone);val date=local.toLocalDate()
            val oldLocal=Instant.ofEpochMilli(a!!.tsUtcMs).atZone(zone)
            if(oldLocal.toLocalDate()==date){
                val h=halves.getOrPut(date){DoubleArray(2)}
                if(oldLocal.hour<12&&local.hour<12)h[0]+=ds*stride/1000
                if(oldLocal.hour>=16&&local.hour>=16)h[1]+=ds*stride/1000
            }
            if(pair){
                if(blindStart!=null)closeBlind(a.tsUtcMs)
                ExpeditionPlaces.all.forEachIndexed {i,place->
                    val inside=distanceM(place.lat,place.lon,p.lat,p.lon)<=5000&&distanceM(place.lat,place.lon,a.lat,a.lon)<=5000
                    if(inside){regionTime[i]+=dt;regionSteps[i]+=ds}else{regionTime[i]=0.0;regionSteps[i]=0.0}
                    if(regionTime[i]>=300&&regionSteps[i]>=100){flag("expRegion:${place.id}");collect("expRegions",place.id);collect("expRegionDays","${place.id}|$date")}
                }
                if(gpsMove){
                    val angle=(Math.toDegrees(atan2((p.lon-a.lon)*cos(Math.toRadians(p.lat)),p.lat-a.lat))+360)%360
                    directions[((angle+45)/90).toInt()%4]+=dm
                }
            }else{
                regionTime.fill(0.0);regionSteps.fill(0.0)
                if(!good&&a.tsUtcMs !in goodTimes){if(blindStart==null)blindStart=a.tsUtcMs;blindSteps+=ds}
                else closeBlind(a.tsUtcMs)
            }
            if(pair){
                if(contourStart==null){contourStart=a;contourLast=a}
                val origin=contourStart!!;val last=contourLast!!
                fun x(t:TrackPoint)=Math.toRadians(((t.lon-origin.lon+540)%360)-180)*6371000*cos(Math.toRadians(origin.lat))
                fun y(t:TrackPoint)=Math.toRadians(t.lat-origin.lat)*6371000
                areaSum+=x(last)*y(p)-x(p)*y(last);contourLast=p
                if(gpsMove)contourMeters+=dm
                if(contourMeters>=2000&&distanceM(origin.lat,origin.lon,p.lat,p.lon)<=100)peak("expAreaKm2",abs(areaSum)/2_000_000)
            }else clearContour()
            val validEnv=environment(p)
            if(!validEnv)weatherStart=p.tsUtcMs+1
            val half=at(p.tsUtcMs-1_800_000)
            if(validEnv&&half!=null&&environment(half.p)&&half.part==part&&half.p.tsUtcMs>=weatherStart&&p.tsUtcMs-half.p.tsUtcMs in 1_800_000..5_400_000){
                val change=p.tempCorr-half.p.tempCorr;val hum=abs(p.humidity-half.p.humidity)
                peak("expWarmShift",change)
                if(abs(change)>=3)peak("expAirChange",hum)
                val sunset=sun(p)?.sunsetUtcMs
                if(sunset!=null&&half.p.tsUtcMs>=sunset)peak("expEveningCool",-change)
            }
            val hour=at(p.tsUtcMs-3_600_000)
            if(validEnv&&hour!=null&&environment(hour.p)&&hour.part==part&&hour.p.tsUtcMs>=weatherStart&&p.tsUtcMs-hour.p.tsUtcMs in 3_600_000..5_400_000){
                if(hour.p.pressureHpa-p.pressureHpa>=3&&p.humidity-hour.p.humidity>=15)flag("expWeatherTogether")
            }
            val sunrise=sun(p)?.sunriseUtcMs
            if(rest>=600&&walked*stride>=1000&&sunrise!=null&&sunrise in (p.tsUtcMs-(rest*1000).toLong())..p.tsUtcMs)flag("expDawnRest")
        }
        closeBlind(track.last().tsUtcMs)
        v["expCardinals"]=directions.count{it>=250}.toDouble()
        if(halves.values.any{it[0]>=2&&it[1]>=2})flag("expDayHalves")
        if(days.keys.any{d->days[d]!!.steps>=500&&d.plusDays(1).month!=d.month&&(days[d.plusDays(1)]?.steps?:0.0)>=500})flag("expMonthBoundary")
        if((values["hikeKm"]?:0.0)>=2&&(values["movingHours"]?:0.0)>=1.0/6){
            val date=Instant.ofEpochMilli(track.first().tsUtcMs).atZone(zone).toLocalDate()
            collect("expOutings","$date|${track.first().tsUtcMs}")
        }
        fun nearest(time:Long):ExpeditionSample? {
            val i=samples.binarySearchBy(time){it.p.tsUtcMs}
            if(i>=0)return samples[i]
            return listOfNotNull(samples.getOrNull(-i-2),samples.getOrNull(-i-1)).minByOrNull{abs(it.p.tsUtcMs-time)}?.takeIf{abs(it.p.tsUtcMs-time)<=120_000}
        }
        val geo=marks.mapNotNull {m->
            val sample=nearest(m.tsUtcMs)?.takeIf{it.good}?:return@mapNotNull null
            val lat=m.lat?.takeIf{it.isFinite()&&it in -90.0..90.0}?:return@mapNotNull null
            val lon=m.lon?.takeIf{it.isFinite()&&it in -180.0..180.0}?:return@mapNotNull null
            if(distanceM(lat,lon,sample.p.lat,sample.p.lon)>150)return@mapNotNull null
            ExpeditionMark(m,sample)
        }
        val notes=marks.filter{it.type==MarkType.TEXT&&it.text.trim().length>=8}
        v["expNotes"]=notes.size.toDouble()
        if(notes.isNotEmpty()&&marks.any{it.type==MarkType.ENTER}&&marks.any{it.type==MarkType.FOUND})flag("expFieldNotes")
        notes.forEach{m->val s=at(m.tsUtcMs)?.takeIf{m.tsUtcMs-it.p.tsUtcMs<=120_000};if(m.text.trim().length>=20&&s!=null&&s.rest>=600&&s.walked*stride>=1000)flag("expRestNote")}
        val usedClaps=mutableSetOf<Int>()
        marks.filter{it.type==MarkType.FOUND}.sortedBy{it.tsUtcMs}.forEach {f->
            val clap=marks.filter{it.type==MarkType.SYNC&&it.id !in usedClaps&&it.tsUtcMs-f.tsUtcMs in 0..300_000}.minByOrNull{it.tsUtcMs}
            if(clap!=null)usedClaps+=clap.id
        }
        v["expFoundClaps"]=usedClaps.size.toDouble()
        marks.filter{it.type==MarkType.SYNC}.forEach{collect("expCameraDays",Instant.ofEpochMilli(it.tsUtcMs).atZone(zone).toLocalDate().toString())}
        val distinct=mutableListOf<ExpeditionMark>()
        geo.filter{it.mark.type==MarkType.FOUND}.sortedBy{it.mark.tsUtcMs}.forEach {f->
            if(distinct.all{distanceM(f.mark.lat!!,f.mark.lon!!,it.mark.lat!!,it.mark.lon!!)>=150})distinct+=f
            val date=Instant.ofEpochMilli(f.mark.tsUtcMs).atZone(zone).toLocalDate()
            collect("expFoundVisits","${cell(f.mark.lat!!,f.mark.lon!!,500.0)}|$date")
        }
        var separated=minOf(3,distinct.size)
        if(separated<3){
            val locations=geo.filter{it.mark.type==MarkType.FOUND}.map{it.mark}
            findTriple@ for(i in locations.indices)for(j in i+1 until locations.size){
                val a=locations[i];val b=locations[j]
                if(distanceM(a.lat!!,a.lon!!,b.lat!!,b.lon!!)<150)continue
                separated=maxOf(separated,2)
                for(k in j+1 until locations.size){val c=locations[k]
                    if(distanceM(a.lat,a.lon,c.lat!!,c.lon!!)>=150&&distanceM(b.lat,b.lon,c.lat,c.lon)>=150){separated=3;break@findTriple}
                }
            }
        }
        v["expDistinctFound"]=separated.toDouble()
        geo.forEach {g->
            val m=g.mark
            if(m.type==MarkType.SYNC&&sun(g.sample.p)?.sunsetUtcMs?.let{abs(m.tsUtcMs-it)<=900_000}==true)flag("expSunsetClap")
            if(m.type==MarkType.ENTER||m.type==MarkType.FOUND){
                val delay=if(m.type==MarkType.ENTER)900_000 else 1_800_000
                val required=if(m.type==MarkType.ENTER)500.0 else 1000/stride
                if(samples.any{s->s.good&&s.part==g.sample.part&&s.p.tsUtcMs-m.tsUtcMs>=delay&&s.walked-g.sample.walked>=required&&distanceM(m.lat!!,m.lon!!,s.p.lat,s.p.lon)<=100}){
                    flag(if(m.type==MarkType.ENTER)"expEntryReturn" else "expFoundReturn")
                }
            }
        }
        if(blindRuns.any{(start,end,count)->end-start>=1_200_000&&count>=500&&marks.count{it.tsUtcMs in start..end}>=2})flag("expBlindJournal")
        analyzeExpeditionHidden(v,sets,samples,marks,geo,blindRuns,zone,stride)
        return TrailFacts(v,sets=sets)
    }
}
