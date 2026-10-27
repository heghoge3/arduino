package com.vabugeode.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.log.LogText
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun levelColor(level: Int): Color = when (level) {
    0 -> Palette.Bad
    1 -> Palette.Warn
    2 -> Palette.Text
    else -> Palette.TextFaint
}

/** Вход в журнал на вкладке «Гаджет»: сколько ошибок и предупреждений за сутки */
@Composable
fun LogEntryPanel(vm: AppViewModel, onOpen: () -> Unit) {
    val g by vm.gadgetLog.collectAsStateWithLifecycle()
    val a by vm.appLog.collectAsStateWithLifecycle()
    val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
    val errors = g.count { it.level == 0 && it.timeMs > dayAgo } + a.count { it.level == 0 && it.timeMs > dayAgo }
    val warns = g.count { it.level == 1 && it.timeMs > dayAgo } + a.count { it.level == 1 && it.timeMs > dayAgo }
    Panel(accent = if (errors > 0) Palette.Bad else if (warns > 0) Palette.Warn else null, title = "Журнал") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "За сутки: ошибок $errors, внимание $warns",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (errors > 0) Palette.Bad else if (warns > 0) Palette.Warn else Palette.Text,
                )
                Text("гаджет ${g.size} строк · телефон ${a.size}", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
            TermButton("Открыть", Palette.Geo, onClick = onOpen)
        }
    }
}

/** Журнал гаджета и телефона: фильтры, отладочный режим, «поделиться» файлом */
@Composable
fun LogScreen(vm: AppViewModel, contentPadding: PaddingValues, onClose: () -> Unit) {
    val g by vm.gadgetLog.collectAsStateWithLifecycle()
    val a by vm.appLog.collectAsStateWithLifecycle()
    val debug by vm.debugLog.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(0) }      // 0 всё, 1 гаджет, 2 телефон
    var problems by rememberSaveable { mutableStateOf(false) } // только ошибки и внимание
    var sharing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lines = remember(g, a, source, problems) {
        LogText.merge(if (source == 2) emptyList() else g, if (source == 1) emptyList() else a)
            .filter { !problems || it.level <= 1 }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermButton("← Гаджет", Palette.TextDim, onClick = onClose)
                Spacer(Modifier.weight(1f))
                TermButton(if (sharing) "Готовим…" else "↗ Поделиться", Palette.Geo) {
                    if (sharing) return@TermButton
                    sharing = true
                    scope.launch {
                        runCatching { shareLog(context, vm.exportLogs()) }
                        sharing = false
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Всё", "Гаджет", "Телефон").forEachIndexed { i, t ->
                    TermButton(t, Palette.Geo, selected = source == i) { source = i }
                }
                TermButton("Только проблемы", Palette.Warn, selected = problems) { problems = !problems }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Подробный журнал (отладка)", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                    Text(
                        "Гаджет пишет снимок состояния раз в минуту вместо 10 и все команды. На батарею почти не влияет.",
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint,
                    )
                }
                Switch(
                    checked = debug, onCheckedChange = vm::setDebugLog,
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Geo, checkedThumbColor = Palette.Bg),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        if (lines.isEmpty()) {
            Text(
                "Пусто. Журнал гаджета приходит при каждом подключении.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        ) {
            items(lines, key = { it.key }) { l -> LogLineRow(l) }
        }
    }
}

@Composable
private fun LogLineRow(l: LogText.Line) {
    val color = levelColor(l.level)
    Row(Modifier.padding(vertical = 3.dp)) {
        Column(Modifier.width(78.dp)) {
            Text(
                if (l.timeMs > 0) Fmt.timeSec(l.timeMs) + (if (l.timeOk) "" else "?") else "+${l.uptimeSec ?: 0}с",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextDim,
            )
            Text(
                (if (l.gadget) "ГАДЖ " else "ТЕЛ ") + l.tag,
                style = MaterialTheme.typography.labelSmall,
                color = if (l.gadget) Palette.Geo else Palette.Pressure,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (l.level <= 1) "${LogText.levelChar(l.level)} " else "") + l.text,
            style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f),
        )
    }
}

/** Журнал — файлом через «Поделиться» (Telegram, почта…): текст бывает длинным для обычной отправки */
private suspend fun shareLog(context: Context, text: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "vabugeode-log-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".txt"
        File(dir, name).apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Журнал vabuGEOde")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Журнал vabuGEOde"))
}
