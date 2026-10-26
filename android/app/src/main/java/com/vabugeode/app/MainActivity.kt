package com.vabugeode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.components.Logo
import com.vabugeode.app.ui.components.Pill
import com.vabugeode.app.ui.screens.ChartsScreen
import com.vabugeode.app.ui.screens.DeviceScreen
import com.vabugeode.app.ui.screens.HomeScreen
import com.vabugeode.app.ui.screens.MapScreen
import com.vabugeode.app.ui.screens.MarksScreen
import com.vabugeode.app.ui.screens.SettingsScreen
import com.vabugeode.app.ui.theme.Palette
import com.vabugeode.app.ui.theme.VabuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
        )
        setContent { VabuTheme { App() } }
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    MAP("Карта", Icons.Filled.Map),
    CHARTS("Графики", Icons.AutoMirrored.Filled.ShowChart),
    MARKS("Метки", Icons.Filled.Bookmarks),
    DEVICE("Трекер", Icons.Filled.Memory),
}

@Composable
internal fun App(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val link by vm.repo.link.collectAsStateWithLifecycle()

    fun toast(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // Ответы устройства, которые надо показать (отказ команды, нет связи)
    LaunchedEffect(Unit) { vm.repo.notices.collect { toast(it) } }

    // Приложение на экране — слушаем маячки и подключаемся; свернули — перестаём искать
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.link.setForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.link.setForeground(false)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BackHandler(enabled = settings) { settings = false }
    BackHandler(enabled = !settings && tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Bg)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (settings) {
                    IconButton(onClick = { settings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Palette.Text)
                    }
                    Text("Настройки", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                } else {
                    Spacer(Modifier.width(8.dp))
                    Logo()
                }
                Spacer(Modifier.weight(1f))
                Pill(
                    // Пока устройство виртуальное — показываем это явно, чтобы не путать с настоящим
                    if (link == LinkState.CONNECTED) "Эмулятор" else link.title,
                    when (link) {
                        LinkState.CONNECTED -> Palette.Geo
                        LinkState.CONNECTING -> Palette.Warn
                        else -> Palette.TextDim
                    },
                )
                if (!settings) {
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Filled.Settings, "Настройки", tint = Palette.TextDim)
                    }
                } else Spacer(Modifier.width(12.dp))
            }
        },
        bottomBar = {
            if (!settings) {
                NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.title) },
                            label = { Text(t.title, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Geo,
                                selectedTextColor = Palette.Geo,
                                indicatorColor = Palette.Geo.copy(alpha = 0.16f),
                                unselectedIconColor = Palette.TextDim,
                                unselectedTextColor = Palette.TextDim,
                            ),
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        if (settings) {
            SettingsScreen(vm, padding)
        } else when (tab) {
            Tab.HOME -> HomeScreen(
                vm, padding,
                onMark = { vm.repo.addMark(com.vabugeode.app.model.MarkType.MARK); toast("📍 Метка поставлена") },
                onClap = { vm.repo.clap(); toast("🎬 Хлопушка! Устройство пищит и мигает — метка SYNC") },
            )
            Tab.MAP -> MapScreen(vm, padding)
            Tab.CHARTS -> ChartsScreen(vm, padding)
            Tab.MARKS -> MarksScreen(vm, padding, onAdded = ::toast)
            Tab.DEVICE -> DeviceScreen(vm, padding)
        }
    }
}
