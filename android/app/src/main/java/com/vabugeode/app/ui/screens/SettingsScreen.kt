package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.Source
import com.vabugeode.app.data.blockingRadiusFromDiameterKm
import com.vabugeode.app.BuildConfig
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.KeyValue
import com.vabugeode.app.ui.components.Logo
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val blacklist by vm.repo.blacklist.collectAsStateWithLifecycle()
    var editIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var diameter by rememberSaveable { mutableStateOf("") }
    editIndex?.let { index ->
        val point = blacklist.getOrNull(index)
        if (point != null) {
            val radius = blockingRadiusFromDiameterKm(diameter)
            AlertDialog(
                onDismissRequest = { editIndex = null },
                title = { Text("Диаметр блокировки") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(point.note)
                        OutlinedTextField(value = diameter, onValueChange = { diameter = it },
                            label = { Text("Диаметр, км") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = radius == null,
                            supportingText = { Text("От 0,2 до 100 км. Радиус — половина диаметра.") })
                        Text("Большая область исключает из GPS-трека и настоящие координаты внутри неё.")
                    }
                },
                confirmButton = { TextButton(enabled = radius != null, onClick = {
                    radius?.let { vm.setBlacklistRadius(index, it) }
                    editIndex = null
                }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { editIndex = null }) { Text("Отмена") } },
            )
        }
    }
    var guide by rememberSaveable { mutableStateOf(false) }
    if (guide) {
        XosGuide(contentPadding) { guide = false }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        val source by vm.link.source.collectAsStateWithLifecycle()
        Panel {
            SectionLabel("Источник данных")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Source.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = source == s,
                        onClick = { vm.link.setSource(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size),
                    ) { Text(s.title) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (source == Source.GADGET) "Настоящий гаджет по Bluetooth."
                else "Виртуальный гаджет внутри приложения — для проверки без железа.",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            )
        }
        // Только то, что относится к выбранному источнику
        if (source == Source.GADGET) {
            LinkPanel(vm)
            WatchPanel(vm) { guide = true }
        } else {
            EmulatorPanel(vm)
        }
        OfflineMapsPanel()

        Panel {
            SectionLabel("Чёрный список подмены")
            Spacer(Modifier.height(4.dp))
            Text(
                "Области вокруг ложных точек GPS. Позиции внутри круга помечаются как подозрительные. Диаметр — полная ширина круга.",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            blacklist.forEachIndexed { i, b ->
                if (i > 0) HorizontalDivider(color = Palette.Outline)
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(b.note, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        Text(Fmt.coord(b.lat, b.lon), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.TextDim)
                        Spacer(Modifier.weight(1f))
                        Text("диаметр ${Fmt.num(b.radiusM / 500.0, 3).trimEnd('0').trimEnd(',')} км", fontSize = 12.sp, color = Palette.TextDim)
                    }
                    OutlinedButton(onClick = {
                        diameter = Fmt.num(b.radiusM / 500.0, 3).trimEnd('0').trimEnd(',')
                        editIndex = i
                    }) { Text("Изменить диаметр") }
                }
            }
            Text("Сохраняется на телефоне. Передаётся подключённому гаджету или при следующем подключении.", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }

        if (source == Source.GADGET) CompassPanel(vm)

        if (source == Source.GADGET) Panel {
            SectionLabel("Запись")
            Spacer(Modifier.height(6.dp))
            KeyValue("Интервал в движении", "10 с")
            KeyValue("Интервал на стоянке", "60 с")
        }

        Panel {
            Logo(size = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text("Карта: © OpenStreetMap, Protomaps, OpenFreeMap", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text("Шрифты: VT323, IBM Plex Mono (OFL)", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EmulatorPanel(vm: AppViewModel) {
    val speed by vm.demo.speed.collectAsStateWithLifecycle()
    Panel(accent = Palette.Geo) {
        SectionLabel("Эмулятор", color = Palette.Geo)
        Spacer(Modifier.height(6.dp))
        Text(
            "Шлёт байты по тому же протоколу, что и прошивка, а приложение их разбирает. Сценарий: поход под Петербургом, закат, стоянки, потеря сигнала в здании, подмена GPS.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
        )
        Spacer(Modifier.height(12.dp))
        Text("Скорость времени", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Spacer(Modifier.height(6.dp))
        val options = listOf(1, 10, 30, 120)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = speed == v,
                    onClick = { vm.demo.speed.value = v },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text("${v}×") }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { vm.demo.restart() }, modifier = Modifier.fillMaxWidth()) {
            Text("Начать демо-поход заново")
        }
    }
}
