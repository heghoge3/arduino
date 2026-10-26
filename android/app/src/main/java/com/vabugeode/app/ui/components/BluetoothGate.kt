package com.vabugeode.app.ui.components

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vabugeode.app.ui.theme.Palette

/** Разрешения для работы с гаджетом: поиск и подключение по Bluetooth, геолокация телефона. */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(ctx: Context) =
    blePermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

fun bluetoothOn(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/**
 * Проверяет разрешения и включённый Bluetooth. Всё в порядке — показывает content,
 * иначе — объяснение и кнопку. onReady вызывается, когда всё стало можно (перезапустить поиск).
 */
@Composable
fun BluetoothGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(ctx)) }
    var enabled by remember { mutableStateOf(bluetoothOn(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBlePermissions(ctx)
        enabled = bluetoothOn(ctx)
        if (granted && enabled) onReady()
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = bluetoothOn(ctx)
        if (granted && enabled) onReady()
    }
    when {
        !granted -> Panel(accent = Palette.Warn) {
            SectionLabel("Нужны разрешения", color = Palette.Warn)
            Spacer(Modifier.height(6.dp))
            Text(
                "Bluetooth — чтобы найти гаджет и подключиться. Геолокация — чтобы гаджет сверял свой GPS " +
                    "с телефоном (защита от подмены) и чтобы запомнить, где вы его последний раз слышали.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { permLauncher.launch(blePermissions()) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Разрешить")
            }
        }
        !enabled -> Panel(accent = Palette.Warn) {
            SectionLabel("Bluetooth выключен", color = Palette.Warn)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("Включить Bluetooth") }
        }
        else -> content()
    }
}
