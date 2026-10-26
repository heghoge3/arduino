package com.vabugeode.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.components.TermButton
import com.vabugeode.app.ui.theme.Palette

/** Метки текущего похода под картой: быстрые кнопки, список, экспорт для CapCut. */
@Composable
fun MarksPanel(vm: AppViewModel, marks: List<Mark>, start: Long?, onAdded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var textDialog by remember { mutableStateOf(false) }

    Column(modifier.background(Palette.Bg)) {
        // Быстрые метки — одной строкой, листаются вбок
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(MarkType.ENTER, MarkType.FOUND, MarkType.GUARD, MarkType.INTERESTING, MarkType.SYNC).forEach { t ->
                TermButton("${t.emoji} ${t.title}", markColor(t)) {
                    if (t == MarkType.SYNC) vm.repo.clap() else vm.repo.addMark(t)
                    onAdded("${t.emoji} ${t.title} — метка поставлена")
                }
            }
            TermButton("✏️ Свой текст", Palette.Text) { textDialog = true }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Метки похода · ${marks.size}", Modifier.weight(1f))
            TermButton("↗ CapCut", Palette.Geo) { shareMarks(context, marks, start) }
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        ) {
            items(marks.asReversed(), key = { it.id }) { m -> MarkLine(m, start) }
        }
    }

    if (textDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text("Метка с текстом") },
            text = {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Что здесь?") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.repo.addMark(MarkType.TEXT, text.trim())
                    textDialog = false
                    onAdded("✏️ Метка поставлена")
                }, enabled = text.isNotBlank()) { Text("Поставить") }
            },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text("Отмена") } },
        )
    }
}

/** Строка метки как в логе: «14:23:05  +01:23:05  НАШЛИ  текст» */
@Composable
fun MarkLine(m: Mark, start: Long?) {
    val color = markColor(m.type)
    Column {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(76.dp)) {
                Text(Fmt.timeSec(m.tsUtcMs), style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                if (start != null) {
                    Text("+" + Fmt.clock(m.tsUtcMs - start), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(m.type.emoji)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.type.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
                if (m.text.isNotBlank()) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
    }
}

fun shareMarks(context: Context, marks: List<Mark>, start: Long?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Метки vabuGEOde")
        putExtra(Intent.EXTRA_TEXT, capcutExport(marks, start))
    }
    context.startActivity(Intent.createChooser(send, "Метки для монтажа"))
}

/** Экспорт для монтажа: «HH:MM:SS (+от старта) — тип — текст» (ТЗ, раздел 9). */
fun capcutExport(marks: List<Mark>, start: Long?): String = buildString {
    appendLine("Метки vabuGEOde")
    if (start != null) appendLine("Старт: ${Fmt.timeSec(start)}")
    appendLine()
    marks.forEach { m ->
        append(Fmt.timeSec(m.tsUtcMs))
        if (start != null) append(" (+${Fmt.clock(m.tsUtcMs - start)})")
        append(" — ${m.type.title}")
        if (m.text.isNotBlank()) append(" — ${m.text}")
        appendLine()
    }
}
