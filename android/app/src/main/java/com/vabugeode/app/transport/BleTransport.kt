package com.vabugeode.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.Proto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Настоящий гаджет по Bluetooth LE (docs/protocol.md, «GATT» и «Порядок при подключении»).
 * Подключение → MTU 247 → поиск сервиса → подписка на Status и Event → CONNECTED.
 * Сопряжение: команды пишутся только по шифрованной связи; первый раз Android покажет
 * запрос сопряжения — на гаджете перед этим нужно долго нажать B (окно сопряжения).
 *
 * На реальном железе не проверено.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    val address: String,
    override val deviceName: String,
) : Transport {

    override val state = MutableStateFlow(LinkState.DISCONNECTED)
    override val incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 8192)

    /** Последняя ошибка для человека (почему не подключилось) */
    val lastError = MutableStateFlow<String?>(null)

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var setupJob: Job? = null

    // GATT в Android выполняет одну операцию за раз: следующая — только после ответа на предыдущую
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                setupJob = scope.launch { setup(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (state.value == LinkState.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) {
                    lastError.value = "Гаджет не ответил (код $status)"
                }
                cleanup()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(status)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status)

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onNotify(c.uuid, value)

        // Android 12 и старше
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onNotify(c.uuid, c.value ?: return)
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        val ch = when (uuid) {
            Proto.CHAR_STATUS -> Channel.STATUS
            Proto.CHAR_EVENT -> Channel.EVENT
            else -> return
        }
        incoming.tryEmit(Packet(ch, value.copyOf()))
    }

    private fun complete(status: Int) {
        pending?.complete(status)
    }

    /** Выполнить одну GATT-операцию и дождаться ответа. Возвращает статус GATT. */
    private suspend fun op(timeoutMs: Long = 5000, start: () -> Boolean): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        if (!start()) {
            pending = null
            return BluetoothGatt.GATT_FAILURE
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: TIMEOUT
        pending = null
        r
    }

    private suspend fun setup(g: BluetoothGatt) {
        // Сначала MTU: самый длинный пакет 99 байт, при MTU 23 не пролезет
        op { g.requestMtu(Proto.MTU) }
        if (op(10_000) { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            fail(g, "Не удалось прочитать сервисы гаджета")
            return
        }
        val svc = g.getService(Proto.SERVICE)
        if (svc == null) {
            fail(g, "Это не vabuGEOde (нет нашего сервиса)")
            return
        }
        cmdChar = svc.getCharacteristic(Proto.CHAR_COMMAND)
        for (uuid in listOf(Proto.CHAR_STATUS, Proto.CHAR_EVENT)) {
            val c = svc.getCharacteristic(uuid) ?: run {
                fail(g, "Не та версия прошивки (нет характеристики)")
                return
            }
            if (!enableNotify(g, c)) {
                fail(g, "Не удалось подписаться на данные")
                return
            }
        }
        lastError.value = null
        state.value = LinkState.CONNECTED
    }

    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val st = op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        return st == BluetoothGatt.GATT_SUCCESS
    }

    private fun fail(g: BluetoothGatt, why: String) {
        lastError.value = why
        g.disconnect()
    }

    private fun cleanup() {
        setupJob?.cancel()
        pending?.complete(BluetoothGatt.GATT_FAILURE)
        gatt?.close()
        gatt = null
        cmdChar = null
        state.value = LinkState.DISCONNECTED
    }

    override fun connect() {
        if (state.value != LinkState.DISCONNECTED) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            lastError.value = "Bluetooth выключен"
            return
        }
        state.value = LinkState.CONNECTING
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            lastError.value = "Нет разрешения на подключение Bluetooth"
            state.value = LinkState.DISCONNECTED
            return
        } catch (e: IllegalArgumentException) {
            lastError.value = "Неверный адрес гаджета"
            state.value = LinkState.DISCONNECTED
            return
        }
        // Гаджет мог уснуть между маячками — не висим вечно
        scope.launch {
            delay(15_000)
            if (state.value == LinkState.CONNECTING) {
                lastError.value = "Гаджет не ответил за 15 с"
                gatt?.disconnect()
                cleanup()
            }
        }
    }

    override fun disconnect() {
        val g = gatt ?: return
        g.disconnect()
        // Если система не пришлёт «отключено» — закрываем сами
        scope.launch {
            delay(2000)
            if (gatt === g) cleanup()
        }
    }

    override suspend fun send(data: ByteArray): Boolean {
        if (state.value != LinkState.CONNECTED) return false
        val g = gatt ?: return false
        val c = cmdChar ?: return false
        var st = write(g, c, data)
        if (st in AUTH_ERRORS) {
            // Команды — только по шифрованной связи: ждём, пока Android проведёт сопряжение, и пробуем снова
            lastError.value = "Подтвердите сопряжение на телефоне (на гаджете — долгое нажатие B)"
            val bonded = withTimeoutOrNull(30_000) {
                while (g.device.bondState != BluetoothDevice.BOND_BONDED) delay(500)
                true
            } ?: false
            if (!bonded) return false
            lastError.value = null
            st = write(g, c, data)
        }
        return st == BluetoothGatt.GATT_SUCCESS
    }

    private suspend fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Int = op {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT = -1
        // Нет шифрования/сопряжения (5, 15) и типичная ошибка Android при сопряжении (137)
        private val AUTH_ERRORS = setOf(5, 15, 137)
    }
}
