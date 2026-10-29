package com.vabugeode.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
