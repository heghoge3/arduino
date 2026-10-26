package com.vabugeode.app.data

import com.vabugeode.app.model.BlacklistPoint
import com.vabugeode.app.model.CheckLevel
import com.vabugeode.app.model.DeviceInfo
import com.vabugeode.app.model.DeviceStatus
import com.vabugeode.app.model.EnvState
import com.vabugeode.app.model.GpsFix
import com.vabugeode.app.model.GpsState
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.PowerMode
import com.vabugeode.app.model.PowerState
import com.vabugeode.app.model.Satellite
import com.vabugeode.app.model.SelfCheck
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.protocol.AckMsg
import com.vabugeode.app.protocol.AlertMsg
import com.vabugeode.app.protocol.CheckMsg
import com.vabugeode.app.protocol.Cmd
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.protocol.InfoMsg
import com.vabugeode.app.protocol.MarkMsg
import com.vabugeode.app.protocol.PointMsg
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.protocol.ProtocolException
import com.vabugeode.app.protocol.SatsMsg
import com.vabugeode.app.protocol.StatusMsg
import com.vabugeode.app.protocol.SyncDoneMsg
import com.vabugeode.app.protocol.toHex
import com.vabugeode.app.transport.Packet
import com.vabugeode.app.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Запись журнала обмена с устройством. */
data class LogEntry(
    val timeMs: Long,
    val outgoing: Boolean,
    val name: String,
    val size: Int,
    val hex: String,
    val error: Boolean = false,
)

/** Счётчики обмена. */
data class LinkStats(
    val packetsIn: Long = 0,
    val bytesIn: Long = 0,
    val packetsOut: Long = 0,
    val errors: Long = 0,
    val lastSeq: Int? = null,
    val lostStatus: Long = 0,
    val syncing: Boolean = false,
)

/**
 * Состояние устройства, собранное из пакетов протокола.
 * Не знает, откуда идут байты: виртуальное устройство или настоящий BLE.
 */
class ProtocolDeviceRepository(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val initialBlacklist: List<BlacklistPoint>,
) : DeviceRepository {

    override val link: StateFlow<LinkState> = transport.state
    override val status = MutableStateFlow<DeviceStatus?>(null)
    override val track = MutableStateFlow<List<TrackPoint>>(emptyList())
    override val marks = MutableStateFlow<List<Mark>>(emptyList())
    override val info = MutableStateFlow<DeviceInfo?>(null)
    override val blacklist = MutableStateFlow(initialBlacklist)
    override val hikeStartMs = MutableStateFlow<Long?>(null)

    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val stats = MutableStateFlow(LinkStats())

    /** Сообщения для пользователя (ошибки команд и т. п.). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices

    val deviceName: String get() = transport.deviceName

    // Трек и метки копим во внутренних структурах и публикуем пачками:
    // при синхронизации приходят тысячи точек подряд
    private val points = java.util.TreeMap<Long, TrackPoint>()
    private val markMap = java.util.TreeMap<Int, Mark>()
    private var dirty = false
    private var lastSats: SatsMsg? = null
    private var lastInfo: InfoMsg? = null
    private var hikeStartSec: Long? = null
    private var syncAfterInfo = false

    init {
        scope.launch { transport.incoming.collect { onPacket(it) } }
        scope.launch {
            transport.state.collect { st -> if (st == LinkState.CONNECTED) onConnected() }
        }
        // Самопроверка на устройстве идёт раз в минуту — INFO перезапрашиваем, чтобы не устаревал
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (link.value == LinkState.CONNECTED) send(Cmd.GetInfo)
            }
        }
        // «Телефон тут»: без команд 60 с устройство само рвёт связь (gadget-logic.md, 1.3)
        scope.launch {
            while (isActive) {
                delay(20_000)
                if (link.value == LinkState.CONNECTED && !stats.value.syncing) send(Cmd.Session(live = true))
            }
        }
        scope.launch {
            while (isActive) {
                delay(150)
                if (dirty) {
                    dirty = false
                    track.value = ArrayList(points.values)
                    marks.value = ArrayList(markMap.values)
                }
            }
        }
    }

    fun connect() = transport.connect()
    fun disconnect() = transport.disconnect()

    // ---- Команды ----

    // BLE не принимает параллельные записи — команды строго по очереди
    private val sendLock = Mutex()

    private fun send(cmd: Cmd) {
        scope.launch {
            val bytes = Codec.encode(cmd)
            if (sendLock.withLock { transport.send(bytes) }) {
                addLog(LogEntry(System.currentTimeMillis(), true, cmdName(bytes), bytes.size, bytes.toHex()))
                stats.value = stats.value.copy(packetsOut = stats.value.packetsOut + 1)
            } else {
                _notices.tryEmit("Устройство не подключено")
            }
        }
    }

    override fun addMark(type: MarkType, text: String) = send(Cmd.Mark(type.ordinal, text))
    override fun clap() = send(Cmd.Clap)
    override fun setRecording(on: Boolean) {
        send(Cmd.Record(on))
        send(Cmd.GetInfo) // мог начаться новый поход
    }
    override fun findDevice() = send(Cmd.Find)

    fun refreshInfo() = send(Cmd.GetInfo)

    private fun onConnected() {
        syncAfterInfo = true
        stats.value = stats.value.copy(lastSeq = null, syncing = false)
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
        send(Cmd.SetTime(now / 1000, tz))
        send(Cmd.Blacklist(blacklist.value.map { Cmd.Blacklist.Entry(it.lat, it.lon, it.radiusM) }))
        // Дальше — по ответу INFO: понять, тот же ли поход, и догрузить недостающее
        send(Cmd.GetInfo)
    }

    // ---- Входящие пакеты ----

    private fun onPacket(p: Packet) {
        val msg = try {
            Codec.decodeMsg(p.data)
        } catch (e: ProtocolException) {
            addLog(LogEntry(System.currentTimeMillis(), false, e.message ?: "ошибка", p.data.size, p.data.toHex(), error = true))
            stats.value = stats.value.copy(errors = stats.value.errors + 1)
            return
        }
        val s = stats.value
        stats.value = s.copy(packetsIn = s.packetsIn + 1, bytesIn = s.bytesIn + p.data.size)
        // STATUS и POINT идут потоком — в журнал пишем не каждый, иначе он забьётся
        val noisy = msg is PointMsg || (msg is StatusMsg && msg.seq % 10 != 0)
        if (!noisy) addLog(LogEntry(System.currentTimeMillis(), false, msgName(msg), p.data.size, p.data.toHex()))

        when (msg) {
            is StatusMsg -> onStatus(msg)
            is SatsMsg -> lastSats = msg
            is InfoMsg -> onInfo(msg)
            is MarkMsg -> {
                markMap[msg.id] = msg.toMark()
                dirty = true
            }
            is PointMsg -> {
                points[msg.timeUtc * 1000] = msg.toTrackPoint()
                dirty = true
            }
            is SyncDoneMsg -> {
                if (msg.what == Proto.CMD_SYNC_MARKS) stats.value = stats.value.copy(syncing = false)
            }
            is AlertMsg -> if (msg.fresh != 0) _notices.tryEmit("Тревога: ${alertText(msg.fresh)}")
            is AckMsg -> if (msg.result != Proto.ACK_OK) {
                _notices.tryEmit("Устройство отклонило ${cmdNameByCode(msg.cmd)}: ${ackText(msg.result)}")
            }
        }
    }

    private fun onInfo(m: InfoMsg) {
        lastInfo = m
        val newHike = m.hikeStartUtc != hikeStartSec
        if (newHike) {
            // Другой поход на устройстве — начинаем с чистого листа
            hikeStartSec = m.hikeStartUtc
            hikeStartMs.value = m.hikeStartUtc?.let { it * 1000 }
            points.clear()
            markMap.clear()
            dirty = true
        }
        // После подключения или смены похода догружаем только недостающее — с последней известной записи
        if (syncAfterInfo || newHike) {
            syncAfterInfo = false
            val sincePoint = points.lastEntry()?.key?.let { it / 1000 + 1 } ?: 0
            val sinceMark = markMap.values.maxOfOrNull { it.tsUtcMs }?.let { it / 1000 + 1 } ?: 0
            stats.value = stats.value.copy(syncing = true)
            send(Cmd.SyncTrack(sincePoint))
            send(Cmd.SyncMarks(sinceMark))
            // Живой сеанс: устройство начнёт слать STATUS раз в секунду (protocol.md, порядок подключения)
            send(Cmd.Session(live = true))
        }
        info.value = buildInfo(m, status.value)
    }

    private fun onStatus(m: StatusMsg) {
        val prev = stats.value.lastSeq
        if (prev != null) {
            val gap = ((m.seq - prev) and 0xFFFF) - 1
            if (gap in 1..1000) stats.value = stats.value.copy(lostStatus = stats.value.lostStatus + gap)
        }
        stats.value = stats.value.copy(lastSeq = m.seq)
        val st = m.toStatus(lastSats)
        status.value = st
        lastInfo?.let { info.value = buildInfo(it, st) }
    }

    private fun addLog(e: LogEntry) {
        val l = log.value
        log.value = (if (l.size >= 60) l.drop(l.size - 59) else l) + e
    }

    // ---- Перевод протокола в модель приложения ----

    private fun StatusMsg.toStatus(sats: SatsMsg?): DeviceStatus {
        val fixE = GpsFix.entries.getOrElse(fix) { GpsFix.OFF }
        val satellites = if (has(Proto.F_GPS_ON)) sats?.sats.orEmpty().map {
            Satellite(sysName(it.system), it.prn, it.snr, it.used)
        } else emptyList()
        return DeviceStatus(
            timeUtcMs = timeUtc * 1000,
            mode = PowerMode.entries.getOrElse(mode) { PowerMode.CAMP },
            recording = has(Proto.F_RECORDING),
            gps = GpsState(
                fix = fixE, satsUsed = satsUsed, satsInView = satsView, hdop = hdop ?: 99.9,
                speedKmh = speedKmh, courseDeg = courseDeg, altGps = altGps,
                satellites = satellites, spoof = has(Proto.F_SPOOF), spoofMask = spoofMask,
            ),
            env = EnvState(
                tempRaw = tempRaw ?: Double.NaN, tempCorr = tempCorr ?: Double.NaN,
                humidity = humidity, pressureHpa = pressureHpa,
                altBaro = altBaro ?: Double.NaN, pressureTrend3h = trend3h ?: 0.0,
            ),
            power = PowerState(
                voltage = battMv / 1000.0, currentMa = battMa, percent = battPct,
                charging = has(Proto.F_CHARGING), usedMah = usedMah.toDouble(), hoursLeft = hoursLeft,
            ),
            steps = steps.toInt(), headingDeg = headingDeg, distanceKm = distanceM / 1000.0,
            positionSource = srcOf(src), lat = lat, lon = lon,
        )
    }

    private fun PointMsg.toTrackPoint() = TrackPoint(
        tsUtcMs = timeUtc * 1000, lat = lat ?: Double.NaN, lon = lon ?: Double.NaN,
        altGps = altGps, sats = sats, hdop = hdop ?: 99.9, speedKmh = speedKmh,
        spoof = spoof, spoofMask = spoofMask, spoofLat = spoofLat, spoofLon = spoofLon,
        tempRaw = tempRaw ?: Double.NaN, tempCorr = tempCorr ?: Double.NaN, humidity = humidity,
        pressureHpa = pressureHpa, altBaro = altBaro ?: Double.NaN,
        battV = battMv / 1000.0, battMa = 0.0, battPercent = battPct,
        steps = steps.toInt(), headingDeg = headingDeg, src = srcOf(src), markId = null,
    )

    private fun MarkMsg.toMark() = Mark(
        id = id, tsUtcMs = timeUtc * 1000,
        type = MarkType.entries.getOrElse(type) { MarkType.MARK },
        text = text, lat = lat, lon = lon,
    )

    private fun buildInfo(m: InfoMsg, st: DeviceStatus?): DeviceInfo {
        val systems = listOf("GPS", "BeiDou", "ГЛОНАСС", "Galileo").filterIndexed { i, _ -> m.gnssMask and (1 shl i) != 0 }
        return DeviceInfo(
            name = transport.deviceName,
            chipModel = when (m.chip) { 1 -> "ESP32-S3"; 2 -> "ESP32-C3"; else -> "чип неизвестен" },
            firmware = "${m.fwMajor}.${m.fwMinor}.${m.fwPatch}",
            uptimeSec = m.uptimeSec,
            sdTotalMb = m.sdTotalMb.toInt(), sdFreeMb = m.sdFreeMb.toInt(),
            gnssSystems = systems.joinToString(" + ").ifEmpty { "—" },
            checks = m.checks.map { checkText(it, st, m) },
        )
    }

    companion object {
        private fun sysName(s: Int) = when (s) { 0 -> "GPS"; 1 -> "BDS"; 2 -> "GLO"; else -> "GAL" }

        private fun srcOf(s: Int) = when (s) { 1 -> PositionSource.GPS; 2 -> PositionSource.PDR; else -> PositionSource.NONE }

        private fun f(fmt: String, vararg a: Any) = String.format(Locale("ru"), fmt, *a)

        /** Текст проверки: по id и коду причины + живые значения из STATUS. */
        fun checkText(c: CheckMsg, st: DeviceStatus?, info: InfoMsg): SelfCheck {
            val level = when (c.level) { 0 -> CheckLevel.OK; 1 -> CheckLevel.WARN; else -> CheckLevel.ERROR }
            val name = when (c.id) {
                1 -> "Экран SSD1306"
                2 -> "Питание INA219"
                3 -> "Акселерометр MPU6050"
                4 -> "Компас QMC5883L"
                5 -> "Климат BME280"
                6 -> "Карта SD"
                7 -> "GPS ATGM336H"
                8 -> "Аккумулятор"
                9 -> "Часы DS1302"
                else -> "Узел #${c.id}"
            }
            val okDetail = when (c.id) {
                1 -> "Отвечает"
                2 -> st?.let { f("%.2f В, %.0f мА", it.power.voltage, it.power.currentMa) }
                3 -> st?.let { "Шаги: ${it.steps}" }
                4 -> "Откалиброван"
                5 -> st?.let { f("chip id 0x60, %.1f° / %.0f гПа", it.env.tempRaw, it.env.pressureHpa) }
                6 -> f("FAT32, свободно %.1f ГБ", info.sdFreeMb / 1024.0)
                7 -> st?.let { f("NMEA идёт, %d спутн., HDOP %.1f", it.gps.satsUsed, it.gps.hdop) }
                8 -> st?.let { f("%d %%, хватит на ~%.0f ч", it.power.percent, it.power.hoursLeft) }
                9 -> "Идут, время сверено"
                else -> null
            } ?: "Отвечает"
            val detail = when (c.code) {
                0 -> okDetail
                1 -> "Не найден на шине — проверьте провода и питание"
                2 -> "Неверный chip id — возможно, другой датчик (BMP280?)"
                3 -> "Не откалиброван — откалибруйте вращением"
                4 -> "Нет данных NMEA — проверьте TX/RX и питание"
                5 -> "NMEA идёт, но подозрение на подмену"
                6 -> "Ищет спутники${st?.let { " (${it.gps.satsInView} видно)" } ?: ""}"
                7 -> "Выключен — стоянка, экономия заряда"
                8 -> "Низкий заряд${st?.let { " — ${it.power.percent} %" } ?: ""}"
                9 -> "Карта не вставлена или не читается"
                10 -> "Мало места на карте"
                11 -> "Часы стояли (села батарейка?) — время не проверено, ждём телефон или GPS"
                else -> "Код ${c.code}"
            }
            val addr = if (c.addr != 0) "0x%02X".format(c.addr) else when (c.id) { 6 -> "SPI"; 7 -> "UART"; 9 -> "3-wire"; else -> null }
            return SelfCheck(name, detail, level, addr)
        }

        fun msgName(m: Any): String = when (m) {
            is StatusMsg -> "STATUS #${m.seq}"
            is SatsMsg -> "SATS ×${m.sats.size}"
            is InfoMsg -> "INFO"
            is MarkMsg -> "MARK #${m.id}"
            is PointMsg -> "POINT"
            is SyncDoneMsg -> "SYNC_DONE ${if (m.what == Proto.CMD_SYNC_TRACK) "трек" else "метки"}: ${m.count}"
            is AckMsg -> "ACK ${cmdNameByCode(m.cmd)} → ${ackText(m.result)}"
            is AlertMsg -> "ALERT ${alertText(m.alerts)}"
            else -> "?"
        }

        fun cmdName(bytes: ByteArray) = cmdNameByCode(Codec.cmdCode(bytes))

        fun cmdNameByCode(code: Int) = when (code) {
            Proto.CMD_SET_TIME -> "SET_TIME"
            Proto.CMD_PHONE_POS -> "PHONE_POS"
            Proto.CMD_RECORD -> "RECORD"
            Proto.CMD_MARK -> "MARK"
            Proto.CMD_CLAP -> "CLAP"
            Proto.CMD_FIND -> "FIND"
            Proto.CMD_SET_INTERVALS -> "SET_INTERVALS"
            Proto.CMD_CALIB_ALT -> "CALIB_ALT"
            Proto.CMD_CALIB_COMPASS -> "CALIB_COMPASS"
            Proto.CMD_BLACKLIST -> "BLACKLIST"
            Proto.CMD_GET_INFO -> "GET_INFO"
            Proto.CMD_SYNC_TRACK -> "SYNC_TRACK"
            Proto.CMD_SYNC_MARKS -> "SYNC_MARKS"
            Proto.CMD_ACK_ALERT -> "ACK_ALERT"
            Proto.CMD_SET_RADIO -> "SET_RADIO"
            Proto.CMD_SET_GPS_MODE -> "SET_GPS_MODE"
            Proto.CMD_BYE -> "BYE"
            Proto.CMD_SESSION -> "SESSION"
            else -> "0x%02X".format(code)
        }

        fun alertText(mask: Int) = buildList {
            if (mask and Proto.AL_SPOOF != 0) add("подмена GPS")
            if (mask and Proto.AL_LOW_BATT != 0) add("низкий заряд")
            if (mask and Proto.AL_CRIT_BATT != 0) add("критический заряд")
            if (mask and Proto.AL_SD_ERROR != 0) add("ошибка SD")
        }.joinToString(", ").ifEmpty { "нет" }

        fun ackText(r: Int) = when (r) {
            Proto.ACK_OK -> "OK"
            Proto.ACK_BAD_ARGS -> "неверные аргументы"
            Proto.ACK_BUSY -> "занято"
            Proto.ACK_UNSUPPORTED -> "не поддерживается"
            Proto.ACK_NOT_BONDED -> "нет сопряжения"
            else -> "код $r"
        }
    }
}
