package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette

@Composable
internal fun ManualStartForm(initialLat:String,initialLon:String,available:Boolean,
    onCancel:()->Unit,onMap:()->Unit,onConfirm:(Double,Double)->Unit) {
    var latitude by rememberSaveable(initialLat) { mutableStateOf(initialLat) }
    var longitude by rememberSaveable(initialLon) { mutableStateOf(initialLon) }
    val lat=latitude.replace(',','.').toDoubleOrNull()?.takeIf {it.isFinite()&&it in -90.0..90.0}
    val lon=longitude.replace(',','.').toDoubleOrNull()?.takeIf {it.isFinite()&&it in -180.0..180.0}
    Panel(accent=Palette.Pdr) {
        SectionLabel("Начать отсюда без GPS")
        Text("Точка будет отмечена как заданная вручную. Это привязка текущего места; накопленные записи сохраняются.",color=Palette.TextDim)
        OutlinedTextField(latitude,{latitude=it},label={Text("Широта")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),isError=latitude.isNotBlank()&&lat==null)
        OutlinedTextField(longitude,{longitude=it},label={Text("Долгота")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),isError=longitude.isNotBlank()&&lon==null)
        TextButton(onClick=onMap) { Text("Выбрать на карте") }
        Row {
            TextButton(onClick=onCancel) { Text("Отмена") }
            TextButton(enabled=available&&lat!=null&&lon!=null,onClick={onConfirm(lat!!,lon!!)}) { Text("Задать старт") }
        }
        if(!available) Text("Нужно подключение к гаджету, включённая запись и отсутствие достоверного GPS.",color=Palette.TextDim)
    }
}
