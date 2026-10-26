package com.vabugeode.app.transport

import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.AckMsg
import com.vabugeode.app.protocol.Cmd
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.protocol.DeviceMsg
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.protocol.ProtocolException
import com.vabugeode.app.protocol.SyncDoneMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Виртуальное устройство «по ту сторону BLE». Делает то же, что будет делать
 * BLE-сервер в прошивке: шлёт STATUS раз в секунду, SATS раз в 5 с, новые POINT/MARK
 * по мере записи, отвечает на команды ACK-ами и синхронизацией.
 * Все данные проходят через байтовый кодек — как по радио.
 */
class VirtualTransport(
    private val scope: CoroutineScope,
    private val device: DemoDevice,
) : Transport {

    override val deviceName = "vabuGEOde-01 (виртуальное)"
    override val key = VIRTUAL_ID
    override val state = MutableStateFlow(LinkState.DISCONNECTED)
    override val incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 8192)

    private var session: Job? = null
    /** Живой сеанс: STATUS и SATS шлются только после SESSION live — как в прошивке. */
    private var live = false
    private var seq = 0
    private var sentPoints = 0
    private var sentMarks = 0

    init {
        // Новый поход на устройстве (перезапуск демо) — как перезагрузка платы: связь рвётся
        scope.launch {
            device.restarts.drop(1).collect {
                if (state.value == LinkState.CONNECTED) {
                    disconnect()
                    delay(800)
                    connect()
                }
            }
        }
    }

    override fun connect() {
        if (session != null) return
        state.value = LinkState.CONNECTING
        session = scope.launch {
            delay(700) // поиск и подключение
            // Новые записи шлём только после подключения; старое телефон заберёт синхронизацией
            sentPoints = device.pointCount
            sentMarks = device.markCount
            live = false
            state.value = LinkState.CONNECTED
            var tick = 0
            while (isActive) {
                if (live) {
                    emit(Channel.STATUS, device.statusMsg(seq++))
                    if (tick % 5 == 0) emit(Channel.STATUS, device.satsMsg())
                }
                pushNew()
                tick++
                delay(1000)
            }
        }
    }

    override fun disconnect() {
        session?.cancel()
        session = null
        state.value = LinkState.DISCONNECTED
    }

    override suspend fun send(data: ByteArray): Boolean {
        if (state.value != LinkState.CONNECTED) return false
        delay(30) // задержка радио
        val code = Codec.cmdCode(data)
        val cmd = try {
            Codec.decodeCmd(data)
        } catch (e: ProtocolException) {
            emit(Channel.EVENT, AckMsg(code, Proto.ACK_BAD_ARGS))
            return true
        }
        val result = device.handle(cmd)
        emit(Channel.EVENT, AckMsg(code, result))
        when (cmd) {
            Cmd.GetInfo -> emit(Channel.EVENT, device.infoMsg())
            is Cmd.SyncTrack -> {
                val from = device.firstPointSince(cmd.sinceUtc)
                val to = device.pointCount
                for (i in from until to) emit(Channel.EVENT, device.pointMsg(i))
                emit(Channel.EVENT, SyncDoneMsg(Proto.CMD_SYNC_TRACK, (to - from).toLong()))
                sentPoints = maxOf(sentPoints, to)
            }
            is Cmd.SyncMarks -> {
                val from = device.firstMarkSince(cmd.sinceUtc)
                val to = device.markCount
                for (i in from until to) emit(Channel.EVENT, device.markMsg(i))
                emit(Channel.EVENT, SyncDoneMsg(Proto.CMD_SYNC_MARKS, (to - from).toLong()))
                sentMarks = maxOf(sentMarks, to)
            }
            is Cmd.Session -> live = cmd.live
            Cmd.Bye -> disconnect()
            else -> pushNew() // метка по команде появится сразу
        }
        return true
    }

    /** Новые точки и метки, записанные с прошлого раза. */
    private suspend fun pushNew() {
        while (sentPoints < device.pointCount) emit(Channel.EVENT, device.pointMsg(sentPoints++))
        while (sentMarks < device.markCount) emit(Channel.EVENT, device.markMsg(sentMarks++))
    }

    private suspend fun emit(ch: Channel, m: DeviceMsg) {
        incoming.emit(Packet(ch, Codec.encode(m)))
    }
}
