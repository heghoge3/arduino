package com.vabugeode.app.transport

import com.vabugeode.app.model.LinkState
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

    val state: StateFlow<LinkState>

    /** Уведомления от устройства (Status и Event). */
    val incoming: SharedFlow<Packet>

    fun connect()
    fun disconnect()

    /** Запись в характеристику Command. false — не отправлено (нет связи). */
    suspend fun send(data: ByteArray): Boolean
}
