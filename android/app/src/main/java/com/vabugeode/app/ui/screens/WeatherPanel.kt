package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.Weather
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.theme.Palette
import kotlin.math.roundToInt

/** Небольшое окошко погоды на Главной: сегодня и завтра для места телефона */
@Composable
internal fun WeatherPanel(vm: AppViewModel) {
    val f by vm.weather.collectAsStateWithLifecycle()
    val forecast = f
    val age = forecast?.let { System.currentTimeMillis() - it.fetchedMs }
    val updated = when {
        forecast == null -> null
        age!! < 3 * 60 * 60_000 -> "обновлено ${Fmt.time(forecast.fetchedMs)}"
        else -> "от ${Fmt.dayMonth(forecast.fetchedMs)} ${Fmt.time(forecast.fetchedMs)}"
    }
    Panel(accent = Palette.Geo, title = "Погода", titleRight = updated) {
        if (forecast == null || forecast.days.isEmpty()) {
            Text(
                "Прогноз появится, когда у телефона будут интернет и позиция",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            )
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            forecast.days.take(2).forEachIndexed { i, d ->
                Column(Modifier.weight(1f)) {
                    Text(if (i == 0) "СЕГОДНЯ" else "ЗАВТРА", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconOf(d.code), null, tint = colorOf(d.code), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${temp(d.tMin)}…${temp(d.tMax)} °C",
                            style = MaterialTheme.typography.titleMedium, color = Palette.Text,
                        )
                    }
                    Text(Weather.describe(d.code), style = MaterialTheme.typography.labelMedium, color = colorOf(d.code))
                    Text("осадки ${d.precipProb}%", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                    Text("ветер до ${d.windMaxMs.roundToInt()}\u00A0м/с", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                }
            }
        }
        // Где брался прогноз: пишет «Санкт-Петербург», а мы уже в Сланцах — значит, ещё не обновился
        forecast.place?.let { place ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, null, tint = Palette.TextFaint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(place, style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
            }
        }
    }
}

private fun temp(v: Double) = if (v.isNaN()) "—" else v.roundToInt().let { if (it > 0) "+$it" else "$it" }

private fun iconOf(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Filled.WbSunny
    2 -> Icons.Filled.WbCloudy
    3, 45, 48 -> Icons.Filled.Cloud
    in 51..67, 80, 81, 82 -> Icons.Filled.WaterDrop
    in 71..77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.Thunderstorm
    else -> Icons.Filled.Cloud
}

private fun colorOf(code: Int) = when (code) {
    0, 1 -> Palette.Warn
    in 51..67, 80, 81, 82 -> Palette.Humidity
    in 71..77, 85, 86 -> Palette.Text
    95, 96, 99 -> Palette.Bad
    else -> Palette.TextDim
}
