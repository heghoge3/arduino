package com.vabugeode.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.Source
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.PowerMode
import com.vabugeode.app.protocol.BeaconMsg
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.transport.BleTransport
import com.vabugeode.app.transport.FoundGadget
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.BluetoothGate
import com.vabugeode.app.ui.components.IconBadge
import com.vabugeode.app.ui.components.KeyValue
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette

/** Связь: источник, выбранный гаджет, подключение, найденные маячки, счётчики обмена. */
@Composable
internal fun LinkPanel(vm: AppViewModel) {
    val source by vm.link.source.collectAsStateWithLifecycle()
    if (source == Source.GADGET) {
        BluetoothGate(onReady = { vm.link.restartScan() }) { LinkContent(vm) }
    } else {
        LinkContent(vm)
    }
}

@Composable
private fun LinkContent(vm: AppViewModel) {
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val stats by vm.repo.stats.collectAsStateWithLifecycle()
    val source by vm.link.source.collectAsStateWithLifecycle()
    val selectedId by vm.link.selectedId.collectAsStateWithLifecycle()
    val selectedName by vm.link.selectedName.collectAsStateWithLifecycle()
    val scanner by vm.link.scanner.collectAsStateWithLifecycle()
    val found by scanner.found.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val scanError by scanner.error.collectAsStateWithLifecycle()
    val color = when (link) {
        LinkState.CONNECTED -> Palette.Good
        LinkState.CONNECTING -> Palette.Warn
        else -> Palette.TextDim
    }

    Panel(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Bluetooth, color, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(selectedName ?: "Гаджет не выбран", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text("${source.title} · ${link.title}", style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (selectedId != null) {
                if (link == LinkState.DISCONNECTED) {
                    Button(onClick = { vm.link.connectNow() }, shape = RoundedCornerShape(14.dp)) { Text("Подключить") }
                } else {
                    OutlinedButton(onClick = { vm.link.disconnectByUser() }, shape = RoundedCornerShape(14.dp)) { Text("Отключить") }
                }
            }
        }
        // Почему не подключилось — от настоящего Bluetooth
        val bleError = (vm.repo.currentTransport as? BleTransport)?.lastError?.collectAsStateWithLifecycle()?.value
        if (bleError != null) {
            Spacer(Modifier.height(6.dp))
            Text(bleError, style = MaterialTheme.typography.labelMedium, color = Palette.Warn)
        }
        Spacer(Modifier.height(10.dp))
        KeyValue("Принято пакетов", "${stats.packetsIn} · ${Fmt.num(stats.bytesIn / 1024.0)} КБ")
        KeyValue("Отправлено команд", "${stats.packetsOut}")
        KeyValue("Потеряно STATUS", "${stats.lostStatus}", if (stats.lostStatus > 0) Palette.Warn else Palette.Text)
        KeyValue("Ошибок разбора", "${stats.errors}", if (stats.errors > 0) Palette.Bad else Palette.Text)
        if (stats.syncing) KeyValue("Синхронизация", "идёт…", Palette.Warn)
    }

    if (source == Source.GADGET) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.AutoMirrored.Filled.BluetoothSearching, if (scanning) Palette.Geo else Palette.TextDim)
                Spacer(Modifier.width(10.dp))
                SectionLabel(if (scanning) "Ищем гаджеты рядом…" else "Поиск остановлен", Modifier.weight(1f))
                TextButton(onClick = { vm.link.restartScan() }) { Text("Обновить") }
            }
            scanError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Palette.Warn, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            if (found.isEmpty()) {
                Text(
                    "Пока никого. Гаджет выходит в эфир раз в 30–60 с. Чтобы найти сразу — зажмите кнопку B " +
                        "на гаджете на 1,5 с: откроется окно на 2 минуты.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                )
            }
            found.values.sortedByDescending { it.seenAtMs }.forEach { g ->
                FoundRow(g, selected = g.id == selectedId, onChoose = { vm.link.choose(g) })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Первое подключение нового телефона: зажмите B на гаджете (окно сопряжения), затем «Выбрать» " +
                    "и подтвердите сопряжение в запросе Android.",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint,
            )
            if (selectedId != null) {
                TextButton(onClick = { vm.link.forget() }) { Text("Забыть выбранный гаджет", color = Palette.TextDim) }
            }
        }
    }
}

@Composable
private fun FoundRow(g: FoundGadget, selected: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.name, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Text(beaconSummary(g.beacon), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text(
                "${g.id}${g.rssi?.let { " · $it дБм" } ?: ""}",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.TextFaint,
            )
        }
        if (selected) Text("выбран", color = Palette.Good, style = MaterialTheme.typography.labelMedium)
        else Button(onClick = onChoose, shape = RoundedCornerShape(12.dp)) { Text("Выбрать") }
    }
}

/** «2,3° · 80 % · GPS 9 сп. · в пути» — из маячка, без подключения */
fun beaconSummary(b: BeaconMsg): String = buildList {
    add(b.tempCorr?.let { "${Fmt.num(it)}°" } ?: "—°")
    add("${b.battPct} %")
    add(
        when {
            b.flags and Proto.B_SPOOF != 0 -> "подмена GPS!"
            b.flags and Proto.B_FIX != 0 -> "GPS ${b.satsUsed} сп."
            else -> "GPS выкл"
        }
    )
    add(PowerMode.entries.getOrNull(b.mode)?.title?.lowercase() ?: "?")
    if (b.flags and Proto.B_WINDOW != 0) add("окно открыто")
}.joinToString(" · ")
