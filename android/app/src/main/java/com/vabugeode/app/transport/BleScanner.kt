package com.vabugeode.app.transport

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Поиск маячков vabuGEOde по данным производителя (company id 0xFFFF, первые байты «версия, 'V'»).
 * Фильтр отдаётся Bluetooth-чипу — телефон не будится на чужие устройства.
 * Разрешения (BLUETOOTH_SCAN, геолокация) проверяет вызывающий код.
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    // На экране — LOW_LATENCY (ловить короткие маячки 300 мс); в фоне — BALANCED (дешевле для телефона)
    private val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
) : GadgetScanner {

    override val found = MutableStateFlow<Map<String, FoundGadget>>(emptyMap())
    override val scanning = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)

    private var cleaner: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)

        override fun onScanFailed(errorCode: Int) {
            // 1 уже идёт, 2 стек не смог, 4 нет функции, 6 слишком частые запуски (Android режет > 5 за 30 с)
            AppLog.e("SCAN", "поиск не запустился, код $errorCode")
            scanning.value = false
            error.value = "Поиск не запустился (код $errorCode)"
        }
    }

    private fun onResult(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(Proto.COMPANY_ID) ?: return
        val beacon = Codec.decodeBeacon(data) ?: return
        val id = r.device.address
        val name = r.scanRecord?.deviceName ?: "vabuGEOde"
        found.value = found.value + (id to FoundGadget(id, name, r.rssi, beacon, System.currentTimeMillis()))
    }

    override fun start() {
        if (scanning.value) return
        if (Build.VERSION.SDK_INT>=31 && listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            .any { ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED }) {
            error.value="Нет разрешения на поиск Bluetooth"; return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            error.value = "В телефоне нет Bluetooth"
            return
        }
        if (!adapter.isEnabled) {
            error.value = "Bluetooth выключен"
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            error.value = "Bluetooth не готов"
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                Proto.COMPANY_ID,
                byteArrayOf(Proto.VERSION.toByte(), Proto.BEACON_TAG.toByte()),
                byteArrayOf(-1, -1),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            error.value = "Нет разрешения на поиск Bluetooth"
            return
        }
        error.value = null
        scanning.value = true
        AppLog.d("SCAN", "поиск запущен, режим $scanMode")
        // Не слышно дольше 2 мин — убираем из списка
        cleaner = scope.launch {
            while (isActive) {
                delay(10_000)
                val cutoff = System.currentTimeMillis() - 120_000
                found.value = found.value.filterValues { it.seenAtMs > cutoff }
            }
        }
    }

    override fun stop() {
        cleaner?.cancel()
        cleaner = null
        if (!scanning.value) return
        scanning.value = false
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Bluetooth выключили во время поиска
        }
    }
}
