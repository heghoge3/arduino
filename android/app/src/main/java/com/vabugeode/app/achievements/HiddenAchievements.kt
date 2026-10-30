package com.vabugeode.app.achievements

import com.vabugeode.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** Не выводить закрытый каталог в интерфейс, журналы, уведомления или описания релизов. */
internal fun hiddenCatalog(): List<Achievement> {
    fun b(i:Int,t:String,c:String,g:BadgeGroup,r:Rarity,k:String,n:Double=1.0)=Achievement("hidden$i",t,c,g,r,k,n,secret=true)
    return listOf(
        b(1,"Ответ найден","Пройти 42 км за один поход.",BadgeGroup.ROUTE,Rarity.LEGENDARY,"hikeKm",42.0),
        b(2,"На грани нуля","Записать десять минут подряд в диапазоне −0,2…+0,2 °C.",BadgeGroup.WEATHER,Rarity.RARE,"xZeroMinutes",10.0),
        b(3,"03:33","Зафиксировать движение около 03:33.",BadgeGroup.LIGHT,Rarity.RARE,"x0333"),
        b(4,"Затишье","Записать четыре часа подряд с диапазоном температуры не более 0,6 °C.",BadgeGroup.WEATHER,Rarity.EPIC,"xStableHours",4.0),
        b(5,"Чёртова дюжина","Пройти 13 км в пятницу тринадцатого за один поход.",BadgeGroup.CALENDAR,Rarity.EPIC,"xFriday13Km",13.0),
        b(6,"Пи на тропе","Пройти 3,14 км 14 марта за один поход.",BadgeGroup.CALENDAR,Rarity.RARE,"xPiKm",3.14),
        b(7,"Летняя полночь","Двигаться ночью в период белых ночей на широте от 59°.",BadgeGroup.LIGHT,Rarity.RARE,"xWhiteNight"),
        b(8,"По ту сторону меридиана","Записать достоверное GPS-пересечение нулевой долготы.",BadgeGroup.GEO,Rarity.LEGENDARY,"xGreenwich"),
        b(9,"Два полушария","Записать достоверное GPS-пересечение экватора.",BadgeGroup.GEO,Rarity.LEGENDARY,"xEquator"),
        b(10,"Долгая тишина","Записать шесть часов подряд без достоверного GPS.",BadgeGroup.SIGNAL,Rarity.EPIC,"xSilenceHours",6.0),
        b(11,"Зимний свет","Пройти 1 км 21 или 22 декабря за один поход.",BadgeGroup.CALENDAR,Rarity.RARE,"xWinterKm",1.0),
        b(12,"Летний свет","Пройти 1 км 20 или 21 июня за один поход.",BadgeGroup.CALENDAR,Rarity.RARE,"xSummerKm",1.0),
        b(13,"Первая минута","Записать движение в первые пять минут нового года.",BadgeGroup.CALENDAR,Rarity.EPIC,"xNewYear"),
        b(14,"Запасной сценарий","Использовать все семь типов меток за один поход.",BadgeGroup.MARKS,Rarity.RARE,"xAllMarks",7.0),
        b(15,"Группа 2533","Пройти не менее 25,33 км за один поход.",BadgeGroup.ROUTE,Rarity.EPIC,"hikeKm",25.33),
        b(16,"Три времени суток","Пройти по километру ночью, утром и вечером за один поход.",BadgeGroup.TIME,Rarity.EPIC,"xThreeTimes"),
        b(17,"Восьмичасовой след","Накопить восемь часов движения за один поход.",BadgeGroup.TIME,Rarity.EPIC,"xMovingHours",8.0),
        b(18,"Ровное дыхание","Записать час движения без остановок и разрывов более трёх минут.",BadgeGroup.TIME,Rarity.RARE,"xMovingRunHours",1.0),
        b(19,"Чай на привале","Записать час стоянки без шагов после километра пути.",BadgeGroup.TIME,Rarity.UNCOMMON,"xRestHours",1.0),
        b(20,"Путь продолжается","Пройти три километра по шагам без GPS за один поход.",BadgeGroup.SIGNAL,Rarity.RARE,"xOfflineKm",3.0),
    )
}
internal fun updateHiddenFacts(v:MutableMap<String,Double>,p:TrackPoint,a:TrackPoint,dt:Double,km:Double,moving:Boolean,
    lost:Double,envOk:Boolean,date:LocalDate,hour:Int,minute:Int) {
    fun peak(k:String,n:Double){v[k]=max(v[k]?:0.0,n)}
    fun add(k:String,n:Double){v[k]=(v[k]?:0.0)+n}
    fun run(k:String,on:Boolean){v["run:$k"]=if(on)(v["run:$k"]?:0.0)+dt/3600 else 0.0;peak(k,v["run:$k"]!!)}
    if(moving&&hour==3&&minute in 32..34)peak("x0333",1.0)
    if(date.dayOfMonth==13&&date.dayOfWeek.value==5)add("xFriday13Km",km)
    if(date.monthValue==3&&date.dayOfMonth==14)add("xPiKm",km)
    if(date.monthValue==12&&date.dayOfMonth in 21..22)add("xWinterKm",km)
    if(date.monthValue==6&&date.dayOfMonth in 20..21)add("xSummerKm",km)
    if(moving&&date.monthValue==1&&date.dayOfMonth==1&&hour==0&&minute<5)peak("xNewYear",1.0)
    if(moving&&TrailAnalyzer.gps(p)&&p.lat>=59&&hour<3&&
        ((date.monthValue==6&&date.dayOfMonth>=11)||(date.monthValue==7&&date.dayOfMonth<=2)))peak("xWhiteNight",1.0)
    if(TrailAnalyzer.gps(p)&&TrailAnalyzer.gps(a)&&com.vabugeode.app.data.distanceM(a.lat,a.lon,p.lat,p.lon)<=dt*15+10){
        if(a.lon*p.lon<0&&abs(a.lon-p.lon)<1)peak("xGreenwich",1.0)
        if(a.lat*p.lat<0&&abs(a.lat-p.lat)<1)peak("xEquator",1.0)
    }
    peak("xSilenceHours",lost/3600)
    if(moving)add("xMovingHours",dt/3600)
    run("xMovingRunHours",moving)
    run("xRestHours",!moving&&(v["km"]?:0.0)>=1)
    run("xZeroHours",envOk&&abs(a.tempCorr)<=.2&&abs(p.tempCorr)<=.2)
    peak("xZeroMinutes",(v["xZeroHours"]?:0.0)*60)
    val part=when(hour){in 0..3->"Night";in 6..11->"Morning";in 18..23->"Evening";else->null}
    if(part!=null)add("part:$part",km)
    if(listOf("Night","Morning","Evening").all{(v["part:$it"]?:0.0)>=1})peak("xThreeTimes",1.0)
    peak("xOfflineKm",v["offlineKm"]?:0.0)
}
internal fun finalizeHiddenFacts(v:MutableMap<String,Double>,track:List<TrackPoint>,marks:List<Mark>,zone:ZoneId) {
    v["xAllMarks"]=marks.map{it.type}.distinct().size.toDouble()
    v.keys.filter{it.startsWith("run:")||it.startsWith("part:")}.toList().forEach{v.remove(it)}
}
