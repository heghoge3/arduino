package com.vabugeode.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.HikeSummary
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.BigValue
import com.vabugeode.app.ui.components.KeyValue
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.components.Waiting
import com.vabugeode.app.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))
private fun dateOf(ms: Long) = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun HikeSummary.id() = "$device|$hike"
private fun HikeSummary.title() = name ?: "Поход ${dateOf(startMs).substringBefore(',')}"

/** Вкладка «Походы»: список сохранённых в телефоне походов и экран одного похода. */
@Composable
fun HikesScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val hikes by vm.hikes.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val list = hikes ?: return Waiting(contentPadding, "Читаем базу походов")

    val open = openId?.let { id -> list.firstOrNull { it.id() == id } }
    if (open != null) {
        BackHandler { openId = null }
        HikeDetail(vm, open, contentPadding) { openId = null }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionLabel("Сохранено в телефоне · ${list.size}") }
        if (list.isEmpty()) item {
            Text(
                "Походов пока нет. Они появятся после первой связи с гаджетом.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
            )
        }
        items(list, key = { it.id() }) { h -> HikeCard(h, vm.isCurrent(h)) { openId = h.id() } }
    }
}

@Composable
private fun HikeCard(h: HikeSummary, current: Boolean, onClick: () -> Unit) {
    val accent = if (h.spoofPoints > 0) Palette.Warn else Palette.Geo
    Panel(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        accent = if (current) Palette.Good else null,
        title = dateOf(h.startMs),
        titleRight = when {
            current -> "идёт"
            h.demo -> "эмулятор"
            else -> null
        },
        padding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
    ) {
        if (h.name != null) Text(h.name, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(Fmt.num(h.km), "км", Palette.Geo, 38.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${Fmt.time(h.startMs)}–${Fmt.time(h.endMs)} · ${Fmt.duration(h.endMs - h.startMs)}",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Text,
                )
                Text(
                    "t ${Fmt.num(h.tempMin)}…${Fmt.num(h.tempMax)}° · меток ${h.marks}",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
                )
            }
        }
        if (h.spoofPoints > 0) {
            Text("⚠ подозрение на подмену: ${h.spoofPoints} точек", style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

@Composable
private fun HikeDetail(vm: AppViewModel, h: HikeSummary, contentPadding: PaddingValues, onClose: () -> Unit) {
    val context = LocalContext.current
    // Перечитываем, когда в текущий поход дописались точки
    val data by produceState<Pair<List<TrackPoint>, List<Mark>>?>(null, h.device, h.hike, h.points) {
        value = vm.loadHike(h.device, h.hike)
    }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val current = vm.isCurrent(h)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermButton("← Походы", Palette.TextDim, onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text(h.title(), style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, modifier = Modifier.weight(1f))
        }

        Panel(accent = Palette.Geo, title = dateOf(h.startMs), titleRight = if (h.demo) "эмулятор" else null) {
            Row(verticalAlignment = Alignment.Bottom) {
                BigValue(Fmt.num(h.km), "км", Palette.Geo, 52.sp)
                Spacer(Modifier.width(16.dp))
                val min = (h.endMs - h.startMs) / 60_000
                BigValue("${min / 60}:${"%02d".format(min % 60)}", "ч", Palette.Text, 40.sp)
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("Время", "${Fmt.time(h.startMs)} – ${Fmt.time(h.endMs)}")
            KeyValue("Температура", "${Fmt.num(h.tempMin)}° … ${Fmt.num(h.tempMax)}°", Palette.Temp)
            KeyValue("Метки", "${h.marks}")
            KeyValue("Точек записано", "${h.points}")
            if (h.spoofPoints > 0) KeyValue("Подозрение на подмену", "${h.spoofPoints} точек", Palette.Warn)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton("✎ Название", Palette.Geo) { rename = true }
            TermButton("↗ CapCut", Palette.Sunset) { data?.let { shareMarks(context, it.second, h.startMs) } }
            if (!current) TermButton("✕ Удалить", Palette.Bad) { delete = true }
        }
        if (current) {
            Text("Поход идёт сейчас — удалить можно после завершения.", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }

        val d = data
        if (d == null) {
            Text("Загрузка…", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        } else {
            val (track, marks) = d
            Panel(accent = Palette.Outline, title = "Трек", padding = PaddingValues(start = 0.dp, end = 0.dp, top = 6.dp, bottom = 8.dp)) {
                TrackMap(track, marks, Modifier.fillMaxWidth().height(320.dp))
                Row(Modifier.padding(horizontal = 12.dp)) { MapLegend(track.count { it.spoof }) }
            }
            if (track.size >= 2) {
                val sun = remember(h.hike, track.size > 0) {
                    track.firstOrNull { !it.lat.isNaN() }?.let { p ->
                        SunCalc.times(Instant.ofEpochMilli(h.startMs).atZone(ZoneId.systemDefault()).toLocalDate(), p.lat, p.lon)
                    }
                }
                ChartsBody(track, h.startMs, sun?.sunsetUtcMs, sun?.civilDuskUtcMs, "Закат и температура")
            }
            SectionLabel("Метки · ${marks.size}", Modifier.padding(top = 4.dp))
            Column { marks.forEach { MarkLine(it, h.startMs) } }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (rename) {
        var text by remember { mutableStateOf(h.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Название похода") },
            text = {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Например, Урбекс Кронштадт") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.renameHike(h, text); rename = false }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена") } },
        )
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Удалить поход?") },
            text = { Text("«${h.title()}» удалится только из телефона. Файл на SD-карте гаджета останется.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteHike(h); delete = false; onClose() }) { Text("Удалить", color = Palette.Bad) }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } },
        )
    }
}
