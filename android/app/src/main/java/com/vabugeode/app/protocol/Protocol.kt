package com.vabugeode.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Протокол BLE v2 — точное отражение docs/protocol.md.
 * Здесь обе стороны: телефон (кодирует команды, разбирает сообщения)
 * и устройство (наоборот) — вторая нужна виртуальному устройству и тестам.
 */
object Proto {
    const val VERSION = 2

    val SERVICE: UUID = UUID.fromString("e0f30001-5642-4744-8a1e-76616275676f")
    val CHAR_STATUS: UUID = UUID.fromString("e0f30002-5642-4744-8a1e-76616275676f")
    val CHAR_COMMAND: UUID = UUID.fromString("e0f30003-5642-4744-8a1e-76616275676f")
    val CHAR_EVENT: UUID = UUID.fromString("e0f30004-5642-4744-8a1e-76616275676f")

    const val MTU = 247

    // Сообщения устройства
    const val MSG_STATUS = 0x01
    const val MSG_SATS = 0x02
    const val MSG_INFO = 0x03
    const val MSG_MARK = 0x04
    const val MSG_POINT = 0x05
    const val MSG_SYNC_DONE = 0x06
    const val MSG_ACK = 0x07
    const val MSG_ALERT = 0x08
    const val MSG_LOG = 0x09
    const val MSG_HIKE_RESULT = 0x0A

    // Команды телефона
    const val CMD_SET_TIME = 0x01
    const val CMD_PHONE_POS = 0x02
    const val CMD_RECORD = 0x03
    const val CMD_MARK = 0x04
    const val CMD_CLAP = 0x05
    const val CMD_FIND = 0x06
    const val CMD_SET_INTERVALS = 0x07
    const val CMD_CALIB_ALT = 0x08
    const val CMD_CALIB_COMPASS = 0x09
    const val CMD_BLACKLIST = 0x0A
    const val CMD_GET_INFO = 0x0B
    const val CMD_SESSION = 0x0C
    const val CMD_BYE = 0x0D
    const val CMD_SET_GPS_MODE = 0x0E
    const val CMD_SET_RADIO = 0x0F
    const val CMD_SYNC_TRACK = 0x10
    const val CMD_SYNC_MARKS = 0x11
    const val CMD_ACK_ALERT = 0x12
    const val CMD_SYNC_LOG = 0x13
    const val CMD_SET_DEBUG = 0x14
    const val CMD_NEW_HIKE = 0x15
    const val CMD_DELETE_HIKE = 0x16
    const val CMD_PDR_SETTINGS = 0x17

    // Результаты ACK
    const val ACK_OK = 0
    const val ACK_BAD_ARGS = 1
    const val ACK_BUSY = 2
    const val ACK_UNSUPPORTED = 3
    const val ACK_NOT_BONDED = 4

    // Флаги STATUS
    const val F_RECORDING = 1 shl 0
    const val F_GPS_ON = 1 shl 1
    const val F_SPOOF = 1 shl 2
    const val F_TIME_SYNCED = 1 shl 3
    const val F_CHARGING = 1 shl 4
    const val F_SD_OK = 1 shl 5
    const val F_LOW_BATT = 1 shl 6
    const val F_ALERT = 1 shl 7

    // Тревоги (байт alerts)
    const val AL_SPOOF = 1 shl 0
    const val AL_LOW_BATT = 1 shl 1
    const val AL_CRIT_BATT = 1 shl 2
    const val AL_SD_ERROR = 1 shl 3

    // Маячок
    const val COMPANY_ID = 0xFFFF
    const val BEACON_TAG = 0x56
    const val BEACON_SIZE = 18
    const val B_RECORDING = 1 shl 0
    const val B_FIX = 1 shl 1
    const val B_SPOOF = 1 shl 2
    const val B_TIME_OK = 1 shl 3
    const val B_WINDOW = 1 shl 4
    const val B_SD_OK = 1 shl 5

    const val NA16: Int = -32768
    const val HDOP_NA = 255
    const val MAX_TEXT = 60
    const val MAX_LOG_TEXT = 80
    const val MAX_SATS = 24
    const val MAX_BLACKLIST = 24

    const val STATUS_SIZE = 64
    const val POINT_SIZE = 51
}

/** Ошибка разбора пакета: неверная версия, длина или тип. */
class ProtocolException(msg: String) : Exception(msg)

// ---------------------------------------------------------------------------
// Сообщения устройства → телефон
// ---------------------------------------------------------------------------

sealed interface DeviceMsg

data class StatusMsg(
    val timeUtc: Long,
    val mode: Int,
    val flags: Int,
    val fix: Int,
    val satsUsed: Int,
    val satsView: Int,
    val hdop: Double?,
    val lat: Double?,
    val lon: Double?,
    val src: Int,
    val spoofMask: Int,
    val altGps: Double?,
    val speedKmh: Double,
    val courseDeg: Double,
    val tempRaw: Double?,
    val tempCorr: Double?,
    val humidity: Double,
    val pressureHpa: Double,
    val altBaro: Double?,
    val trend3h: Double?,
    val battMv: Int,
    val battMa: Double,
    val battPct: Int,
    val usedMah: Int,
    val steps: Long,
    val headingDeg: Double,
    val distanceM: Long,
    val hoursLeft: Double,
    val seq: Int,
    val alerts: Int = 0,
) : DeviceMsg {
    fun has(flag: Int) = flags and flag != 0
}

data class SatMsg(val system: Int, val prn: Int, val snr: Int, val used: Boolean)
data class SatsMsg(val sats: List<SatMsg>) : DeviceMsg

data class CheckMsg(val id: Int, val level: Int, val code: Int, val addr: Int)

data class InfoMsg(
    val fwMajor: Int,
    val fwMinor: Int,
    val fwPatch: Int,
    val chip: Int,
    val uptimeSec: Long,
    val sdTotalMb: Long,
    val sdFreeMb: Long,
    val gnssMask: Int,
    val hikeStartUtc: Long?,
    val checks: List<CheckMsg>,
    val gpsMode: Int = 0,
    val gpsPulseS: Int = 30,
    val beaconS: Int = 30,
) : DeviceMsg

data class MarkMsg(
    val id: Int,
    val timeUtc: Long,
    val type: Int,
    val lat: Double?,
    val lon: Double?,
    val text: String,
) : DeviceMsg

data class PointMsg(
    val timeUtc: Long,
    val lat: Double?,
    val lon: Double?,
    val src: Int,
    val spoof: Boolean,
    val spoofMask: Int,
    val spoofLat: Double?,
    val spoofLon: Double?,
    val tempRaw: Double?,
    val tempCorr: Double?,
    val humidity: Double,
    val pressureHpa: Double,
    val altBaro: Double?,
    val altGps: Double?,
    val battMv: Int,
    val battPct: Int,
    val sats: Int,
    val hdop: Double?,
    val steps: Long,
    val headingDeg: Double,
    val speedKmh: Double,
) : DeviceMsg

data class SyncDoneMsg(val what: Int, val count: Long) : DeviceMsg
data class AckMsg(val cmd: Int, val result: Int) : DeviceMsg
data class AlertMsg(val timeUtc: Long, val alerts: Int, val fresh: Int, val battPct: Int, val spoofMask: Int) : DeviceMsg

/** Строка журнала гаджета. level: 0 ошибка, 1 внимание, 2 инфо, 3 отладка; timeUtc 0 — время неизвестно */
data class LogMsg(
    val seq: Long,
    val timeUtc: Long,
    val uptimeSec: Long,
    val level: Int,
    val timeOk: Boolean,
    val module: Int,
    val text: String,
) : DeviceMsg

/** Сводка маячка (данные производителя в рекламе, без company id). Координат нет намеренно. */
data class BeaconMsg(
    val mode: Int,
    val flags: Int,
    val alerts: Int,
    val tempCorr: Double?,
    val battPct: Int,
    val satsUsed: Int,
    val pressureHpa: Double?,
    val lastRecordUtc: Long,
    val hikeId: Int,
    val counter: Int,
)

// ---------------------------------------------------------------------------
// Команды телефона → устройство
// ---------------------------------------------------------------------------

data class HikeResultMsg(val cmd: Int, val result: Int, val hike: Long) : DeviceMsg

sealed interface Cmd {
    data class NewHike(val expectedHike: Long) : Cmd
    data class DeleteHike(val hike: Long) : Cmd
    data class SetTime(val timeUtc: Long, val tzOffsetMin: Int) : Cmd
    data class PhonePos(val lat: Double, val lon: Double, val accuracyM: Int) : Cmd
    data class Record(val on: Boolean) : Cmd
    data class Mark(val type: Int, val text: String) : Cmd
    data object Clap : Cmd
    data object Find : Cmd
    data class SetIntervals(val movingSec: Int, val idleSec: Int) : Cmd
    data class CalibAlt(val altM: Double) : Cmd
    data class CalibCompass(val start: Boolean) : Cmd
    data class PdrSettings(val strideCm: Int, val headingOffset10: Int, val inverted: Boolean) : Cmd
    data class Blacklist(val points: List<Entry>) : Cmd {
        data class Entry(val lat: Double, val lon: Double, val radiusM: Int)
    }
    data object GetInfo : Cmd
    data class Session(val live: Boolean) : Cmd
    data object Bye : Cmd
    data class SetGpsMode(val pulse: Boolean, val pulseS: Int) : Cmd
    data class SetRadio(val beaconS: Int) : Cmd
    data class AckAlert(val mask: Int) : Cmd
    data class SyncTrack(val sinceUtc: Long) : Cmd
    data class SyncMarks(val sinceUtc: Long) : Cmd
    data class SyncLog(val sinceSeq: Long) : Cmd
    data class SetDebug(val on: Boolean) : Cmd
}

// ---------------------------------------------------------------------------
// Кодирование / разбор
// ---------------------------------------------------------------------------

object Codec {

    // ---- Телефон: команды → байты ----

    fun encode(cmd: Cmd): ByteArray = when (cmd) {
        is Cmd.NewHike -> packet(Proto.CMD_NEW_HIKE, 4) { u32(cmd.expectedHike) }
        is Cmd.DeleteHike -> packet(Proto.CMD_DELETE_HIKE, 4) { u32(cmd.hike) }
        is Cmd.SetTime -> packet(Proto.CMD_SET_TIME, 6) { u32(cmd.timeUtc); i16(cmd.tzOffsetMin) }
        is Cmd.PhonePos -> packet(Proto.CMD_PHONE_POS, 10) { coord(cmd.lat); coord(cmd.lon); u16(cmd.accuracyM) }
        is Cmd.Record -> packet(Proto.CMD_RECORD, 1) { u8(if (cmd.on) 1 else 0) }
        is Cmd.Mark -> {
            val t = utf8Limited(cmd.text)
            packet(Proto.CMD_MARK, 2 + t.size) { u8(cmd.type); u8(t.size); bytes(t) }
        }
        Cmd.Clap -> packet(Proto.CMD_CLAP, 0) {}
        Cmd.Find -> packet(Proto.CMD_FIND, 0) {}
        is Cmd.SetIntervals -> packet(Proto.CMD_SET_INTERVALS, 4) { u16(cmd.movingSec); u16(cmd.idleSec) }
        is Cmd.CalibAlt -> packet(Proto.CMD_CALIB_ALT, 2) { i16(scaled(cmd.altM, 10.0)) }
        is Cmd.PdrSettings -> packet(Proto.CMD_PDR_SETTINGS, 5) { u16(cmd.strideCm); i16(cmd.headingOffset10); u8(if (cmd.inverted) 1 else 0) }
        is Cmd.CalibCompass -> packet(Proto.CMD_CALIB_COMPASS, 1) { u8(if (cmd.start) 1 else 0) }
        is Cmd.Blacklist -> {
            require(cmd.points.size <= Proto.MAX_BLACKLIST) { "Чёрный список: не больше ${Proto.MAX_BLACKLIST} точек" }
            packet(Proto.CMD_BLACKLIST, 1 + 10 * cmd.points.size) {
                u8(cmd.points.size)
                cmd.points.forEach { coord(it.lat); coord(it.lon); u16(it.radiusM) }
            }
        }
        Cmd.GetInfo -> packet(Proto.CMD_GET_INFO, 0) {}
        is Cmd.Session -> packet(Proto.CMD_SESSION, 1) { u8(if (cmd.live) 1 else 0) }
        Cmd.Bye -> packet(Proto.CMD_BYE, 0) {}
        is Cmd.SetGpsMode -> packet(Proto.CMD_SET_GPS_MODE, 3) { u8(if (cmd.pulse) 1 else 0); u16(cmd.pulseS) }
        is Cmd.SetRadio -> packet(Proto.CMD_SET_RADIO, 2) { u16(cmd.beaconS) }
        is Cmd.AckAlert -> packet(Proto.CMD_ACK_ALERT, 1) { u8(cmd.mask) }
        is Cmd.SyncTrack -> packet(Proto.CMD_SYNC_TRACK, 4) { u32(cmd.sinceUtc) }
        is Cmd.SyncMarks -> packet(Proto.CMD_SYNC_MARKS, 4) { u32(cmd.sinceUtc) }
        is Cmd.SyncLog -> packet(Proto.CMD_SYNC_LOG, 4) { u32(cmd.sinceSeq) }
        is Cmd.SetDebug -> packet(Proto.CMD_SET_DEBUG, 1) { u8(if (cmd.on) 1 else 0) }
    }

    // ---- Устройство: байты → команда ----

    fun decodeCmd(data: ByteArray): Cmd {
        val r = reader(data)
        return when (val code = r.u8()) {
            Proto.CMD_NEW_HIKE -> Cmd.NewHike(r.u32())
            Proto.CMD_DELETE_HIKE -> Cmd.DeleteHike(r.u32())
            Proto.CMD_SET_TIME -> Cmd.SetTime(r.u32(), r.i16())
            Proto.CMD_PHONE_POS -> Cmd.PhonePos(r.coordRaw(), r.coordRaw(), r.u16())
            Proto.CMD_RECORD -> Cmd.Record(r.u8() != 0)
            Proto.CMD_MARK -> {
                val type = r.u8()
                val len = r.u8()
                if (len > Proto.MAX_TEXT) throw ProtocolException("Текст метки длиннее ${Proto.MAX_TEXT} байт")
                Cmd.Mark(type, r.string(len))
            }
            Proto.CMD_CLAP -> Cmd.Clap
            Proto.CMD_FIND -> Cmd.Find
            Proto.CMD_SET_INTERVALS -> Cmd.SetIntervals(r.u16(), r.u16())
            Proto.CMD_CALIB_ALT -> Cmd.CalibAlt(r.i16() / 10.0)
            Proto.CMD_PDR_SETTINGS -> Cmd.PdrSettings(r.u16(), r.i16(), r.u8() != 0)
            Proto.CMD_CALIB_COMPASS -> Cmd.CalibCompass(r.u8() != 0)
            Proto.CMD_BLACKLIST -> {
                val n = r.u8()
                if (n > Proto.MAX_BLACKLIST) throw ProtocolException("Слишком длинный чёрный список: $n")
                Cmd.Blacklist(List(n) { Cmd.Blacklist.Entry(r.coordRaw(), r.coordRaw(), r.u16()) })
            }
            Proto.CMD_GET_INFO -> Cmd.GetInfo
            Proto.CMD_SESSION -> Cmd.Session(r.u8() != 0)
            Proto.CMD_BYE -> Cmd.Bye
            Proto.CMD_SET_GPS_MODE -> Cmd.SetGpsMode(r.u8() != 0, r.u16())
            Proto.CMD_SET_RADIO -> Cmd.SetRadio(r.u16())
            Proto.CMD_ACK_ALERT -> Cmd.AckAlert(r.u8())
            Proto.CMD_SYNC_TRACK -> Cmd.SyncTrack(r.u32())
            Proto.CMD_SYNC_MARKS -> Cmd.SyncMarks(r.u32())
            Proto.CMD_SYNC_LOG -> Cmd.SyncLog(r.u32())
            Proto.CMD_SET_DEBUG -> Cmd.SetDebug(r.u8() != 0)
            else -> throw ProtocolException("Неизвестная команда 0x%02X".format(code))
        }.also { r.expectEnd() }
    }

    /** Код команды из сырого пакета (для ACK, даже если аргументы битые). */
    fun cmdCode(data: ByteArray): Int = if (data.size >= 2) data[1].toInt() and 0xFF else 0

    // ---- Устройство: сообщения → байты ----

    fun encode(m: DeviceMsg): ByteArray = when (m) {
        is StatusMsg -> packet(Proto.MSG_STATUS, Proto.STATUS_SIZE - 2) {
            u32(m.timeUtc); u8(m.mode); u8(m.flags); u8(m.fix)
            u8(m.satsUsed); u8(m.satsView); u8(hdop(m.hdop))
            coord(m.lat); coord(m.lon)
            u8(m.src); u8(m.spoofMask)
            i16(scaledOrNa(m.altGps, 10.0))
            u16(scaled(m.speedKmh, 10.0)); u16(scaled(m.courseDeg, 10.0))
            i16(scaledOrNa(m.tempRaw, 100.0)); i16(scaledOrNa(m.tempCorr, 100.0))
            u16(scaled(m.humidity, 100.0))
            u32((m.pressureHpa * 100).roundToLong())
            i16(scaledOrNa(m.altBaro, 10.0)); i16(scaledOrNa(m.trend3h, 100.0))
            u16(m.battMv); i16(scaled(m.battMa, 10.0)); u8(m.battPct); u16(m.usedMah)
            u32(m.steps); u16(if (m.headingDeg.isFinite()) scaled(m.headingDeg, 10.0) else 65535); u32(m.distanceM)
            u16(scaled(m.hoursLeft, 10.0)); u16(m.seq); u8(m.alerts)
        }
        is SatsMsg -> {
            val sats = m.sats.take(Proto.MAX_SATS)
            packet(Proto.MSG_SATS, 1 + 4 * sats.size) {
                u8(sats.size)
                sats.forEach { u8(it.system); u8(it.prn); u8(it.snr); u8(if (it.used) 1 else 0) }
            }
        }
        is InfoMsg -> packet(Proto.MSG_INFO, 27 + 4 * m.checks.size) {
            u8(m.fwMajor); u8(m.fwMinor); u8(m.fwPatch); u8(m.chip)
            u32(m.uptimeSec); u32(m.sdTotalMb); u32(m.sdFreeMb); u8(m.gnssMask)
            u32(m.hikeStartUtc ?: 0)
            u8(m.gpsMode); u16(m.gpsPulseS); u16(m.beaconS)
            u8(m.checks.size)
            m.checks.forEach { u8(it.id); u8(it.level); u8(it.code); u8(it.addr) }
        }
        is MarkMsg -> {
            val t = utf8Limited(m.text)
            packet(Proto.MSG_MARK, 16 + t.size) {
                u16(m.id); u32(m.timeUtc); u8(m.type); coord(m.lat); coord(m.lon); u8(t.size); bytes(t)
            }
        }
        is PointMsg -> packet(Proto.MSG_POINT, Proto.POINT_SIZE - 2) {
            u32(m.timeUtc); coord(m.lat); coord(m.lon)
            u8((m.src and 0x03) or (if (m.spoof) 0x04 else 0)); u8(m.spoofMask)
            coord(m.spoofLat); coord(m.spoofLon)
            i16(scaledOrNa(m.tempRaw, 100.0)); i16(scaledOrNa(m.tempCorr, 100.0))
            u16(scaled(m.humidity, 100.0)); u32((m.pressureHpa * 100).roundToLong())
            i16(scaledOrNa(m.altBaro, 10.0)); i16(scaledOrNa(m.altGps, 10.0))
            u16(m.battMv); u8(m.battPct); u8(m.sats); u8(hdop(m.hdop))
            u32(m.steps); u16(if (m.headingDeg.isFinite()) scaled(m.headingDeg, 10.0) else 65535); u16(scaled(m.speedKmh, 10.0))
        }
        is SyncDoneMsg -> packet(Proto.MSG_SYNC_DONE, 5) { u8(m.what); u32(m.count) }
        is HikeResultMsg -> packet(Proto.MSG_HIKE_RESULT, 6) { u8(m.cmd); u8(m.result); u32(m.hike) }
        is AckMsg -> packet(Proto.MSG_ACK, 2) { u8(m.cmd); u8(m.result) }
        is AlertMsg -> packet(Proto.MSG_ALERT, 8) { u32(m.timeUtc); u8(m.alerts); u8(m.fresh); u8(m.battPct); u8(m.spoofMask) }
        is LogMsg -> {
            val t = utf8Limited(m.text, Proto.MAX_LOG_TEXT)
            packet(Proto.MSG_LOG, 15 + t.size) {
                u32(m.seq); u32(m.timeUtc); u32(m.uptimeSec)
                u8((m.level and 0x03) or (if (m.timeOk) 0x80 else 0)); u8(m.module); u8(t.size); bytes(t)
            }
        }
    }

    // ---- Телефон: байты → сообщение ----

    fun decodeMsg(data: ByteArray): DeviceMsg {
        val r = reader(data)
        return when (val type = r.u8()) {
            Proto.MSG_STATUS -> {
                r.expectSize(Proto.STATUS_SIZE)
                StatusMsg(
                    timeUtc = r.u32(), mode = r.u8(), flags = r.u8(), fix = r.u8(),
                    satsUsed = r.u8(), satsView = r.u8(), hdop = r.hdop(),
                    lat = r.coord(), lon = r.coord(), src = r.u8(), spoofMask = r.u8(),
                    altGps = r.i16OrNull(10.0), speedKmh = r.u16() / 10.0, courseDeg = r.u16() / 10.0,
                    tempRaw = r.i16OrNull(100.0), tempCorr = r.i16OrNull(100.0),
                    humidity = r.u16() / 100.0, pressureHpa = r.u32() / 100.0,
                    altBaro = r.i16OrNull(10.0), trend3h = r.i16OrNull(100.0),
                    battMv = r.u16(), battMa = r.i16() / 10.0, battPct = r.u8(), usedMah = r.u16(),
                    steps = r.u32(), headingDeg = r.u16().let { if (it == 65535) Double.NaN else it / 10.0 }, distanceM = r.u32(),
                    hoursLeft = r.u16() / 10.0, seq = r.u16(), alerts = r.u8(),
                )
            }
            Proto.MSG_SATS -> {
                val n = r.u8()
                if (n > Proto.MAX_SATS) throw ProtocolException("Слишком много спутников: $n")
                SatsMsg(List(n) { SatMsg(r.u8(), r.u8(), r.u8(), r.u8() and 1 != 0) })
            }
            Proto.MSG_INFO -> {
                val ma = r.u8(); val mi = r.u8(); val pa = r.u8(); val chip = r.u8()
                val up = r.u32(); val tot = r.u32(); val free = r.u32(); val gnss = r.u8()
                val hike = r.u32().takeIf { it != 0L }
                val gpsMode = r.u8(); val pulse = r.u16(); val beacon = r.u16()
                val n = r.u8()
                InfoMsg(ma, mi, pa, chip, up, tot, free, gnss, hike, List(n) { CheckMsg(r.u8(), r.u8(), r.u8(), r.u8()) }, gpsMode, pulse, beacon)
            }
            Proto.MSG_MARK -> {
                val id = r.u16(); val t = r.u32(); val mt = r.u8(); val la = r.coord(); val lo = r.coord()
                val len = r.u8()
                MarkMsg(id, t, mt, la, lo, r.string(len))
            }
            Proto.MSG_POINT -> {
                r.expectSize(Proto.POINT_SIZE)
                val t = r.u32(); val la = r.coord(); val lo = r.coord()
                val fl = r.u8()
                PointMsg(
                    timeUtc = t, lat = la, lon = lo, src = fl and 0x03, spoof = fl and 0x04 != 0,
                    spoofMask = r.u8(), spoofLat = r.coord(), spoofLon = r.coord(),
                    tempRaw = r.i16OrNull(100.0), tempCorr = r.i16OrNull(100.0),
                    humidity = r.u16() / 100.0, pressureHpa = r.u32() / 100.0,
                    altBaro = r.i16OrNull(10.0), altGps = r.i16OrNull(10.0),
                    battMv = r.u16(), battPct = r.u8(), sats = r.u8(), hdop = r.hdop(),
                    steps = r.u32(), headingDeg = r.u16().let { if (it == 65535) Double.NaN else it / 10.0 }, speedKmh = r.u16() / 10.0,
                )
            }
            Proto.MSG_SYNC_DONE -> SyncDoneMsg(r.u8(), r.u32())
            Proto.MSG_HIKE_RESULT -> HikeResultMsg(r.u8(), r.u8(), r.u32())
            Proto.MSG_ACK -> AckMsg(r.u8(), r.u8())
            Proto.MSG_ALERT -> AlertMsg(r.u32(), r.u8(), r.u8(), r.u8(), r.u8())
            Proto.MSG_LOG -> {
                val seq = r.u32(); val t = r.u32(); val up = r.u32(); val lv = r.u8(); val mod = r.u8()
                val len = r.u8()
                if (len > Proto.MAX_LOG_TEXT) throw ProtocolException("Строка журнала длиннее ${Proto.MAX_LOG_TEXT} байт")
                LogMsg(seq, t, up, lv and 0x03, lv and 0x80 != 0, mod, r.string(len))
            }
            else -> throw ProtocolException("Неизвестный тип сообщения 0x%02X".format(type))
        }.also { r.expectEnd() }
    }

    // ---- Маячок ----

    fun encodeBeacon(m: BeaconMsg): ByteArray {
        val buf = ByteBuffer.allocate(Proto.BEACON_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val w = Writer(buf)
        w.u8(Proto.VERSION); w.u8(Proto.BEACON_TAG)
        w.u8(m.mode); w.u8(m.flags); w.u8(m.alerts)
        w.i16(scaledOrNa(m.tempCorr, 100.0)); w.u8(m.battPct); w.u8(m.satsUsed)
        w.u16(m.pressureHpa?.let { scaled(it, 10.0) } ?: 0)
        w.u32(m.lastRecordUtc); w.u16(m.hikeId); w.u8(m.counter)
        return buf.array()
    }

    /** Разбор сводки из данных производителя (без company id). null — не наш маячок. */
    fun decodeBeacon(data: ByteArray): BeaconMsg? {
        if (data.size != Proto.BEACON_SIZE) return null
        val r = Reader(data)
        if (r.u8() != Proto.VERSION || r.u8() != Proto.BEACON_TAG) return null
        return BeaconMsg(
            mode = r.u8(), flags = r.u8(), alerts = r.u8(), tempCorr = r.i16OrNull(100.0),
            battPct = r.u8(), satsUsed = r.u8(), pressureHpa = r.u16().takeIf { it != 0 }?.let { it / 10.0 },
            lastRecordUtc = r.u32(), hikeId = r.u16(), counter = r.u8(),
        )
    }

    // ---- Внутреннее ----

    private class Writer(val buf: ByteBuffer) {
        fun u8(v: Int) { buf.put((v and 0xFF).toByte()) }
        fun u16(v: Int) { buf.putShort((v.coerceIn(0, 0xFFFF) and 0xFFFF).toShort()) }
        fun i16(v: Int) { buf.putShort(v.coerceIn(-32768, 32767).toShort()) }
        fun u32(v: Long) { buf.putInt((v.coerceIn(0, 0xFFFFFFFFL) and 0xFFFFFFFFL).toInt()) }
        fun coord(v: Double?) { buf.putInt(if (v == null) 0 else (v * 1e7).roundToLong().toInt()) }
        fun bytes(b: ByteArray) { buf.put(b) }
    }

    private inline fun packet(type: Int, payload: Int, body: Writer.() -> Unit): ByteArray {
        val buf = ByteBuffer.allocate(2 + payload).order(ByteOrder.LITTLE_ENDIAN)
        val w = Writer(buf)
        w.u8(Proto.VERSION); w.u8(type)
        w.body()
        check(buf.position() == buf.capacity()) { "Размер пакета 0x%02X: ${buf.position()} вместо ${buf.capacity()}".format(type) }
        return buf.array()
    }

    private class Reader(val data: ByteArray) {
        val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private fun need(n: Int) {
            if (buf.remaining() < n) throw ProtocolException("Пакет короче ожидаемого (${data.size} байт)")
        }
        fun u8(): Int { need(1); return buf.get().toInt() and 0xFF }
        fun u16(): Int { need(2); return buf.short.toInt() and 0xFFFF }
        fun i16(): Int { need(2); return buf.short.toInt() }
        fun u32(): Long { need(4); return buf.int.toLong() and 0xFFFFFFFFL }
        fun i32(): Int { need(4); return buf.int }
        fun coordRaw(): Double = i32() / 1e7
        fun coord(): Double? = i32().takeIf { it != 0 }?.let { it / 1e7 }
        fun i16OrNull(scale: Double): Double? = i16().takeIf { it != Proto.NA16 }?.let { it / scale }
        fun hdop(): Double? = u8().takeIf { it != Proto.HDOP_NA }?.let { it / 10.0 }
        fun string(len: Int): String {
            need(len)
            val b = ByteArray(len); buf.get(b)
            return String(b, Charsets.UTF_8)
        }
        fun expectSize(n: Int) {
            if (data.size != n) throw ProtocolException("Неверная длина пакета 0x%02X: ${data.size} вместо $n".format(data[1]))
        }
        fun expectEnd() {
            if (buf.remaining() != 0) throw ProtocolException("Лишние ${buf.remaining()} байт в пакете")
        }
    }

    private fun reader(data: ByteArray): Reader {
        if (data.size < 2) throw ProtocolException("Пакет слишком короткий: ${data.size} байт")
        val r = Reader(data)
        val ver = r.u8()
        if (ver != Proto.VERSION) throw ProtocolException("Версия протокола $ver, ожидается ${Proto.VERSION}")
        return r
    }

    private fun scaled(v: Double, k: Double): Int = (v * k).roundToInt()
    private fun scaledOrNa(v: Double?, k: Double): Int =
        if (v == null) Proto.NA16 else (v * k).roundToInt().coerceIn(-32767, 32767)
    private fun hdop(v: Double?): Int = if (v == null) Proto.HDOP_NA else (v * 10).roundToInt().coerceIn(0, 254)

    /** UTF-8 не длиннее max байт, не разрезая символы. */
    fun utf8Limited(s: String, max: Int = Proto.MAX_TEXT): ByteArray {
        var b = s.toByteArray(Charsets.UTF_8)
        var cut = s
        while (b.size > max) {
            cut = cut.dropLast(1)
            b = cut.toByteArray(Charsets.UTF_8)
        }
        return b
    }
}

/** Байты в hex для журнала обмена. */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
