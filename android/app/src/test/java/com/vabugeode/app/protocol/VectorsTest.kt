package com.vabugeode.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Эталонные байты протокола: docs/protocol_vectors.json.
 * Тот же файл проверяют тесты прошивки — так приложение и ESP32 гарантированно понимают друг друга.
 * Если файла нет — создаётся. Если формат поменялся намеренно — удалить файл и перезапустить тест.
 */
class VectorsTest {

    private val file = File("../../docs/protocol_vectors.json")

    private val vectors: List<Pair<String, ByteArray>> = listOf(
        "status" to Codec.encode(
            StatusMsg(
                timeUtc = 1_793_000_000, mode = 2, flags = Proto.F_RECORDING or Proto.F_GPS_ON or Proto.F_SD_OK,
                fix = 3, satsUsed = 9, satsView = 12, hdop = 0.9, lat = 60.158, lon = 30.515, src = 1, spoofMask = 0,
                altGps = 71.3, speedKmh = 4.3, courseDeg = 123.4, tempRaw = 7.85, tempCorr = -1.25,
                humidity = 81.5, pressureHpa = 1012.37, altBaro = 68.2, trend3h = -3.4, battMv = 3987, battMa = 47.3,
                battPct = 83, usedMah = 512, steps = 12345, headingDeg = 359.9, distanceM = 6789, hoursLeft = 41.5, seq = 7,
                alerts = Proto.AL_SPOOF,
            )
        ),
        "status_no_data" to Codec.encode(
            StatusMsg(
                timeUtc = 1_793_000_001, mode = 1, flags = 0, fix = 0, satsUsed = 0, satsView = 0, hdop = null,
                lat = null, lon = null, src = 0, spoofMask = 0, altGps = null, speedKmh = 0.0, courseDeg = 0.0,
                tempRaw = null, tempCorr = null, humidity = 0.0, pressureHpa = 0.0, altBaro = null, trend3h = null,
                battMv = 0, battMa = -120.0, battPct = 0, usedMah = 0, steps = 0, headingDeg = 0.0, distanceM = 0,
                hoursLeft = 0.0, seq = 8,
            )
        ),
        "sats" to Codec.encode(SatsMsg(listOf(SatMsg(0, 5, 41, true), SatMsg(1, 33, 18, false)))),
        "info" to Codec.encode(InfoMsg(0, 1, 0, 1, 3600, 7580, 7400, 0b11, 1_793_000_000, listOf(CheckMsg(4, 1, 3, 0x0D), CheckMsg(7, 0, 0, 0)), gpsMode = 1, gpsPulseS = 45, beaconS = 30)),
        "mark" to Codec.encode(MarkMsg(7, 1_793_000_100, 2, 60.1, 30.5, "Граффити")),
        "point_spoof" to Codec.encode(
            PointMsg(
                timeUtc = 1_793_000_010, lat = 60.12, lon = 30.55, src = 2, spoof = true, spoofMask = 0b101011,
                spoofLat = 60.0346, spoofLon = 30.5693, tempRaw = 2.0, tempCorr = 0.5, humidity = 90.0,
                pressureHpa = 1003.5, altBaro = 110.0, altGps = 12.0, battMv = 3900, battPct = 79, sats = 10,
                hdop = 0.7, steps = 16890, headingDeg = 85.0, speedKmh = 31.5,
            )
        ),
        "sync_done" to Codec.encode(SyncDoneMsg(Proto.CMD_SYNC_TRACK, 1234)),
        "ack" to Codec.encode(AckMsg(Proto.CMD_MARK, Proto.ACK_BAD_ARGS)),
        "alert" to Codec.encode(AlertMsg(1_793_000_300, Proto.AL_SPOOF or Proto.AL_LOW_BATT, Proto.AL_SPOOF, 14, 0b1000101)),
        "beacon" to Codec.encodeBeacon(
            BeaconMsg(
                mode = 1, flags = Proto.B_RECORDING or Proto.B_FIX or Proto.B_SD_OK, alerts = 0, tempCorr = -1.25,
                battPct = 83, satsUsed = 9, pressureHpa = 1012.4, lastRecordUtc = 1_793_000_000, hikeId = 0x0240, counter = 200,
            )
        ),
        "cmd_set_time" to Codec.encode(Cmd.SetTime(1_793_000_000, 180)),
        "cmd_phone_pos" to Codec.encode(Cmd.PhonePos(59.9386, 30.3141, 25)),
        "cmd_record_on" to Codec.encode(Cmd.Record(true)),
        "cmd_mark" to Codec.encode(Cmd.Mark(4, "Башня")),
        "cmd_clap" to Codec.encode(Cmd.Clap),
        "cmd_set_intervals" to Codec.encode(Cmd.SetIntervals(10, 60)),
        "cmd_calib_alt" to Codec.encode(Cmd.CalibAlt(45.5)),
        "cmd_blacklist" to Codec.encode(Cmd.Blacklist(listOf(Cmd.Blacklist.Entry(60.0346, 30.5693, 1000)))),
        "cmd_get_info" to Codec.encode(Cmd.GetInfo),
        "cmd_session_live" to Codec.encode(Cmd.Session(true)),
        "cmd_bye" to Codec.encode(Cmd.Bye),
        "cmd_set_gps_mode" to Codec.encode(Cmd.SetGpsMode(true, 45)),
        "cmd_set_radio" to Codec.encode(Cmd.SetRadio(30)),
        "cmd_ack_alert" to Codec.encode(Cmd.AckAlert(Proto.AL_SPOOF)),
        "cmd_sync_track" to Codec.encode(Cmd.SyncTrack(1_793_000_000)),
        "log" to Codec.encode(LogMsg(4242, 1_793_000_020, 3605, 1, true, 2, "Фикс потерян, спутн 3")),
        "cmd_sync_log" to Codec.encode(Cmd.SyncLog(4241)),
        "cmd_set_debug" to Codec.encode(Cmd.SetDebug(true)),
    )

    private fun render(): String = buildString {
        appendLine("{")
        appendLine("  \"_comment\": \"Эталонные пакеты протокола v${Proto.VERSION} (docs/protocol.md). Генерирует android VectorsTest, проверяют приложение и прошивка.\",")
        vectors.forEachIndexed { i, (name, bytes) ->
            val hex = bytes.joinToString("") { "%02x".format(it) }
            append("  \"$name\": \"$hex\"")
            appendLine(if (i < vectors.size - 1) "," else "")
        }
        appendLine("}")
    }

    @Test
    fun vectorsMatchFile() {
        val actual = render()
        if (!file.exists()) file.writeText(actual)
        assertEquals("Формат протокола изменился — см. комментарий в VectorsTest", file.readText(), actual)
    }
}
