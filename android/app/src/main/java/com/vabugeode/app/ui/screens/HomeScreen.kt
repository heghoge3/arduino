package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.DeviceStatus
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.GpsFix
import com.vabugeode.app.model.PowerMode
import com.vabugeode.app.model.SpoofReason
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.ChartSeries
import com.vabugeode.app.ui.components.IconBadge
import com.vabugeode.app.ui.components.LineChart
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.Pill
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.components.SnrBars
import com.vabugeode.app.ui.components.StatTile
import com.vabugeode.app.ui.theme.Palette
import kotlin.math.abs

@Composable
fun HomeScreen(
    vm: AppViewModel,
    contentPadding: PaddingValues,
    onMark: () -> Unit,
    onClap: () -> Unit,
) {
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val track by vm.repo.track.collectAsStateWithLifecycle()
    val sun by vm.sun.collectAsStateWithLifecycle()
    val start by vm.repo.hikeStartMs.collectAsStateWithLifecycle()
    val st = status ?: return NotConnected(vm, contentPadding)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        OfflineBanner(vm)
        StatusRow(st, start)
        TempHero(st, track, sun?.sunsetUtcMs, sun?.civilDuskUtcMs)
        GpsCard(st)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val trend = st.env.pressureTrend3h
            StatTile(
                Icons.Filled.Speed, "Давление", Fmt.num(st.env.pressureHpa, 0), "гПа", Palette.Pressure,
                Modifier.weight(1f),
                sub = "${trendArrow(trend)} ${Fmt.signed(trend)} за 3 ч" + if (trend <= -3) " · к непогоде" else "",
                subColor = if (trend <= -3) Palette.Warn else Palette.TextDim,
            )
            StatTile(
                Icons.Filled.WaterDrop, "Влажность", Fmt.num(st.env.humidity, 0), "%", Palette.Humidity,
                Modifier.weight(1f),
                sub = if (st.env.humidity > 90) "Возможен туман/роса" else "Относительная",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                Icons.Filled.Landscape, "Высота", Fmt.num(st.env.altBaro, 0), "м", Palette.Altitude,
                Modifier.weight(1f),
                sub = "барометр" + (st.gps.altGps?.let { " · GPS ${Fmt.num(it, 0)} м" } ?: ""),
            )
            val pw = st.power
            StatTile(
                Icons.Filled.BatteryChargingFull, "Заряд", "${pw.percent}", "%",
                if (pw.percent < 20) Palette.Bad else Palette.Battery,
                Modifier.weight(1f),
                sub = "${Fmt.num(pw.voltage, 2)} В · ${Fmt.num(pw.currentMa, 0)} мА\n~${Fmt.num(pw.hoursLeft, 0)} ч осталось",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                Icons.AutoMirrored.Filled.DirectionsWalk, "Пройдено", Fmt.num(st.distanceKm, 1), "км", Palette.Geo,
                Modifier.weight(1f),
                sub = "${st.steps} шагов",
            )
            StatTile(
                Icons.Filled.Speed, "Скорость", Fmt.num(if (st.mode == PowerMode.MOVING) st.gps.speedKmh else 0.0, 1), "км/ч", Palette.Cold,
                Modifier.weight(1f),
                sub = "курс ${Fmt.compass(st.headingDeg)} ${st.headingDeg.toInt()}°",
            )
        }

        Actions(st, onMark, onClap, onToggle = { vm.repo.setRecording(!st.recording) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusRow(st: DeviceStatus, start: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.recording) Pill("Запись", Palette.Bad) else Pill("Пауза", Palette.TextDim)
        Pill(st.mode.title, if (st.mode == PowerMode.MOVING) Palette.Good else Palette.Cold)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.time(st.timeUtcMs), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            if (start != null) {
                Text("в пути ${Fmt.duration(st.timeUtcMs - start)}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            }
        }
    }
}

/** Главное: температура и закат — исходная идея проекта. */
@Composable
private fun TempHero(st: DeviceStatus, track: List<TrackPoint>, sunset: Long?, dusk: Long?) {
    val now = st.timeUtcMs
    val temp = st.env.tempCorr
    val tempColor = if (temp < 2) Palette.Cold else Palette.Temp
    Panel(accent = tempColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Thermostat, tempColor)
            Spacer(Modifier.width(10.dp))
            SectionLabel("Температура воздуха")
            Spacer(Modifier.weight(1f))
            if (sunset != null) {
                Icon(Icons.Filled.WbTwilight, null, tint = Palette.Sunset, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("закат ${Fmt.time(sunset)}", style = MaterialTheme.typography.labelMedium, color = Palette.Sunset)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Fmt.signed(temp).removePrefix("+") + "°",
                style = MaterialTheme.typography.displayLarge, color = Palette.Text,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(bottom = 12.dp)) {
                val sinceSunset = sunset?.takeIf { now > it }?.let { s ->
                    track.minByOrNull { abs(it.tsUtcMs - s) }?.let { temp - it.tempCorr }
                }
                when {
                    sunset == null -> {}
                    now < sunset -> Text("до заката ${Fmt.duration(sunset - now)}", color = Palette.Sunset, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(
                        "${Fmt.signed(sinceSunset ?: 0.0)}° с заката",
                        color = if ((sinceSunset ?: 0.0) < 0) Palette.Cold else Palette.Temp,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    "в корпусе ${Fmt.num(st.env.tempRaw)}° · поправка ${Fmt.signed(st.env.tempCorr - st.env.tempRaw)}°",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
                )
            }
        }
        // Последние 4 часа
        val recent = track.filter { it.tsUtcMs > now - 4 * 3_600_000L }.map { it.tsUtcMs to it.tempCorr }
        LineChart(
            series = listOf(ChartSeries(recent, tempColor, "t")),
            modifier = Modifier.fillMaxWidth().height(70.dp),
            sunsetMs = sunset, duskMs = dusk, showAxes = false, interactive = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpsCard(st: DeviceStatus) {
    val g = st.gps
    val (color, icon, title, text) = when {
        g.spoof -> Quad(
            Palette.Bad, Icons.Filled.Warning, "Подозрение на подмену GPS",
            "Приёмник уверенно показывает ${g.fix.title}, но координаты ложные. Эти точки не идут в трек — позиция считается по шагам.",
        )
        g.fix == GpsFix.OFF -> Quad(
            Palette.Cold, Icons.Filled.GpsOff, "GPS выключен",
            "Стоянка — экономим заряд. Включится, когда начнёте двигаться.",
        )
        g.fix == GpsFix.SEARCHING -> Quad(
            Palette.Warn, Icons.Filled.GpsOff, "Ищет спутники",
            "Видно ${g.satsInView}, сигнал слабый (в здании?). Пока позиция по шагам — приблизительно.",
        )
        else -> Quad(
            Palette.Good, Icons.Filled.GpsFixed, "GPS: сигнал хороший",
            "${g.fix.title} · ${g.satsUsed} из ${g.satsInView} спутников · HDOP ${Fmt.num(g.hdop)} · ±${Fmt.num(g.hdop * 3, 0)} м",
        )
    }
    Panel(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                Text(st.positionSource.let { "Позиция: ${it.title}" }, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        if (g.spoof) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SpoofReason.fromMask(g.spoofMask).forEach { Pill(it.title, Palette.Bad, dot = false) }
            }
        }
        if (g.satellites.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SnrBars(g.satellites, Modifier.fillMaxWidth().height(36.dp))
            Spacer(Modifier.height(4.dp))
            Row {
                Text("■ GPS", color = Palette.Geo, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                Text("■ BeiDou", color = Palette.Cold, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("сила сигнала (SNR)", color = Palette.TextFaint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Actions(st: DeviceStatus, onMark: () -> Unit, onClap: () -> Unit, onToggle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(Icons.Filled.BookmarkAdd, "Метка", Palette.Geo, Modifier.weight(1f), onMark)
        ActionButton(Icons.Filled.Movie, "Хлопушка", Palette.Sunset, Modifier.weight(1f), onClap)
        ActionButton(
            if (st.recording) Icons.Filled.Pause else Icons.Filled.FiberManualRecord,
            if (st.recording) "Пауза" else "Запись",
            if (st.recording) Palette.TextDim else Palette.Bad,
            Modifier.weight(1f), onToggle,
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    // Кнопка терминала: рамка в цвет действия, подпись «[ МЕТКА ]»
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.08f), contentColor = color),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text("[ ${label.uppercase()} ]", fontWeight = FontWeight.Medium, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private data class Quad(val color: Color, val icon: ImageVector, val title: String, val text: String)

private fun trendArrow(d: Double) = when {
    d <= -1 -> "↘"
    d >= 1 -> "↗"
    else -> "→"
}

/** Нет данных сеанса: показываем, что знаем из маячка, и как подключиться. */
@Composable
private fun NotConnected(vm: AppViewModel, contentPadding: PaddingValues) {
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val name by vm.link.selectedName.collectAsStateWithLifecycle()
    val beacon by vm.link.lastBeacon.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel {
            SectionLabel(name ?: "Гаджет не выбран")
            Spacer(Modifier.height(8.dp))
            val b = beacon
            if (b != null) {
                Text(beaconSummary(b.beacon), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(
                    "маячок ${Fmt.duration(System.currentTimeMillis() - b.seenAtMs)} назад",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
                )
            } else {
                Text(
                    if (name == null) "Выберите гаджет на вкладке «Трекер»."
                    else "Гаджет пока не слышно. Он выходит в эфир раз в 30–60 с; чтобы сразу — зажмите B на 1,5 с.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                )
            }
            Spacer(Modifier.height(10.dp))
            when (link) {
                LinkState.CONNECTING -> Text("Подключение…", color = Palette.Warn)
                LinkState.CONNECTED -> Text("Подключено, загружаем данные…", color = Palette.Good)
                else -> if (name != null) {
                    Button(onClick = { vm.link.connectNow() }, shape = RoundedCornerShape(2.dp)) { Text("Подключить") }
                }
            }
        }
    }
}

/** Связи нет, но данные из базы есть — честно показать, насколько они старые. */
@Composable
private fun OfflineBanner(vm: AppViewModel) {
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val seen by vm.repo.lastSeenMs.collectAsStateWithLifecycle()
    if (link == LinkState.CONNECTED) return
    val s = seen ?: return
    Panel(accent = Palette.Warn, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (link == LinkState.CONNECTING) "Подключение…" else "Нет связи с гаджетом",
                    style = MaterialTheme.typography.titleMedium, color = Palette.Warn,
                )
                Text(
                    "Данные на ${Fmt.time(s)} (${Fmt.duration(System.currentTimeMillis() - s)} назад)",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
                )
            }
            if (link == LinkState.DISCONNECTED) {
                Button(onClick = { vm.link.connectNow() }, shape = RoundedCornerShape(2.dp)) { Text("Обновить") }
            }
        }
    }
}
