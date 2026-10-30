package com.vabugeode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.vabugeode.app.ui.screens.HikesScreen
import com.vabugeode.app.ui.screens.SettingsScreen
import com.vabugeode.app.ui.screens.AchievementsScreen
import com.vabugeode.app.ui.theme.Palette
import com.vabugeode.app.ui.theme.VabuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var achievementRequest by mutableStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
        )
        if(intent.getBooleanExtra("achievements",false))achievementRequest++
        setContent { VabuTheme { App(openAchievements = achievementRequest) } }
    }
    override fun onNewIntent(intent:android.content.Intent){
        super.onNewIntent(intent)
        if(intent.getBooleanExtra("achievements",false))achievementRequest++
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    MAP("Карта", Icons.Filled.Map),
    CHARTS("Графики", Icons.AutoMirrored.Filled.ShowChart),
    HIKES("Походы", Icons.Filled.Hiking),
    DEVICE("Гаджет", Icons.Filled.Memory),
}

@Composable
internal fun App(vm: AppViewModel = viewModel(), openAchievements:Int=0) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var achievements by rememberSaveable {mutableStateOf(openAchievements>0)}
    LaunchedEffect(openAchievements){if(openAchievements>0)achievements=true}
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val link by vm.repo.link.collectAsStateWithLifecycle()
    val source by vm.link.source.collectAsStateWithLifecycle()

    fun toast(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // Ответы устройства, которые надо показать (отказ команды, нет связи)
    LaunchedEffect(Unit) { vm.repo.notices.collect { toast(it) } }
    LaunchedEffect(Unit) { vm.achievementNotices.collect {toast(it)} }

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
    BackHandler(enabled = !settings && !achievements && tab != Tab.HOME) { tab = Tab.HOME }
    BackHandler(enabled = !settings && achievements) {achievements=false;tab=Tab.HIKES}

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
                if (settings || achievements) {
                    IconButton(onClick = { if(settings)settings=false else {achievements=false;tab=Tab.HIKES} }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Palette.Text)
                    }
                    Text(if(settings)"Настройки" else "Достижения", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                } else {
                    Spacer(Modifier.width(8.dp))
                    Logo()
                }
                Spacer(Modifier.weight(1f))
                Pill(
                    // Эмулятор показываем явно, чтобы не путать с настоящим гаджетом
                    if (link == LinkState.CONNECTED) source.title else link.title,
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
                TermNavBar(if(achievements)Tab.HIKES else tab) { achievements=false;tab = it }
            }
        },
        modifier = Modifier.fillMaxSize().scanlines(),
    ) { padding ->
        if (settings) {
            SettingsScreen(vm, padding)
        } else if(achievements) {
            AchievementsScreen(vm,padding)
        } else androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(200), label = "tab") { tab -> when (tab) {
            Tab.HOME -> HomeScreen(
                vm, padding,
                onMark = { vm.repo.addMark(com.vabugeode.app.model.MarkType.MARK); toast("📍 Метка поставлена") },
                onClap = { vm.repo.clap(); toast("🎬 Хлопушка! Устройство пищит и мигает — метка SYNC") },
            )
            Tab.MAP -> MapScreen(vm, padding, onAdded = ::toast)
            Tab.CHARTS -> ChartsScreen(vm, padding)
            Tab.HIKES -> HikesScreen(vm, padding) {achievements=true}
            Tab.DEVICE -> DeviceScreen(vm, padding)
        } }
    }
}

/** Сканлайны поверх всего экрана: тонкие полосы через 3 dp, едва заметные. */
private fun Modifier.scanlines() = drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Palette.Scan, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
        y += step
    }
}

/** Нижняя панель терминала: квадратные ячейки, выбранная — инверсный блок */
@Composable
private fun TermNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.background(Palette.Bg).navigationBarsPadding()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            Tab.entries.forEach { t ->
                val on = t == selected
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .weight(1f)
                        .background(if (on) Palette.Geo else Palette.Bg)
                        .clickable { onSelect(t) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, tint = if (on) Palette.Bg else Palette.TextDim, modifier = Modifier.size(22.dp))
                    Text(
                        t.title, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = if (on) Palette.Bg else Palette.TextDim,
                    )
                }
            }
        }
    }
}
