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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.Source
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
    val speed by vm.demo.speed.collectAsStateWithLifecycle()
    val blacklist by vm.repo.blacklist.collectAsStateWithLifecycle()
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
                if (source == Source.GADGET) "Настоящий гаджет по Bluetooth. Выбор и подключение — на вкладке «Трекер»."
                else "Виртуальный гаджет внутри приложения — для проверки без железа.",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            )
        }
        WatchPanel(vm) { guide = true }
        Panel(accent = Palette.Geo) {
            SectionLabel("Виртуальное устройство", color = Palette.Geo)
            Spacer(Modifier.height(6.dp))
            Text(
                "Платы ещё нет — её заменяет эмулятор. Он шлёт байты по тому же протоколу, что и прошивка, а приложение их разбирает. Сценарий: поход под Петербургом, закат, стоянки, потеря сигнала в здании, подмена GPS.",
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

        Panel {
            SectionLabel("Чёрный список подмены")
            Spacer(Modifier.height(4.dp))
            Text(
                "Точки, куда «уводит» поддельный сигнал. Если GPS показывает место в радиусе — точка помечается как подозрительная.",
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
                        Text("радиус ${b.radiusM / 1000} км", fontSize = 12.sp, color = Palette.TextDim)
                    }
                }
            }
            Text("Редактирование — после подключения устройства", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }

        Panel {
            SectionLabel("Запись и калибровки")
            Spacer(Modifier.height(6.dp))
            KeyValue("Интервал в движении", "10 с")
            KeyValue("Интервал на стоянке", "60 с")
            KeyValue("Длина шага", "0,75 м (уточняется по GPS)")
            KeyValue("Высота", "калибровка по известной точке")
            KeyValue("Компас", "калибровка вращением")
            Text("Станет доступно после подключения устройства", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }

        Panel {
            Logo(size = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text("Версия 0.5.0 · на железе не проверено", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text("Карта: © OpenStreetMap, OpenFreeMap", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text("Шрифты: VT323, IBM Plex Mono (OFL)", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        }
        Spacer(Modifier.height(8.dp))
    }
}
