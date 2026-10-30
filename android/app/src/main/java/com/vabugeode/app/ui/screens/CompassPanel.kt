package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette
import kotlin.math.roundToInt

@Composable
fun CompassPanel(vm: AppViewModel) {
    val settings by vm.pdrSettings.collectAsStateWithLifecycle()
    val state by vm.repo.compassState.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val info by vm.repo.info.collectAsStateWithLifecycle()
    val anchorMessage by vm.pdrAnchorMessage.collectAsStateWithLifecycle()
    var stride by rememberSaveable(settings) { mutableStateOf((settings.strideCm / 100.0).toString()) }
    var offset by rememberSaveable(settings) { mutableStateOf((settings.headingOffset10 / 10.0).toString()) }
    var inverted by rememberSaveable(settings) { mutableStateOf(settings.inverted) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val strideM = stride.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.30..1.50 }
    val offsetDeg = offset.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
    val fw = info?.firmware?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    val supported = fw.size == 3 && (fw[0] > 0 || fw[1] > 1 || (fw[1] == 1 && fw[2] >= 14))
    val connected = link == LinkState.CONNECTED && supported
    Panel(accent = Palette.Pdr) {
        SectionLabel("Маршрут по шагам", color = Palette.Pdr)
        Spacer(Modifier.height(6.dp))
        Text("Без GPS — приблизительный пунктир по шагам и компасу. При возврате проверенного GPS приложение поправит участок. Подмена GPS для коррекции не используется.", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Text("Начало — последняя позиция GPS или свежая позиция телефона при подключении. Если её нет, шаги считаются, но маршрута на карте нет. Проверьте разрешение геолокации телефона.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        Text(when (state) {
            13 -> "Калибровка идёт — вращайте корпус"
            0 -> "Компас откалиброван"
            3 -> "Компас нужно откалибровать"
            else -> "Подключите гаджет с прошивкой 0.1.14 или новее"
        }, color = Palette.Pdr)
        val heading = status?.headingDeg
        Text(if (heading != null && heading.isFinite()) "Направление: ${heading.roundToInt()}° (0° — север)" else "Нет достоверного направления", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
        Text("1. Нажмите «Начать». В течение 30–60 секунд плавно поворачивайте собранный корпус по всем трём осям: полный оборот вокруг каждой. Отойдите от металла, магнитов, динамиков и телефона. Шаги на время калибровки не считаются.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Text("2. Положите корпус спокойно и горизонтально (плата компаса тоже горизонтальна), затем нажмите «Сохранить калибровку». При неполном вращении сохранение будет отклонено; продолжайте вращать. Через 3 минуты тест завершится автоматически.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = connected && state != 13, onClick = { vm.repo.calibrateCompass(true) }) { Text("Начать") }
            OutlinedButton(enabled = connected && state == 13, onClick = { vm.repo.calibrateCompass(false); vm.seedPdrFromPhone() }) { Text("Сохранить калибровку") }
        }
        OutlinedButton(enabled = connected && state != 13, onClick = { vm.seedPdrFromPhone() }) { Text("Начало от телефона") }
        anchorMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim) }
        Text("При ходьбе держите корпус примерно горизонтально и одной выбранной стороной вперёд. Повороты корпуса в руке и магнитные помехи искажают путь. При сильном наклоне линия может прерваться; шаги продолжат считаться.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = stride, onValueChange = { stride = it; saved = false }, label = { Text("Длина одного шага, м (0,30–1,50)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = strideM == null, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = offset, onValueChange = { offset = it; saved = false }, label = { Text("Поправка направления, ° (−180…180)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = offsetDeg == null, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(checked = inverted, onCheckedChange = { inverted = it; saved = false })
            Text("Обратное вращение компаса (плата перевёрнута)", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Text)
        }
        Text("Направьте выбранную переднюю сторону на известный север. Поправкой добейтесь 0°. Поверните корпус вправо: число должно увеличиваться. Если уменьшается — включите обратное вращение, сохраните и заново уточните поправку. Поворот крепления и магнитное склонение учитываются этой поправкой.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        OutlinedButton(enabled = strideM != null && offsetDeg != null, onClick = {
            vm.setPdrSettings((strideM!! * 100).roundToInt(), (offsetDeg!! * 10).roundToInt(), inverted)
            saved = true
        }) { Text("Сохранить параметры") }
        if (saved) Text("Параметры сохранены на телефоне; передаются гаджету при подключении.", style = MaterialTheme.typography.labelMedium, color = Palette.Pdr)
    }
}
