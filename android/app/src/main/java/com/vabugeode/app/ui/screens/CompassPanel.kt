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
    val nav by vm.repo.navigationState.collectAsStateWithLifecycle()
    val compass by vm.repo.compassState.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val info by vm.repo.info.collectAsStateWithLifecycle()
    val anchorMessage by vm.pdrAnchorMessage.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var stride by rememberSaveable(settings) { mutableStateOf((settings.strideCm / 100.0).toString()) }
    val strideM = stride.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.30..1.50 }
    val fw = info?.firmware?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    val supported = fw.size == 3 && (fw[0] > 0 || fw[1] > 1 || (fw[1] == 1 && fw[2] >= 15))
    val connected = link == LinkState.CONNECTED && supported
    Panel(accent = Palette.Pdr) {
        SectionLabel("Маршрут по шагам", color = Palette.Pdr)
        Spacer(Modifier.height(6.dp))
        Text("Автоматически. Держать горизонтально или выбирать переднюю сторону не нужно. Шаги считаются при любом положении корпуса.", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Text("Перед входом в здание пройдите немного с нормальным GPS — обычно достаточно 10–20 метров. Трекер сам подстроит направление по ходьбе. Внутри нарисует приблизительный пунктир, после выхода поправит его по GPS, прошедшему проверку подмены.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Text(when {
            !supported -> "Нужна прошивка 0.1.15 или новее"
            nav == 0 -> "Готов к приблизительной записи без GPS"
            nav == 14 -> "Ожидаю прогулку с достоверным GPS"
            nav == 15 -> "Автоподстройка гироскопа: выполнится в короткий момент покоя"
            nav == 16 -> "Ожидаю устойчивое направление ходьбы"
            nav == 17 -> "Долго без GPS — ошибка накапливается"
            else -> "Подключите гаджет для проверки готовности"
        }, color = Palette.Pdr, style = MaterialTheme.typography.bodyMedium)
        Text("Это грубая оценка, а не план помещений. Резкие перекладывания, развороты на месте и необычные движения могут исказить её. Если направление определить не удаётся, линия прервётся; шаги продолжат считаться. После долгой стоянки со сном может снова понадобиться GPS.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки") }
        if (advanced) {
            OutlinedTextField(value = stride, onValueChange = { stride = it }, label = { Text("Длина одного шага, м (0,30–1,50)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = strideM == null, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = strideM != null, onClick = { vm.setPdrSettings((strideM!! * 100).roundToInt(), settings.headingOffset10, settings.inverted) }) { Text("Сохранить длину шага") }
            Text("Позиция телефона может задать начало, но направление автоматического маршрута всё равно настраивается по ходьбе с GPS.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            OutlinedButton(enabled = connected, onClick = { vm.seedPdrFromPhone() }) { Text("Начало от телефона") }
            anchorMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim) }
            HorizontalDivider(color = Palette.Outline)
            Text("Дополнительный магнитный компас", color = Palette.Text)
            Text("Для автоматического маршрута калибровка компаса не нужна. Здесь можно отдельно настроить его показания: нажать «Начать», 30–60 секунд вращать собранный корпус по всем трём осям вдали от металла и магнитов, затем положить платой компаса горизонтально и сохранить. Шаги на время этого теста не считаются.", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Text(when (compass) { 0 -> "Компас откалиброван"; 13 -> "Калибровка идёт"; else -> "Компас не откалиброван" }, color = Palette.TextDim)
            val heading = status?.headingDeg
            if (heading != null && heading.isFinite()) Text("Магнитный компас: ${heading.roundToInt()}°", color = Palette.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = connected && compass != 13, onClick = { vm.repo.calibrateCompass(true) }) { Text("Начать") }
                OutlinedButton(enabled = connected && compass == 13, onClick = { vm.repo.calibrateCompass(false) }) { Text("Сохранить калибровку") }
            }
        }
    }
}
