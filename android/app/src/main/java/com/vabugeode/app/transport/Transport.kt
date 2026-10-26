package com.vabugeode.app.transport

import com.vabugeode.app.model.LinkState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Из какой характеристики пришёл пакет. */
enum class Channel { STATUS, EVENT }

class Packet(val channel: Channel, val data: ByteArray)

/**
 * Канал байтов до устройства. Протокол поверх него одинаковый,
 * меняется только реализация: виртуальное устройство или настоящий BLE.
 */
interface Transport {
    /** Имя устройства (для BLE — рекламируемое имя). */
    val deviceName: String

    /** Ключ для локальной базы: MAC гаджета или «virtual» */
    val key: String

    val state: StateFlow<LinkState>

    /** Уведомления от устройства (Status и Event). */
    val incoming: SharedFlow<Packet>

    fun connect()
    fun disconnect()

    /** Запись в характеристику Command. false — не отправлено (нет связи). */
    suspend fun send(data: ByteArray): Boolean
}

/** Гаджет ещё не выбран: ни подключиться, ни отправить. */
object NoTransport : Transport {
    override val deviceName = "Гаджет не выбран"
    override val key = "none"
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.DISCONNECTED)
    override val incoming: SharedFlow<Packet> = MutableSharedFlow()
    override fun connect() {}
    override fun disconnect() {}
    override suspend fun send(data: ByteArray) = false
}
