package com.vabugeode.app.data

import android.location.Location
import android.location.LocationManager
import com.vabugeode.app.model.BlacklistPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLocationTest {
    private val now = 1_000_000_000_000L
    private fun loc(provider: String, ageS: Long = 1, acc: Float = 20f,
                    lat: Double = 60.00000, lon: Double = 30.00000): Pair<String, Location> =
        provider to Location(provider).apply {
            latitude = lat; longitude = lon; accuracy = acc
            elapsedRealtimeNanos = now - ageS * 1_000_000_000
            time = System.currentTimeMillis() // ошибочно «свежие» настенные часы
        }

    private fun pick(vararg locations: Pair<String, Location>,
                     blacklist: List<BlacklistPoint> = emptyList()) =
        PhoneLocation.selectCached(locations.toList(), blacklist, 30_000, 1000f, now)

    @Test fun clockChangeCannotMakeOldPhonePositionFresh() {
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, ageS = 380)))
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, ageS = 30)))
        assertNotNull(pick(loc(LocationManager.NETWORK_PROVIDER, ageS = 29)))
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, ageS = -1)))
        val missing = loc(LocationManager.GPS_PROVIDER).apply { second.elapsedRealtimeNanos = 0 }
        assertNull(pick(missing))
    }

    @Test fun networkPriorityDoesNotBypassBlacklistOrAccuracy() {
        val banned = loc(LocationManager.NETWORK_PROVIDER, lat = 60.1161, lon = 31.3864)
        val gps = loc(LocationManager.GPS_PROVIDER)
        val blacklist = listOf(BlacklistPoint(60.1161, 31.3864, 5000, "blocked"))
        assertEquals(gps, pick(banned, gps, blacklist = blacklist))
        assertNull(pick(banned, blacklist = blacklist))
        assertEquals(gps, pick(loc(LocationManager.NETWORK_PROVIDER, acc = 1001f), gps))
        val network = loc(LocationManager.NETWORK_PROVIDER, acc = 500f)
        assertEquals(network, pick(gps, network))
    }

    @Test fun invalidCoordinatesAndUnknownAccuracyAreNeverSent() {
        for (acc in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, acc = acc)))
        }
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, lat = Double.NaN)))
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, lat = 91.0)))
        assertNull(pick(loc(LocationManager.NETWORK_PROVIDER, lon = -181.0)))
        val unknown = loc(LocationManager.GPS_PROVIDER).apply { second.removeAccuracy() }
        assertNull(pick(unknown))
    }
}
