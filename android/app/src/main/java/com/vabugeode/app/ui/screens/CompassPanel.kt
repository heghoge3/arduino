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
    val offset by vm.repo.temperatureOffset100.collectAsStateWithLifecycle()
    val busy by vm.repo.settingBusy.collectAsStateWithLifecycle()
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val anchorMessage by vm.pdrAnchorMessage.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var stride by rememberSaveable(settings) { mutableStateOf((settings.strideCm / 100.0).toString()) }
    val strideM = stride.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.30..1.50 }
    val connected = link==LinkState.CONNECTED && vm.repo.supportsEconomy()
    var temperature by rememberSaveable(offset) { mutableStateOf(((offset ?: 0)/100.0).toString()) }
    val correction=temperature.replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it in -20.0..20.0 }
    Panel(accent = Palette.Pdr) {
        SectionLabel("GPS и шаги", color = Palette.Pdr)
        Text("Экономный режим: гироскоп и компас выключены. Шаги считаются при любом положении корпуса, обучение не требуется.", color=Palette.Text)
        Text("Без GPS сохраняются шаги и датчики. Когда достоверная позиция вернётся, привязки соединятся пунктиром. Пунктир не показывает повороты внутри здания; расстояние оценивается по шагам.", color=Palette.TextDim,style=MaterialTheme.typography.labelMedium)
        if (nav!=19) Text("Обновите гаджет до прошивки 0.1.16",color=Palette.Pdr)
        Text("Без GPS старт можно задать на вкладке «Карта» или по позиции телефона.",color=Palette.TextDim,style=MaterialTheme.typography.labelMedium)
        OutlinedButton(enabled=connected&&!busy,onClick={vm.seedPdrFromPhone()}) { Text("Начало от телефона") }
        anchorMessage?.let { Text(it,color=Palette.TextDim,style=MaterialTheme.typography.labelMedium) }
        TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки") }
        if (advanced) {
            OutlinedTextField(value=stride,onValueChange={stride=it},label={Text("Длина одного шага, м (0,30–1,50)")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,isError=strideM==null,modifier=Modifier.fillMaxWidth())
            OutlinedButton(enabled=strideM!=null,onClick={vm.setPdrSettings((strideM!!*100).roundToInt(),settings.headingOffset10,settings.inverted)}) { Text("Сохранить длину шага") }
            Text("Сохраняется на телефоне и передаётся при подключении. Километраж по шагам приблизительный.",color=Palette.TextDim,style=MaterialTheme.typography.labelMedium)
        }
    }
    Panel(accent=Palette.Temp) {
        SectionLabel("Поправка температуры")
        Text("Прибавляется к показанию датчика. Например, если гаджет завышает на 3 °C, введите −3. Исходное показание сохраняется отдельно.",color=Palette.TextDim,style=MaterialTheme.typography.labelMedium)
        OutlinedTextField(value=temperature,onValueChange={temperature=it},label={Text("Поправка, °C (−20…+20)")},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,isError=correction==null,modifier=Modifier.fillMaxWidth())
        OutlinedButton(enabled=connected&&!busy&&correction!=null,onClick={vm.setTemperatureOffset((correction!!*10).roundToInt()*10)}) { Text("Сохранить на гаджете") }
        Text("Действует на новые измерения и без телефона. Старый график не пересчитывается. Настраивайте по термометру рядом после стабилизации показаний.",color=Palette.TextDim,style=MaterialTheme.typography.labelMedium)
        if(!connected) Text("Для изменения подключите гаджет с прошивкой 0.1.16",color=Palette.TextDim,style=MaterialTheme.typography.labelSmall)
    }
}
