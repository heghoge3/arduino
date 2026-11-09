package com.vabugeode.app.transport
import android.Manifest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BleScannerTest {
    @Test fun firstLaunchWithoutBluetoothPermissionDoesNotTouchProtectedAdapter()=runTest {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
        val scanner=BleScanner(app,backgroundScope)
        scanner.start()
        assertFalse(com.vabugeode.app.ui.components.bluetoothOn(app))
        assertFalse(scanner.scanning.value)
        assertTrue(scanner.error.value!!.contains("разрешения"))
    }
    @Test fun approximateLocationAndNearbyDevicesAreEnoughOnAndroid14() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(com.vabugeode.app.ui.components.hasBlePermissions(app))
    }

}
