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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.CheckLevel
import com.vabugeode.app.model.SelfCheck
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.IconBadge
import com.vabugeode.app.ui.components.KeyValue
import com.vabugeode.app.ui.components.Meter
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.Pill
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.components.SnrBars
import com.vabugeode.app.ui.theme.Palette

/** Отчёт о работе устройства: самопроверка, питание, GPS, SD (ТЗ, раздел 13). */
@Composable
fun DeviceScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val info by vm.repo.info.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val i = info ?: return
    val st = status ?: return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Memory, Palette.Geo, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(i.name, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                    Text("${i.chipModel} · прошивка ${i.firmware}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                }
            }
            Spacer(Modifier.height(10.dp))
            KeyValue("Работает", Fmt.duration(i.uptimeSec * 1000))
            KeyValue("Режим", st.mode.title)
            KeyValue("Связь", vm.repo.link.value.title)
            KeyValue("Спутниковые системы", i.gnssSystems)
        }

        val errors = i.checks.count { it.level == CheckLevel.ERROR }
        val warns = i.checks.count { it.level == CheckLevel.WARN }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Самопроверка", Modifier.weight(1f))
                when {
                    errors > 0 -> Pill("ошибок: $errors", Palette.Bad)
                    warns > 0 -> Pill("внимание: $warns", Palette.Warn)
                    else -> Pill("всё в порядке", Palette.Good)
                }
            }
            Spacer(Modifier.height(6.dp))
            i.checks.forEachIndexed { idx, c ->
                if (idx > 0) HorizontalDivider(color = Palette.Outline)
                CheckRow(c)
            }
        }

        val pw = st.power
        Panel {
            SectionLabel("Питание · INA219")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${pw.percent}%", fontSize = 32.sp, color = Palette.Battery)
                Spacer(Modifier.width(10.dp))
                Text("~${Fmt.num(pw.hoursLeft, 0)} ч до разряда", color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Meter(pw.percent / 100f, if (pw.percent < 20) Palette.Bad else Palette.Battery)
            Spacer(Modifier.height(8.dp))
            KeyValue("Напряжение", "${Fmt.num(pw.voltage, 2)} В")
            KeyValue("Ток сейчас", "${Fmt.num(pw.currentMa, 0)} мА")
            KeyValue("Израсходовано", "${Fmt.num(pw.usedMah, 0)} мА·ч")
            Text(
                "Цель ТЗ: 2 суток на ~1300 мА·ч (с поправкой на холод).",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint,
            )
        }

        Panel {
            SectionLabel("Спутники · ${st.gps.satsUsed} в решении из ${st.gps.satsInView}")
            Spacer(Modifier.height(10.dp))
            if (st.gps.satellites.isEmpty()) {
                Text("GPS выключен или спутников не видно", color = Palette.TextDim)
            } else {
                SnrBars(st.gps.satellites, Modifier.fillMaxWidth().height(90.dp), showLabels = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Подозрительно, если у 5+ спутников почти одинаковый сигнал — так бывает при подмене.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint,
                )
            }
        }

        Panel {
            SectionLabel("Карта SD")
            Spacer(Modifier.height(10.dp))
            val used = 1f - i.sdFreeMb.toFloat() / i.sdTotalMb
            Meter(used, Palette.Humidity)
            Spacer(Modifier.height(8.dp))
            KeyValue("Свободно", "${Fmt.num(i.sdFreeMb / 1024.0)} из ${Fmt.num(i.sdTotalMb / 1024.0)} ГБ")
            KeyValue("Текущий файл", "/tracks/…csv", Palette.TextDim)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CheckRow(c: SelfCheck) {
    val (icon, color) = when (c.level) {
        CheckLevel.OK -> Icons.Filled.CheckCircle to Palette.Good
        CheckLevel.WARN -> Icons.Filled.Warning to Palette.Warn
        CheckLevel.ERROR -> Icons.Filled.Error to Palette.Bad
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            Text(c.detail, style = MaterialTheme.typography.labelMedium, color = if (c.level == CheckLevel.OK) Palette.TextDim else color)
        }
        if (c.address != null) {
            Text(c.address, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.TextFaint)
        }
    }
}
