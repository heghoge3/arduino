package com.vabugeode.app.ui

import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import com.vabugeode.app.App
import com.vabugeode.app.ui.theme.VabuTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Отрисовка экранов с демо-данными в PNG (app/build/screenshots).
 * Заодно проверяет, что экраны не падают. Карту не снимаем:
 * MapLibre — нативная библиотека, на JVM не работает.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1500dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        // Симулятор тикает постоянно — время двигаем вручную, иначе тест не дождётся «тишины»
        compose.mainClock.advanceTimeBy(1_000)
        // Корутины viewModelScope живут на главном потоке Robolectric — двигаем и его время (подключение, синхронизация)
        ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS)
        compose.mainClock.advanceTimeBy(500)
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before
    fun moscowTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        // Demo screenshots choose their fixture explicitly; a fresh install now uses the real gadget.
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("link", 0).edit().putString("source", "EMULATOR").commit()
        // Пример прогноза — чтобы на снимке было окошко погоды (в тесте интернета нет)
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("weather", 0).edit()
            .putString(
                "json",
                """{"daily":{"time":["2026-10-28","2026-10-29"],"weather_code":[3,61],""" +
                    """"temperature_2m_max":[4.2,2.1],"temperature_2m_min":[-1.4,-3.0],""" +
                    """"precipitation_probability_max":[10,70],"wind_speed_10m_max":[4.0,7.5]}}""",
            )
            .putLong("at", System.currentTimeMillis()).putString("place", "Санкт-Петербург").commit()
    }

    @Test
    fun screens() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VabuTheme { App() } }
        shot("1_home")
        compose.onNodeWithText("Графики").performClick()
        shot("2_charts")
        compose.onNodeWithText("Карта").performClick()
        shot("3_map_marks")
        compose.onNodeWithText("Походы").performClick()
        // Room читает в своих потоках — ждём настоящее время, пока список не появится
        repeat(100) {
            if (compose.onAllNodesWithText("км").fetchSemanticsNodes().isNotEmpty()) return@repeat
            Thread.sleep(100)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
        }
        shot("8_hikes")
        compose.onAllNodesWithText("км")[0].performClick()
        repeat(50) {
            Thread.sleep(100)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
        }
        shot("9_hike_detail")
        compose.onNodeWithText("Гаджет").performClick()
        shot("4_device")
        compose.onNodeWithText("Открыть").performClick()
        repeat(30) {
            Thread.sleep(100)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
        }
        shot("10_log")
        compose.onNodeWithText("← Гаджет").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        shot("5_settings")
    }

    @Test
    fun compassSettings() {
        compose.mainClock.autoAdvance = false
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        vm.repo.use(com.vabugeode.app.transport.NoTransport)
        vm.repo.compassState.value = 3
        vm.repo.info.value = com.vabugeode.app.model.DeviceInfo("vabuGEOde", "ESP32-S3", "0.1.16", 10, 7600, 7500, "GPS", emptyList())
        vm.repo.navigationState.value = 19
        compose.setContent { VabuTheme {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) { com.vabugeode.app.ui.screens.CompassPanel(vm) }
        } }
        shot("11_compass_settings")
        compose.onNodeWithText("GPS И ШАГИ").assertExists()
        compose.onNodeWithText("Сохранить калибровку").assertDoesNotExist()
        compose.onNodeWithText("Дополнительные настройки").performClick()
        shot("12_navigation_advanced")
        compose.onNodeWithText("Сохранить длину шага").assertExists()
    }

    @Test
    fun manualStartFormValidatesCoordinates() {
        compose.mainClock.autoAdvance=true
        var result:Pair<Double,Double>?=null
        compose.setContent { VabuTheme {
            com.vabugeode.app.ui.screens.ManualStartForm("","",true,{},{} ) {lat,lon -> result=lat to lon}
        } }
        compose.onNodeWithText("Широта").performSemanticsAction(SemanticsActions.SetText) {it(AnnotatedString("91"))}
        compose.onNodeWithText("Долгота").performSemanticsAction(SemanticsActions.SetText) {it(AnnotatedString("30.2"))}
        compose.onNodeWithText("Задать старт").assertIsNotEnabled()
        compose.onNodeWithText("Широта").performSemanticsAction(SemanticsActions.SetText) {it(AnnotatedString("60.1"))}
        compose.onNodeWithText("Задать старт").assertIsEnabled()
        shot("13_manual_start_form")
        compose.onNodeWithText("Задать старт").performClick()
        org.junit.Assert.assertEquals(60.1 to 30.2,result)
    }

    @Test
    fun achievementsKeepLockedSecretsOutOfUi() {
        compose.mainClock.autoAdvance=true
        val all=com.vabugeode.app.achievements.AchievementCatalog.all.map {
            com.vabugeode.app.achievements.AchievementProgress(it,it.target*.25)
        }
        compose.setContent {VabuTheme {com.vabugeode.app.ui.screens.AchievementCollection(all,true,androidx.compose.foundation.layout.PaddingValues())}}
        compose.onNodeWithText("0 / 150").assertExists()
        shot("14_achievements")
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasText("Секретный архив",ignoreCase=true))
        shot("15_achievements_hidden")
        all.filter{it.badge.secret}.forEachIndexed {index,p ->
            org.junit.Assert.assertTrue("Closed secret leaked: $index",compose.onAllNodesWithText(p.badge.title).fetchSemanticsNodes().isEmpty())
            org.junit.Assert.assertTrue("Closed condition leaked: $index",compose.onAllNodesWithText(p.badge.condition).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun obtainedSecretBecomesVisible() {
        val badge=com.vabugeode.app.achievements.Achievement("fixture","Тестовая награда","Условие выполнено",com.vabugeode.app.achievements.BadgeGroup.JOURNAL,com.vabugeode.app.achievements.Rarity.COMMON,"fixture",1.0,secret=true)
        compose.setContent {VabuTheme {com.vabugeode.app.ui.screens.AchievementCollection(listOf(com.vabugeode.app.achievements.AchievementProgress(badge,1.0,1000000000000)),true,androidx.compose.foundation.layout.PaddingValues())}}
        compose.onNodeWithText("Тестовая награда").assertExists()
        compose.onNodeWithText("Условие выполнено").assertExists()
    }

    /** Вечер: после заката, идёт подмена GPS (сценарий, минута ~255). */
    @Test
    fun evening() {
        compose.mainClock.autoAdvance = false
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        vm.demo.restart(historyMin = 255)
        compose.setContent { VabuTheme { App(vm) } }
        shot("6_home_evening_spoof")
        compose.onNodeWithText("Графики").performClick()
        shot("7_charts_evening")
    }
}
