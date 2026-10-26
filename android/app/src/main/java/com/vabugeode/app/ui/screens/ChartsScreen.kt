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
    val sunset = sun?.sunsetUtcMs
    val dusk = sun?.civilDuskUtcMs

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        ChartsBody(track, start, sunset, dusk, "Поход сегодня")
        Spacer(Modifier.height(8.dp))
    }
}

/** Сводка заката + графики всех показателей. Общие для вкладки «Графики» и экрана похода. */
@Composable
fun ChartsBody(track: List<TrackPoint>, start: Long?, sunset: Long?, dusk: Long?, summaryTitle: String) {
    var selected by rememberSaveable { mutableStateOf(Metric.TEMP) }
    SunsetSummary(track, start, sunset, dusk, summaryTitle)

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Metric.entries.forEach { m ->
            TermButton(m.title, m.color, selected = m == selected) { selected = m }
        }
    }

    BigChart(selected, track, sunset, dusk)

    SectionLabel("Все показатели", Modifier.padding(top = 4.dp))
    Metric.entries.filter { it != selected }.forEach { m ->
        SmallChart(m, track, sunset, dusk) { selected = m }
    }
}

/** Ряд для графика без пустых значений (датчик не ответил). */
private fun seriesOf(track: List<TrackPoint>, f: (TrackPoint) -> Double) =
    track.mapNotNull { p -> f(p).takeIf { !it.isNaN() }?.let { p.tsUtcMs to it } }

/** Сводка «что произошло на закате» — ради этого проект и затевался. */
@Composable
private fun SunsetSummary(track: List<TrackPoint>, start: Long?, sunset: Long?, dusk: Long?, title: String) {
    val last = track.last()
    Panel(accent = Palette.Sunset, title = title) {
        if (start != null) KeyValue("Старт", "${Fmt.time(start)} · в пути ${Fmt.duration(last.tsUtcMs - start)}")
        if (sunset != null) KeyValue("Закат", Fmt.time(sunset), Palette.Sunset)
        if (dusk != null) KeyValue("Конец сумерек", Fmt.time(dusk))
        if (sunset != null && last.tsUtcMs > sunset) {
            val atSunset = track.minBy { abs(it.tsUtcMs - sunset) }.tempCorr
            val after = track.filter { it.tsUtcMs >= sunset }
            val minAfter = after.minOf { it.tempCorr }
            val hours = (last.tsUtcMs - sunset) / 3_600_000.0
            KeyValue("На закате", "${Fmt.num(atSunset)}°")
            KeyValue("Сейчас", "${Fmt.num(last.tempCorr)}°  (${Fmt.signed(last.tempCorr - atSunset)}°)", Palette.Cold)
            KeyValue("Минимум после заката", "${Fmt.num(minAfter)}°", Palette.Cold)
            if (hours > 0.3) {
                KeyValue("Скорость остывания", "${Fmt.num((last.tempCorr - atSunset) / hours)}° в час")
            }
        } else if (sunset != null) {
            KeyValue("До заката", Fmt.duration(sunset - last.tsUtcMs), Palette.Sunset)
        }
    }
}

@Composable
private fun BigChart(m: Metric, track: List<TrackPoint>, sunset: Long?, dusk: Long?) {
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
            Column(horizontalAlignment = Alignment.End) {
                Text("мин ${Fmt.num(values.min(), m.digits)}  ·  макс ${Fmt.num(values.max(), m.digits)}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Text("ведите пальцем по графику", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
        }
        Spacer(Modifier.height(8.dp))
        LineChart(
            series = series,
            modifier = Modifier.fillMaxWidth().height(260.dp),
            unit = m.unit, digits = m.digits,
            sunsetMs = sunset, duskMs = dusk,
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
private fun SmallChart(m: Metric, track: List<TrackPoint>, sunset: Long?, dusk: Long?, onClick: () -> Unit) {
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
                sunsetMs = sunset, duskMs = dusk, showAxes = false, interactive = false,
            )
        }
    }
}
