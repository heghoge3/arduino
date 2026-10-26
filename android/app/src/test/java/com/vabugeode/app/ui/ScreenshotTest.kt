package com.vabugeode.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vabugeode.app.App
import com.vabugeode.app.ui.theme.VabuTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

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
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before
    fun moscowTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    @Test
    fun screens() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VabuTheme { App() } }
        shot("1_home")
        compose.onNodeWithText("Графики").performClick()
        shot("2_charts")
        compose.onNodeWithText("Метки").performClick()
        shot("3_marks")
        compose.onNodeWithText("Трекер").performClick()
        shot("4_device")
        compose.onNodeWithContentDescription("Настройки").performClick()
        shot("5_settings")
    }

    /** Вечер: после заката, идёт подмена GPS (сценарий, минута ~255). */
    @Test
    fun evening() {
        compose.mainClock.autoAdvance = false
        val vm = AppViewModel()
        vm.demo.restart(historyMin = 255)
        compose.setContent { VabuTheme { App(vm) } }
        shot("6_home_evening_spoof")
        compose.onNodeWithText("Графики").performClick()
        shot("7_charts_evening")
    }
}
