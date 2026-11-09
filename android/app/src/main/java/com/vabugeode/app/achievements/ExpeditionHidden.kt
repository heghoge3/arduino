package com.vabugeode.app.achievements

import com.vabugeode.app.model.*
import com.vabugeode.app.data.distanceM
import com.vabugeode.app.astro.SunCalc
import java.time.*
import kotlin.math.*

/** Закрытый раздел: не включать эти строки в описания релизов, логи и снимки закрытых карточек. */
internal fun expeditionHiddenCatalog():List<Achievement> {
    fun b(i:Int,t:String,d:String,g:BadgeGroup,r:Rarity)=Achievement("hidden$i",t,d,g,r,"xe$i",1.0,secret=true)
    return listOf(
        b(21,"Идеальный дубль","Три раза за поход поставить хлопушку не позднее 20 секунд после отдельной метки «Нашли».",BadgeGroup.MARKS,Rarity.RARE),
        b(22,"Закрывающий кадр","Пройти 500 шагов от GPS-метки «Зашли», через 15 минут вернуться к ней в 100 м и поставить GPS-хлопушку, без разрывов.",BadgeGroup.MARKS,Rarity.EPIC),
        b(23,"На линии света","Отметить GPS-находки до и после заката в пределах 15 минут от него.",BadgeGroup.LIGHT,Rarity.EPIC),
        b(24,"Между строк — лес","Три текстовые метки подряд в одном походе начинаются с букв Л, Е, С.",BadgeGroup.MARKS,Rarity.RARE),
        b(25,"Часы съёмочной группы","Поставить хлопушки в первые 60 секунд трёх разных часов за поход.",BadgeGroup.TIME,Rarity.RARE),
        b(26,"Тёплые строки","На привале после километра по шагам записать заметку от 20 символов при измеренной температуре не выше +5 °C.",BadgeGroup.WEATHER,Rarity.UNCOMMON),
        b(27,"Одна точка — три истории","GPS-метки «Зашли», «Нашли» и «Интересно» оказались попарно в 100 м друг от друга за один поход.",BadgeGroup.MARKS,Rarity.RARE),
        b(28,"Место дождалось","Отметить GPS-находки в одной 500-метровой ячейке с интервалом от 30 дней.",BadgeGroup.GEO,Rarity.EPIC),
        b(29,"Традиция и продолжение","Совместить четыре еженедельных выхода в один день недели и двухдневные выходные от 10 км.",BadgeGroup.CALENDAR,Rarity.LEGENDARY),
        b(30,"Областной уикенд","За одни выходные выполнить GPS-условия прогулки в двух разных окрестностях областного набора.",BadgeGroup.GEO,Rarity.EPIC),
        b(31,"Четыре температурные краски","За один поход двигаться от двух минут в каждом из четырёх разных пятиградусных диапазонов температуры.",BadgeGroup.WEATHER,Rarity.RARE),
        b(32,"Треугольник воспоминаний","Первые три GPS-находки похода образуют треугольник площадью от 0,02 км².",BadgeGroup.GEO,Rarity.EPIC),
        b(33,"Без лишних слов","Пройти по шагам три километра до первой метки похода.",BadgeGroup.ROUTE,Rarity.UNCOMMON),
        b(34,"Последняя страница дня","На привале от 10 минут оставить заметку от 50 символов между 23:50 и 23:59.",BadgeGroup.TIME,Rarity.RARE),
        b(35,"Идеальная пауза","Записать отрезки движения от 10 минут до и после привала длиной 14–16 минут.",BadgeGroup.TIME,Rarity.EPIC),
    )
}
internal fun aggregateExpeditionHidden(out:MutableMap<String,Double>,days:Map<LocalDate,DayFacts>,sets:Map<String,Set<String>>) {
    val visits=sets["expFoundVisits"].orEmpty().groupBy{it.substringBefore('|')}
    if(visits.values.any{rows->
        val dates=rows.mapNotNull{runCatching{LocalDate.parse(it.substringAfter('|'))}.getOrNull()}
        dates.isNotEmpty()&&java.time.temporal.ChronoUnit.DAYS.between(dates.minOrNull(),dates.maxOrNull())>=30
    })out["xe28"]=1.0
    if((out["expWeeklyStreak"]?:0.0)>=4&&(out["expWeekendKm"]?:0.0)>=10)out["xe29"]=1.0
    val weekends=sets["expRegionDays"].orEmpty().mapNotNull{s->
        val date=runCatching{LocalDate.parse(s.substringAfter('|'))}.getOrNull()?:return@mapNotNull null
        if(date.dayOfWeek==DayOfWeek.SATURDAY||date.dayOfWeek==DayOfWeek.SUNDAY)
            (if(date.dayOfWeek==DayOfWeek.SUNDAY)date.minusDays(1)else date) to s.substringBefore('|') else null
    }.groupBy{it.first}
    if(weekends.values.any{it.map{p->p.second}.distinct().size>=2})out["xe30"]=1.0
}
internal fun analyzeExpeditionHidden(v:MutableMap<String,Double>,sets:MutableMap<String,MutableSet<String>>,
    samples:List<ExpeditionSample>,marks:List<Mark>,geo:List<ExpeditionMark>,blind:List<Triple<Long,Long,Double>>,zone:ZoneId,stride:Double) {
    fun flag(i:Int){v["xe$i"]=1.0}
    val used=mutableSetOf<Int>()
    marks.filter{it.type==MarkType.FOUND}.sortedBy{it.tsUtcMs}.forEach{f->
        marks.firstOrNull{it.type==MarkType.SYNC&&it.id !in used&&it.tsUtcMs-f.tsUtcMs in 0..20_000}?.let{used+=it.id}
    }
    if(used.size>=3)flag(21)
    val entries=geo.filter{it.mark.type==MarkType.ENTER}
    if(geo.filter{it.mark.type==MarkType.SYNC}.any{c->entries.any{a->
        c.sample.part==a.sample.part&&c.mark.tsUtcMs-a.mark.tsUtcMs>=900_000&&c.sample.walked-a.sample.walked>=500&&
            distanceM(a.mark.lat!!,a.mark.lon!!,c.mark.lat!!,c.mark.lon!!)<=100
    }})flag(22)
    val found=geo.filter{it.mark.type==MarkType.FOUND}.sortedBy{it.mark.tsUtcMs}
    val sides=mutableMapOf<LocalDate,MutableSet<Boolean>>()
    found.forEach{f->
        val date=Instant.ofEpochMilli(f.mark.tsUtcMs).atZone(zone).toLocalDate()
        val sunset=SunCalc.times(date,f.mark.lat!!,f.mark.lon!!).sunsetUtcMs
        if(sunset!=null&&abs(f.mark.tsUtcMs-sunset)<=900_000)sides.getOrPut(date){mutableSetOf()}.add(f.mark.tsUtcMs>=sunset)
    }
    if(sides.values.any{it.size==2})flag(23)
    val initials=marks.filter{it.type==MarkType.TEXT&&it.text.isNotBlank()}.sortedBy{it.tsUtcMs}.map{it.text.trim().first().uppercaseChar()}
    if(initials.windowed(3).any{it==listOf('Л','Е','С')})flag(24)
    val hours=marks.filter{it.type==MarkType.SYNC}.map{Instant.ofEpochMilli(it.tsUtcMs).atZone(zone)}
        .filter{it.minute==0}.map{it.toLocalDate().toString()+":"+it.hour}.distinct()
    if(hours.size>=3)flag(25)
    val notes=marks.filter{it.type==MarkType.TEXT}
    notes.forEach{m->
        val s=samples.lastOrNull{it.p.tsUtcMs<=m.tsUtcMs&&m.tsUtcMs-it.p.tsUtcMs<=120_000}
        if(s!=null&&s.rest>=600){
            if(m.text.trim().length>=20&&s.walked*stride>=1000&&s.p.tempCorr.isFinite()&&s.p.tempCorr<=5)flag(26)
            val date=Instant.ofEpochMilli(m.tsUtcMs).atZone(zone)
            if(m.text.trim().length>=50&&date.hour==23&&date.minute>=50)flag(34)
        }
    }
    val interesting=geo.filter{it.mark.type==MarkType.INTERESTING}
    if(entries.any{a->found.any{b->distanceM(a.mark.lat!!,a.mark.lon!!,b.mark.lat!!,b.mark.lon!!)<=100&&interesting.any{c->
        distanceM(a.mark.lat,a.mark.lon,c.mark.lat!!,c.mark.lon!!)<=100&&distanceM(b.mark.lat,b.mark.lon,c.mark.lat,c.mark.lon)<=100
    }}})flag(27)
    val bins=mutableMapOf<Int,Double>()
    samples.zipWithNext().forEach{(a,b)->
        val dt=(b.p.tsUtcMs-a.p.tsUtcMs)/1000.0
        if(a.part==b.part&&dt in 1.0..180.0&&b.walked>a.walked&&a.p.tempCorr in -50.0..65.0&&b.p.tempCorr in -50.0..65.0&&a.p.tempRaw.isFinite()&&b.p.tempRaw.isFinite()){
            val bin=floor(b.p.tempCorr/5).toInt()
            if(floor(a.p.tempCorr/5).toInt()==bin)bins[bin]=(bins[bin]?:0.0)+dt
        }
    }
    if(bins.values.count{it>=120}>=4)flag(31)
    if(found.size>=3){
        val a=found[0].mark;val b=found[1].mark;val c=found[2].mark
        fun x(m:Mark)=Math.toRadians(((m.lon!!-a.lon!!+540)%360)-180)*6371000*cos(Math.toRadians(a.lat!!))
        fun y(m:Mark)=Math.toRadians(m.lat!!-a.lat!!)*6371000
        if(abs(x(b)*y(c)-x(c)*y(b))/2>=20000)flag(32)
    }
    val first=marks.minOfOrNull{it.tsUtcMs}?:Long.MAX_VALUE
    if(samples.any{it.p.tsUtcMs<first&&it.walked*stride>=3000})flag(33)
    var move=0.0;var before=0.0;var rest=0.0;var candidate=false;var after=0.0
    samples.zipWithNext().forEach{(a,b)->
        val dt=(b.p.tsUtcMs-a.p.tsUtcMs)/1000.0
        if(a.part!=b.part||dt !in 1.0..180.0){move=0.0;before=0.0;rest=0.0;candidate=false;after=0.0}
        else if(b.walked>a.walked){
            if(rest>0){candidate=before>=600&&rest in 840.0..960.0;after=0.0}
            if(candidate){after+=dt;if(after>=600)flag(35)}
            rest=0.0;move+=dt
        }else{if(move>0){before=move;move=0.0};rest+=dt;candidate=false;after=0.0}
    }
}
