package com.vabugeode.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import com.vabugeode.app.model.*
import com.vabugeode.app.transport.NoTransport
import com.vabugeode.app.ui.screens.HomeScreen
import androidx.compose.foundation.layout.PaddingValues
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import com.vabugeode.app.ui.screens.ReadingsNotice
import com.vabugeode.app.ui.theme.VabuTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class ReadingsNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnectedSnapshotExplainsUnknownStateAndProvidesReconnect() {
        var refreshed = false
        compose.setContent { VabuTheme {
            ReadingsNotice(LinkState.DISCONNECTED, 1_793_000_000_000) { refreshed = true }
        } }
        compose.onNodeWithText("Сохранённые данные").assertExists()
        compose.onNodeWithText("Текущее состояние устройства неизвестно.", substring = true).assertExists()
        compose.onNodeWithText("Подключить устройство").performClick()
        assertTrue(refreshed)
    }

    @Test fun connectionWithoutStatusIsNotPresentedAsLive() {
        compose.setContent { VabuTheme { ReadingsNotice(LinkState.CONNECTED, 1_793_000_000_000) {} } }
        compose.onNodeWithText("Ожидаем свежие показания").assertExists()
        compose.onNodeWithText("Запросить показания").assertExists()
    }

    @Test fun homeKeepsSavedReadingsCollapsedAndControlsDisabled() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        vm.repo.use(NoTransport)
        ShadowLooper.idleMainLooper()
        vm.repo.status.value = DeviceStatus(
            1_793_000_000_000, PowerMode.CAMP, true,
            GpsState(GpsFix.OFF, 0, 0, Double.NaN, 0.0, 0.0, null, emptyList(), false, 0),
            EnvState(12.0, 12.0, 70.0, 1010.0, 0.0, 0.0),
            PowerState(4.0, 20.0, 80, false, 10.0, 50.0),
            100, 0.0, 0.1, PositionSource.NONE, null, null,
        )
        compose.setContent { VabuTheme { HomeScreen(vm, PaddingValues(), {}, {}) } }
        compose.onNodeWithText("Сохранённые данные").assertExists()
        compose.onNodeWithText("[ МЕТКА ]").assertDoesNotExist()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File("build/screenshots").mkdirs()
        File("build/screenshots/home_saved.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Показать сохранённые показания").performClick()
        compose.onNodeWithText("[ МЕТКА ]").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("[ ПАУЗА ]").assertIsNotEnabled()
    }
}
