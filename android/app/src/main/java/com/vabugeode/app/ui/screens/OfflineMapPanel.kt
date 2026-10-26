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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.OfflineMap
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Meter
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Настройки → офлайн-карта: загрузить файл .pmtiles из памяти телефона или удалить. */
@Composable
fun OfflineMapPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by OfflineMap.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { OfflineMap.refresh(context) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = OfflineMap.import(context, uri).exceptionOrNull()?.message
        }
    }

    val s = state
    Panel(accent = if (s is OfflineMap.State.Ready) Palette.Good else Palette.Warn, title = "Офлайн-карта") {
        when (s) {
            is OfflineMap.State.Ready -> Text(
                "Загружена · ${Fmt.num(s.bytes / 1_048_576.0, 0)} МБ. Карта работает без интернета.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
            )
            is OfflineMap.State.Copying -> {
                Text("Копируем файл… ${(s.fraction * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                Spacer(Modifier.height(8.dp))
                Meter(s.fraction, Palette.Geo)
            }
            else -> Text(
                "Не загружена — карта показывается только с интернетом. " +
                    "Выберите файл региона .pmtiles (Ленобласть ≈ 570 МБ).",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
            )
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Bad)
        }
        if (s !is OfflineMap.State.Copying) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton(if (s is OfflineMap.State.Ready) "Заменить файл" else "Загрузить файл", Palette.Geo) {
                    error = null
                    pick.launch(arrayOf("*/*"))
                }
                if (s is OfflineMap.State.Ready) TermButton("Удалить", Palette.Bad) { OfflineMap.delete(context) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Карта открывается заново при переходе на вкладку.", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
    }
}
