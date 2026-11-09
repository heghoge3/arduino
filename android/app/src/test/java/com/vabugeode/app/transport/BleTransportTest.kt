package com.vabugeode.app.transport

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.vabugeode.app.model.LinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {
    private fun enableBluetooth() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
    }

    private fun gatt(t: BleTransport): BluetoothGatt =
        BleTransport::class.java.getDeclaredField("gatt").run {
            isAccessible = true
            get(t) as BluetoothGatt
        }

    @Test
    fun waitsForCampBeaconAndEventuallyTimesOut() = runTest {
        enableBluetooth()
        val t = BleTransport(RuntimeEnvironment.getApplication(), backgroundScope, "90:DA:72:47:5B:E9", "test")
        t.connect()
        runCurrent()
        advanceTimeBy(60_001)
        assertEquals(LinkState.CONNECTING, t.state.value)
        advanceTimeBy(30_000)
        assertEquals(LinkState.DISCONNECTED, t.state.value)
    }

    @Test
    fun oldTimeoutAndCallbackCannotCloseNewAttempt() = runTest {
        enableBluetooth()
        val t = BleTransport(RuntimeEnvironment.getApplication(), backgroundScope, "90:DA:72:47:5B:E9", "test")
        t.connect()
        runCurrent()
        val old = gatt(t)
        val cb = shadowOf(old).gattCallback
        advanceTimeBy(60_000)
        cb.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED)
        t.connect()
        runCurrent()
        advanceTimeBy(30_001) // срок первой попытки истёк, новая ещё ждёт маячок
        assertEquals(LinkState.CONNECTING, t.state.value)
        cb.onConnectionStateChange(old, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(LinkState.CONNECTING, t.state.value)
        t.disconnect()
        advanceTimeBy(2_001)
        assertEquals(LinkState.DISCONNECTED, t.state.value)
    }
}
