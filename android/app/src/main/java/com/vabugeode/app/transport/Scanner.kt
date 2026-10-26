package com.vabugeode.app.transport

import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.protocol.BeaconMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Устройство, услышанное по маячку. id — MAC-адрес (или «virtual» для эмулятора). */
data class FoundGadget(
    val id: String,
    val name: String,
    val rssi: Int?,
    val beacon: BeaconMsg,
    val seenAtMs: Long,
)

/** Слушает маячки (docs/protocol.md, «Маячок»). Сводка видна без подключения. */
interface GadgetScanner {
    val found: StateFlow<Map<String, FoundGadget>>
    val scanning: StateFlow<Boolean>
    /** Ошибка запуска (нет разрешений, выключен Bluetooth) — текст для человека */
    val error: StateFlow<String?>
    fun start()
    fun stop()
}

const val VIRTUAL_ID = "virtual"

/** Маячки эмулятора: раз в 3 с, пока к нему не подключены (как настоящий гаджет в эфире). */
class VirtualScanner(
    private val scope: CoroutineScope,
    private val device: DemoDevice,
    private val isConnected: () -> Boolean,
) : GadgetScanner {
    override val found = MutableStateFlow<Map<String, FoundGadget>>(emptyMap())
    override val scanning = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    override fun start() {
        if (job != null) return
        scanning.value = true
        job = scope.launch {
            while (isActive) {
                if (!isConnected()) {
                    val now = System.currentTimeMillis()
                    found.value = mapOf(
                        VIRTUAL_ID to FoundGadget(VIRTUAL_ID, "vabuGEOde (эмулятор)", -55, device.beaconMsg(), now)
                    )
                }
                delay(3000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        scanning.value = false
    }
}
