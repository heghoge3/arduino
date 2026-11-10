package com.vabugeode.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vabugeode.app.ui.screens.EmulatorPanel
import com.vabugeode.app.ui.theme.VabuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w400dp-h1200dp-xhdpi")
class EmulatorPanelTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 @Test fun scenariosAreVisibleAndFaultControlsAffectOnlyEmulator() {
   val vm=AppViewModel(RuntimeEnvironment.getApplication())
   compose.setContent { VabuTheme { Column(Modifier.verticalScroll(rememberScrollState())) {EmulatorPanel(vm)} } }
   compose.onNodeWithText("ЭМУЛЯТОР V2").assertExists()
   compose.onNodeWithText("Смоделировать ошибку SD").performScrollTo().performClick()
   compose.onNodeWithText("Восстановить SD").assertExists().performClick()
   compose.onNodeWithText("Потерять ответ следующей команды похода").performScrollTo().performClick()
   compose.onNodeWithText("Потеря ответа включена — отменить").assertExists().performClick()
   compose.onNodeWithText("Внезапное отключение / разрядка").assertExists()
   compose.onNodeWithText("Завершить поход и выключить").assertExists()
   val view=compose.activity.window.decorView
   val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
   compose.runOnUiThread {view.draw(Canvas(image))}
   File("build/screenshots").mkdirs()
   File("build/screenshots/emulator-v2.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
 }
}
