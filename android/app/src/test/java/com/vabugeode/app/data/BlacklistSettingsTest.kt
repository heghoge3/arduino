package com.vabugeode.app.data

import android.content.Context
import com.vabugeode.app.model.BlacklistPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlacklistSettingsTest {
    @Test
    fun diameterIsConvertedToRadiusAndValidated() {
        assertEquals(1000, blockingRadiusFromDiameterKm("2"))
        assertEquals(2250, blockingRadiusFromDiameterKm(" 4,5 "))
        assertEquals(100, blockingRadiusFromDiameterKm("0.2"))
        assertEquals(50000, blockingRadiusFromDiameterKm("100"))
        listOf("", "0", "-2", "0.1", "101", "NaN", "Infinity", "abc").forEach {
            assertNull(it, blockingRadiusFromDiameterKm(it))
        }
    }

    @Test
    fun diameterSurvivesRestartAndBelongsToCoordinates() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("blacklist_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = BlacklistPoint(60.0346, 30.5693, 1000, "A")
        val b = BlacklistPoint(60.1161, 31.3864, 1000, "B")
        val first = BlacklistSettings(prefs)
        assertEquals(listOf(a, b), first.load(listOf(a, b)))
        first.save(listOf(a, b.copy(radiusM = 2250)))
        val restored = BlacklistSettings(prefs).load(listOf(b, a))
        assertEquals(2250, restored[0].radiusM)
        assertEquals(a, restored[1])
    }
}
