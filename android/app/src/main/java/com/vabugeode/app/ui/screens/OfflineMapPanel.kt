package com.vabugeode.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.OfflineMap
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Meter
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.Pill
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Настройки → офлайн-карты: схема и спутник. */
@Composable
fun OfflineMapsPanel() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineMap.refresh(context) }
    Panel(accent = Palette.Geo, title = "Офлайн-карты") {
        MapSlot(
            OfflineMap.Kind.SCHEME,
            "Векторный .pmtiles (OpenStreetMap). СПб и Ленобласть ≈ 390 МБ.",
        )
        Spacer(Modifier.height(14.dp))
        MapSlot(
            OfflineMap.Kind.SATELLITE,
            "Растровый .mbtiles или .pmtiles (JPEG/PNG/WebP). Подписи берутся из схемы, если она загружена.",
        )
        Spacer(Modifier.height(10.dp))
        Text("Без файлов карта показывается только с интернетом.", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
    }
}

@Composable
private fun MapSlot(kind: OfflineMap.Kind, hint: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by OfflineMap.state(kind).collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = OfflineMap.import(context, kind, uri).exceptionOrNull()?.message
        }
    }
    val s = state
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kind.title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Palette.Text, modifier = Modifier.weight(1f))
        when (s) {
            is OfflineMap.State.Ready -> Pill("${Fmt.num(s.bytes / 1_048_576.0, 0)} МБ", Palette.Good)
            is OfflineMap.State.Copying -> Pill("${(s.fraction * 100).toInt()} %", Palette.Geo)
            else -> Pill("нет", Palette.TextDim)
        }
    }
    if (s is OfflineMap.State.Copying) {
        Spacer(Modifier.height(6.dp))
        Meter(s.fraction, Palette.Geo)
    } else {
        Text(hint, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
    }
    error?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Bad)
    }
    if (s !is OfflineMap.State.Copying) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton(if (s is OfflineMap.State.Ready) "Заменить" else "Загрузить файл", Palette.Geo) {
                error = null
                pick.launch(arrayOf("*/*"))
            }
            if (s is OfflineMap.State.Ready) TermButton("Удалить", Palette.Bad) { OfflineMap.delete(context, kind) }
        }
    }
}
