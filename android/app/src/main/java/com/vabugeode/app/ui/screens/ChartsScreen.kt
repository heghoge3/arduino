package com.vabugeode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.ChartSeries
import com.vabugeode.app.ui.components.KeyValue
import com.vabugeode.app.ui.components.LineChart
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.components.Waiting
import com.vabugeode.app.ui.theme.Palette
import com.vabugeode.app.astro.SunSchedule
import com.vabugeode.app.ui.theme.Fonts
import kotlin.math.abs

/** Показатели для графиков. */
enum class Metric(
    val title: String,
    val unit: String,
    val digits: Int,
    val color: Color,
    val value: (TrackPoint) -> Double,
) {
    TEMP("Температура", "°C", 1, Palette.Temp, { it.tempCorr }),
    PRESSURE("Давление", "гПа", 1, Palette.Pressure, { it.pressureHpa }),
    HUMIDITY("Влажность", "%", 0, Palette.Humidity, { it.humidity }),
    ALTITUDE("Высота", "м", 0, Palette.Altitude, { it.altBaro }),
    BATTERY("Заряд", "%", 0, Palette.Battery, { it.battPercent.toDouble() }),
}

@Composable
fun ChartsScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val track by vm.repo.track.collectAsStateWithLifecycle()
    val sun by vm.sun.collectAsStateWithLifecycle()
    val start by vm.repo.hikeStartMs.collectAsStateWithLifecycle()
    if (track.size < 2) return Waiting(contentPadding, "Трек ещё не загружен")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        ChartsBody(track, start, sun, "Поход сегодня")
        Spacer(Modifier.height(8.dp))
    }
}

/** Сводка заката + графики всех показателей. Общие для вкладки «Графики» и экрана похода. */
@Composable
fun ChartsBody(track: List<TrackPoint>, start: Long?, sun: SunSchedule?, summaryTitle: String) {
    var selected by rememberSaveable { mutableStateOf(Metric.TEMP) }
    SunsetSummary(track, start, sun, summaryTitle)

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Metric.entries.forEach { m ->
            TermButton(m.title, m.color, selected = m == selected) { selected = m }
        }
    }

    BigChart(selected, track, sun)

    SectionLabel("Все показатели", Modifier.padding(top = 4.dp))
    Metric.entries.filter { it != selected }.forEach { m ->
        SmallChart(m, track, sun) { selected = m }
    }
}

/** Ряд для графика без пустых значений (датчик не ответил). */
private fun seriesOf(track: List<TrackPoint>, f: (TrackPoint) -> Double) =
    track.mapNotNull { p -> f(p).takeIf { !it.isNaN() }?.let { p.tsUtcMs to it } }

/**
 * Сводка «что происходит после заката» — ради этого проект и затевался.
 * На каждую ночь похода: температура на закате, минимум до восхода и когда он был.
 */
@Composable
private fun SunsetSummary(track: List<TrackPoint>, start: Long?, sun: SunSchedule?, title: String) {
    val last = track.last()
    val now = last.tsUtcMs
    Panel(accent = Palette.Sunset, title = title) {
        if (start != null) KeyValue("Старт", "${Fmt.time(start)} | в пути ${Fmt.duration(now - start)}")
        if (sun == null) {
            Text("Закат посчитается, когда будет позиция", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            return@Panel
        }
        val t0 = track.first().tsUtcMs
        sun.of(SunSchedule.Kind.SUNSET).filter { it.timeMs in t0..now }.forEach { ss ->
            val atSunset = track.minBy { abs(it.tsUtcMs - ss.timeMs) }.tempCorr
            val rise = sun.next(SunSchedule.Kind.SUNRISE, ss.timeMs)
            val night = track.filter { it.tsUtcMs >= ss.timeMs && (rise == null || it.tsUtcMs <= rise.timeMs) && !it.tempCorr.isNaN() }
            val min = night.minByOrNull { it.tempCorr }
            Spacer(Modifier.height(6.dp))
            Text(
                "Закат ${Fmt.dayMonth(ss.timeMs)}, ${Fmt.time(ss.timeMs)}",
                style = MaterialTheme.typography.labelLarge, color = Palette.SunsetSun,
            )
            KeyValue("На закате", "${Fmt.num(atSunset)}°")
            if (min != null) {
                KeyValue(
                    "Минимум ночи",
                    "${Fmt.num(min.tempCorr)}° в ${Fmt.time(min.tsUtcMs)} (${Fmt.signed(min.tempCorr - atSunset)}°)",
                    Palette.Cold,
                )
            }
            if (rise != null && rise.timeMs <= now) KeyValue("Восход", Fmt.time(rise.timeMs), Palette.Sunrise)
        }
        Spacer(Modifier.height(6.dp))
        val nextSet = sun.next(SunSchedule.Kind.SUNSET, now)
        val nextRise = sun.next(SunSchedule.Kind.SUNRISE, now)
        when {
            nextSet != null && (nextRise == null || nextSet.timeMs < nextRise.timeMs) ->
                KeyValue("До заката", "${Fmt.duration(nextSet.timeMs - now)} | в ${Fmt.time(nextSet.timeMs)}", Palette.SunsetSun)
            nextRise != null -> KeyValue("До восхода", "${Fmt.duration(nextRise.timeMs - now)} | в ${Fmt.time(nextRise.timeMs)}", Palette.Sunrise)
        }
    }
}

@Composable
private fun BigChart(m: Metric, track: List<TrackPoint>, sun: SunSchedule?) {
    val values = track.map { m.value(it) }.filter { !it.isNaN() }
    if (values.isEmpty()) return
    val series = buildList {
        add(ChartSeries(seriesOf(track, m.value), m.color, if (m == Metric.TEMP) "воздух" else m.title.lowercase()))
        if (m == Metric.TEMP) {
            add(ChartSeries(seriesOf(track) { it.tempRaw }, Palette.TextDim, "датчик", dashed = true, fill = false))
        }
    }
    Panel(accent = m.color, title = m.title, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp)) {
            Text(Fmt.num(values.last(), m.digits), fontFamily = Fonts.Pixel, fontSize = 52.sp, lineHeight = 48.sp, color = m.color)
            Spacer(Modifier.width(4.dp))
            Text(m.unit, color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            // Мин и макс — по строке, с единицами, без переноса
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("макс ${Fmt.num(values.max(), m.digits)} ${m.unit}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1, softWrap = false)
                Text("мин ${Fmt.num(values.min(), m.digits)} ${m.unit}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        LineChart(
            series = series,
            modifier = Modifier.fillMaxWidth().height(260.dp),
            unit = m.unit, digits = m.digits,
            sun = sun,
        )
        if (m == Metric.TEMP) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(start = 4.dp)) {
                Text("━ воздух (с поправкой)", color = m.color, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Text("┅ датчик в корпусе", color = Palette.TextDim, fontSize = 11.sp)
            }
        }
        if (m == Metric.ALTITUDE) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Высота по барометру относительная: при смене погоды «плывёт» на 8–25 м.",
                color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun SmallChart(m: Metric, track: List<TrackPoint>, sun: SunSchedule?, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)).clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(110.dp)) {
                Text(m.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.num(m.value(track.last()), m.digits), fontFamily = Fonts.Pixel, fontSize = 34.sp, lineHeight = 32.sp, color = m.color)
                    Spacer(Modifier.width(3.dp))
                    Text(m.unit, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            LineChart(
                series = listOf(ChartSeries(seriesOf(track, m.value), m.color, m.title)),
                modifier = Modifier.weight(1f).height(48.dp),
                sun = sun, showAxes = false, interactive = false,
            )
        }
    }
}
