package com.vabugeode.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarksScreen(vm: AppViewModel, contentPadding: PaddingValues, onAdded: (String) -> Unit) {
    val marks by vm.repo.marks.collectAsStateWithLifecycle()
    val start by vm.repo.hikeStartMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var textDialog by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel {
                SectionLabel("Быстрая метка")
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MarkType.ENTER, MarkType.FOUND, MarkType.GUARD, MarkType.INTERESTING, MarkType.SYNC).forEach { t ->
                        AssistChip(
                            onClick = {
                                if (t == MarkType.SYNC) vm.repo.clap() else vm.repo.addMark(t)
                                onAdded("${t.emoji} ${t.title} — метка поставлена")
                            },
                            label = { Text("${t.emoji}  ${t.title}") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = markColor(t).copy(alpha = 0.14f),
                                labelColor = markColor(t),
                            ),
                            border = null,
                        )
                    }
                    AssistChip(
                        onClick = { textDialog = true },
                        label = { Text("✏️  Свой текст") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Palette.SurfaceHigh, labelColor = Palette.Text),
                        border = null,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                SectionLabel("Метки похода · ${marks.size}", Modifier.weight(1f))
                Button(
                    onClick = {
                        val text = capcutExport(marks, start)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Метки vabuGEOde")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Метки для монтажа"))
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Для CapCut")
                }
            }
        }
        items(marks.asReversed(), key = { it.id }) { m -> MarkRow(m, start) }
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

@Composable
private fun MarkRow(m: Mark, start: Long?) {
    val color = markColor(m.type)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Text(m.type.emoji, fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (m.text.isNotBlank()) m.text else m.type.title,
                style = MaterialTheme.typography.titleMedium, color = Palette.Text,
            )
            Text(m.type.title, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.timeSec(m.tsUtcMs), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Palette.Text, fontSize = 14.sp)
            if (start != null) {
                Text("+" + Fmt.clock(m.tsUtcMs - start), fontFamily = FontFamily.Monospace, color = Palette.TextDim, fontSize = 12.sp)
            }
        }
    }
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
