package com.vabugeode.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.Source
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Palette

/** Настройка фонового наблюдения: переключатель и кнопка инструкции для XOS. */
@Composable
internal fun WatchPanel(vm: AppViewModel, onGuide: () -> Unit) {
    val ctx = LocalContext.current
    val on by vm.watchEnabled.collectAsStateWithLifecycle()
    val source by vm.link.source.collectAsStateWithLifecycle()
    val selected by vm.link.selectedId.collectAsStateWithLifecycle()
    val available = source == Source.GADGET && selected != null
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setWatch(true)  // без разрешения сервис работает, просто без уведомлений — скажет инструкция
    }
    Panel {
        SectionLabel("Фоновое наблюдение")
        Spacer(Modifier.height(6.dp))
        Text(
            "Пока приложение закрыто, телефон слушает маячки гаджета: сводка в уведомлении, тревоги со звуком, " +
                "и запоминается, где вы его последний раз слышали. Гаджету это ничего не стоит — он не подключается.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "Включено" else "Выключено", color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(
                checked = on && available,
                enabled = available,
                onCheckedChange = { want ->
                    if (want && Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setWatch(want)
                    }
                },
            )
        }
        if (!available) {
            Text(
                "Работает только с настоящим гаджетом: выберите «Гаджет» выше и гаджет на вкладке «Трекер».",
                style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGuide, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Настроить телефон, чтобы фон не убивался")
        }
    }
}

/**
 * Пошаговая инструкция для Infinix (XOS): оболочка агрессивно закрывает фоновые приложения.
 * Названия пунктов в XOS могут отличаться по версиям — проверим на телефоне Димы.
 */
@Composable
internal fun XosGuide(contentPadding: PaddingValues, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    // Пересчитывать галочки, когда вернулись из системных настроек
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val notifOk = remember(refresh) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val batteryOk = remember(refresh) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    val pkg = Uri.parse("package:${ctx.packageName}")

    fun open(i: Intent, fallback: Intent? = null) {
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            fallback?.let { runCatching { ctx.startActivity(it) } }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Оболочка XOS на Infinix закрывает приложения в фоне, чтобы беречь батарею. Чтобы наблюдение работало " +
                "в походе, пройдите шаги ниже. Названия пунктов могут немного отличаться — ищите похожие.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
        )
        GuideStep(
            n = 1, done = notifOk, title = "Разрешить уведомления",
            text = "Иначе тревоги (подмена GPS, низкий заряд) не покажутся.",
            button = "Открыть уведомления",
        ) {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            )
        }
        GuideStep(
            n = 2, done = batteryOk, title = "Батарея: без ограничений",
            text = "Разрешить работать в фоне без оптимизации батареи. Телефон сам почти не тратит заряд: " +
                "поиск маячков делает Bluetooth-чип.",
            button = "Снять ограничение",
        ) {
            @Suppress("BatteryLife")
            open(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        GuideStep(
            n = 3, done = null, title = "Автозапуск (XOS)",
            text = "В XOS обычно: Настройки → Приложения → Автозапуск, или приложение «Phone Master» → «Автозапуск». " +
                "Включите для vabuGEOde. Там же может быть «Фоновая активность» — разрешить.",
            button = "Открыть настройки приложения",
        ) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        GuideStep(
            n = 4, done = null, title = "Закрепить в недавних",
            text = "Откройте список недавних приложений, на карточке vabuGEOde нажмите замок (или потяните её вниз). " +
                "Тогда «Очистить всё» её не закроет.",
            button = null,
        ) {}
        GuideStep(
            n = 5, done = null, title = "Проверить",
            text = "Включите наблюдение, заблокируйте экран на 15 минут рядом с гаджетом. В уведомлении «маячок в ЧЧ:ММ» " +
                "должно обновляться. Если застыло — XOS всё ещё убивает фон: напишите, разберёмся.",
            button = null,
        ) {}
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GuideStep(n: Int, done: Boolean?, title: String, text: String, button: String?, onClick: () -> Unit) {
    Panel(accent = if (done == true) Palette.Good else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done == true) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (done == true) Palette.Good else Palette.TextDim, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("$n. $title", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        if (button != null && done != true) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text(button) }
        }
    }
}
