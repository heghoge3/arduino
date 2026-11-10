package com.vabugeode.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vabugeode.app.data.HikeLifecycleTest
import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.protocol.HikeStateMsg
import com.vabugeode.app.ui.screens.HikeControls
import com.vabugeode.app.ui.theme.VabuTheme
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="w400dp-h900dp-xhdpi")
class HikeControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun finishRequiresConfirmationAndOffersSeparateStart() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val gadget=HikeLifecycleTest.Gadget().also { it.endedAt=1_793_000_000 }
        val repo=ProtocolDeviceRepository(scope,gadget,emptyList())
        try {
            compose.setContent { VabuTheme { HikeControls(repo) } }
            compose.runOnIdle { repo.connect() }
            compose.waitUntil(5000) { repo.lifecycleFresh.value && repo.statusFresh.value && !repo.stats.value.syncing }
            compose.onNodeWithText("Завершить поход").performClick()
            compose.onNodeWithText("Завершить поход?").assertExists()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(0,gadget.actions)
            compose.onNodeWithText("Завершить поход").performClick()
            compose.onNodeWithText("Подтвердить").performClick()
            compose.waitUntil(5000) { repo.lifecycle.value?.phase==HikeStateMsg.ENDED && !repo.hikeBusy.value }
            compose.onNodeWithText("Поход завершён").assertExists()
            compose.onNodeWithText("Начать поход").assertExists()
            compose.onNodeWithText("Завершить поход").assertDoesNotExist()
            assertEquals(1,gadget.actions)
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            File("build/screenshots").mkdirs()
            File("build/screenshots/hike-ended.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.runOnIdle { repo.disconnect() }
            compose.onNodeWithText("Последний подтверждённый поход завершён").assertExists()
            compose.onNodeWithText("Начать поход").assertIsNotEnabled()
        } finally { scope.cancel() }
    }

    @Test fun cachedActiveStateCannotFinishOffline() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val repo=ProtocolDeviceRepository(scope,HikeLifecycleTest.Gadget(),emptyList())
        try {
            compose.setContent { VabuTheme { HikeControls(repo) } }
            compose.runOnIdle { repo.lifecycle.value=HikeStateMsg(1,255,7,100,0,0) }
            compose.onNodeWithText("Завершение не подтверждено").assertExists()
            compose.onNodeWithText("Завершить поход").assertIsNotEnabled()
        } finally { scope.cancel() }
    }
}
