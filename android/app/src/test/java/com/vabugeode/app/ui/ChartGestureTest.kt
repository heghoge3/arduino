package com.vabugeode.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.vabugeode.app.ui.components.ChartSeries
import com.vabugeode.app.ui.components.LineChart
import com.vabugeode.app.ui.theme.VabuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChartGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun buttonsAndPinchZoomAndReset() {
        compose.setContent {
            VabuTheme {
                LineChart(listOf(ChartSeries((0..120).map { it * 60_000L to (20.0 + it / 100.0) }, Color.Red, "T")),
                    Modifier.fillMaxWidth().height(300.dp))
            }
        }
        compose.onNodeWithContentDescription("Приблизить график").performClick()
        compose.onNodeWithText("2,0×").assertExists()
        compose.onNodeWithContentDescription("Показать весь период").performClick()
        compose.onNodeWithText("1,0×").assertExists()
        compose.onNodeWithContentDescription("График").performTouchInput {
            pinch(Offset(width * 0.4f, height * 0.5f), Offset(width * 0.6f, height * 0.5f),
                Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f), 400)
        }
        compose.onNodeWithContentDescription("Показать весь период").assertIsEnabled()
        compose.onNodeWithContentDescription("Показать весь период").performClick()
        compose.onNodeWithText("1,0×").assertExists()
    }

    @Test
    fun verticalSwipeOnChartStillScrollsPage() {
        val scroll = ScrollState(0)
        compose.setContent {
            VabuTheme {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    LineChart(listOf(ChartSeries(listOf(0L to 20.0, 3_600_000L to 21.0), Color.Red, "T")),
                        Modifier.fillMaxWidth().height(300.dp))
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }
        compose.onNodeWithContentDescription("График").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("вертикальная прокрутка заблокирована графиком", scroll.value > 0) }
    }
}
