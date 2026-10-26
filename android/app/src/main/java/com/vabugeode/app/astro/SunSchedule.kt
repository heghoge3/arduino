package com.vabugeode.app.astro

import java.time.Instant
import java.time.ZoneOffset

/**
 * Все восходы, закаты и сумерки за отрезок времени — для многодневных походов.
 * Ночь на графике — от конца вечерних сумерек до начала утренних.
 */
data class SunSchedule(val events: List<Event>) {

    enum class Kind { DAWN, SUNRISE, SUNSET, DUSK }

    data class Event(val timeMs: Long, val kind: Kind)

    fun of(kind: Kind) = events.filter { it.kind == kind }

    /** Ближайшее событие этого вида после t */
    fun next(kind: Kind, t: Long) = events.firstOrNull { it.kind == kind && it.timeMs > t }

    /** Последнее событие этого вида до t */
    fun last(kind: Kind, t: Long) = events.lastOrNull { it.kind == kind && it.timeMs <= t }

    /** Тёмная часть суток, видимая в [t0, t1]: пары (начало, конец) — от вечерних до утренних сумерек */
    fun nights(t0: Long, t1: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var start: Long? = if (isNight(t0)) t0 else null
        for (e in events) {
            if (e.timeMs <= t0 || e.timeMs >= t1) continue
            if (e.kind == Kind.DUSK) start = e.timeMs
            if (e.kind == Kind.DAWN && start != null) {
                out += start to e.timeMs
                start = null
            }
        }
        if (start != null) out += start to t1
        return out
    }

    /** Ночь ли в момент t: последнее событие до t — конец сумерек */
    fun isNight(t: Long): Boolean {
        val prev = events.lastOrNull { it.timeMs <= t && (it.kind == Kind.DUSK || it.kind == Kind.DAWN) }
        return prev?.kind == Kind.DUSK
    }

    companion object {
        private const val DAY = 86_400_000L

        fun compute(fromMs: Long, toMs: Long, lat: Double, lon: Double): SunSchedule {
            val d0 = Instant.ofEpochMilli(fromMs - DAY).atZone(ZoneOffset.UTC).toLocalDate()
            val d1 = Instant.ofEpochMilli(toMs + DAY).atZone(ZoneOffset.UTC).toLocalDate()
            val out = ArrayList<Event>()
            var d = d0
            while (!d.isAfter(d1)) {
                SunCalc.eventUtc(d, lat, lon, -6.0, rising = true)?.let { out += Event(it, Kind.DAWN) }
                SunCalc.eventUtc(d, lat, lon, SunCalc.Event.SUNSET.altitudeDeg, rising = true)?.let { out += Event(it, Kind.SUNRISE) }
                SunCalc.eventUtc(d, lat, lon, SunCalc.Event.SUNSET.altitudeDeg, rising = false)?.let { out += Event(it, Kind.SUNSET) }
                SunCalc.eventUtc(d, lat, lon, SunCalc.Event.CIVIL_DUSK.altitudeDeg, rising = false)?.let { out += Event(it, Kind.DUSK) }
                d = d.plusDays(1)
            }
            return SunSchedule(out.sortedBy { it.timeMs }.distinctBy { it.kind to it.timeMs / 600_000 })
        }
    }
}
