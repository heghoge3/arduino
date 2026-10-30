package com.vabugeode.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    private val status = StatusMsg(
        timeUtc = 1_793_000_000, mode = 2, flags = Proto.F_RECORDING or Proto.F_GPS_ON or Proto.F_SD_OK,
        fix = 3, satsUsed = 9, satsView = 12, hdop = 0.9,
        lat = 60.1580123, lon = 30.5150456, src = 1, spoofMask = 0,
        altGps = 71.3, speedKmh = 4.3, courseDeg = 123.4,
        tempRaw = 7.85, tempCorr = -1.25, humidity = 81.5, pressureHpa = 1012.37,
        altBaro = 68.2, trend3h = -3.4, battMv = 3987, battMa = 47.3, battPct = 83, usedMah = 512,
        steps = 12345, headingDeg = 359.9, distanceM = 6789, hoursLeft = 41.5, seq = 65535, alerts = 0b1010,
    )

    @Test
    fun statusRoundTrip() {
        val bytes = Codec.encode(status)
        assertEquals(Proto.STATUS_SIZE, bytes.size)
        assertEquals(status, Codec.decodeMsg(bytes))
    }

    @Test
    fun statusNullFields() {
        val m = status.copy(hdop = null, lat = null, lon = null, altGps = null, tempRaw = null, tempCorr = null, altBaro = null, trend3h = null)
        val d = Codec.decodeMsg(Codec.encode(m)) as StatusMsg
        assertNull(d.hdop); assertNull(d.lat); assertNull(d.altGps); assertNull(d.tempCorr); assertNull(d.trend3h)
    }

    @Test
    fun statusHeaderAndLittleEndian() {
        val b = Codec.encode(status)
        assertEquals(2, b[0].toInt())                    // версия
        assertEquals(Proto.MSG_STATUS, b[1].toInt())     // тип
        // time_utc = 1 793 000 000 = 0x6ADF0240 → 40 02 DF 6A
        assertArrayEquals(byteArrayOf(0x40, 0x02, 0xDF.toByte(), 0x6A), b.copyOfRange(2, 6))
        // temp_corr −1,25 °C → −125 = 0xFF83 → 83 FF (смещение 30)
        assertArrayEquals(byteArrayOf(0x83.toByte(), 0xFF.toByte()), b.copyOfRange(30, 32))
    }

    @Test
    fun pointRoundTrip() {
        val p = PointMsg(
            timeUtc = 1_793_000_010, lat = 60.12, lon = 30.55, src = 2, spoof = true, spoofMask = 0b101011 or 0x80,
            spoofLat = 60.0346, spoofLon = 30.5693, tempRaw = 2.0, tempCorr = 0.5, humidity = 90.0,
            pressureHpa = 1003.5, altBaro = 110.0, altGps = 12.0, battMv = 3900, battPct = 79,
            sats = 10, hdop = 0.7, steps = 16890, headingDeg = 85.0, speedKmh = 31.5,
        )
        val b = Codec.encode(p)
        assertEquals(Proto.POINT_SIZE, b.size)
        assertEquals(p, Codec.decodeMsg(b))
    }

    @Test
    fun otherMessagesRoundTrip() {
        val msgs = listOf(
            SatsMsg(listOf(SatMsg(0, 5, 41, true), SatMsg(1, 33, 18, false))),
            InfoMsg(0, 1, 0, 1, 3600, 7580, 7400, 0b11, 1_793_000_000, listOf(CheckMsg(4, 1, 3, 0x0D), CheckMsg(7, 0, 0, 0))),
            InfoMsg(0, 1, 0, 1, 10, 0, 0, 0, null, emptyList()),
            MarkMsg(7, 1_793_000_100, 2, 60.1, 30.5, "Граффити на стене цеха"),
            MarkMsg(8, 1_793_000_200, 5, null, null, ""),
            SyncDoneMsg(Proto.CMD_SYNC_TRACK, 1234),
            AckMsg(Proto.CMD_MARK, Proto.ACK_BAD_ARGS),
            HikeResultMsg(Proto.CMD_DELETE_HIKE, Proto.ACK_OK, 1_793_000_000),
            AlertMsg(1_793_000_300, 3, 1, 14, 5),
            InfoMsg(0, 1, 0, 1, 10, 0, 0, 0, null, emptyList(), gpsMode = 1, gpsPulseS = 60, beaconS = 0),
        )
        msgs.forEach { assertEquals(it, Codec.decodeMsg(Codec.encode(it))) }
    }

    @Test
    fun commandsRoundTrip() {
        val cmds = listOf(
            Cmd.SetTime(1_793_000_000, 180),
            Cmd.NewHike(1_793_000_000), Cmd.DeleteHike(1_793_000_001),
            Cmd.PhonePos(59.9386, 30.3141, 25),
            Cmd.Record(true), Cmd.Record(false),
            Cmd.Mark(4, "Старая водонапорная башня"),
            Cmd.Clap, Cmd.Find,
            Cmd.SetIntervals(10, 60),
            Cmd.CalibAlt(45.5),
            Cmd.CalibCompass(true),
            Cmd.Blacklist(listOf(Cmd.Blacklist.Entry(60.0346, 30.5693, 1000), Cmd.Blacklist.Entry(60.1161, 31.3864, 1000))),
            Cmd.GetInfo,
            Cmd.SyncTrack(1_793_000_000), Cmd.SyncMarks(0),
            Cmd.Session(true), Cmd.Session(false), Cmd.Bye, Cmd.SetGpsMode(true, 45), Cmd.SetRadio(0), Cmd.AckAlert(3),
        )
        cmds.forEach { assertEquals(it, Codec.decodeCmd(Codec.encode(it))) }
    }

    @Test
    fun allPacketsFitMtu() {
        val longMark = MarkMsg(1, 0, 6, 60.0, 30.0, "я".repeat(100))
        val sats = SatsMsg(List(30) { SatMsg(0, it, 30, true) })
        val bl = Cmd.Blacklist(List(Proto.MAX_BLACKLIST) { Cmd.Blacklist.Entry(60.0, 30.0, 1000) })
        // ATT-пакет: MTU − 3 байта заголовка
        val max = Proto.MTU - 3
        assertTrue(Codec.encode(longMark).size <= max)
        assertTrue(Codec.encode(sats).size <= max)
        assertTrue(Codec.encode(bl).size <= max)
    }

    @Test
    fun longTextIsCutOnCharBoundary() {
        // «я» — 2 байта в UTF-8: 60 байт = 30 символов, без обрезанной половинки
        val d = Codec.decodeMsg(Codec.encode(MarkMsg(1, 0, 6, null, null, "я".repeat(100)))) as MarkMsg
        assertEquals("я".repeat(30), d.text)
    }

    @Test
    fun beaconRoundTrip() {
        val m = BeaconMsg(1, 0b101, 2, -3.5, 14, 0, null, 1_793_000_000, 0xFFFF, 255)
        val b = Codec.encodeBeacon(m)
        assertEquals(Proto.BEACON_SIZE, b.size)
        assertEquals(m, Codec.decodeBeacon(b))
        b[1] = 0x00
        assertNull("чужой маячок", Codec.decodeBeacon(b))
    }

    @Test(expected = ProtocolException::class)
    fun wrongVersionRejected() {
        val b = Codec.encode(status); b[0] = 1
        Codec.decodeMsg(b)
    }

    @Test(expected = ProtocolException::class)
    fun truncatedRejected() {
        Codec.decodeMsg(Codec.encode(status).copyOf(40))
    }

    @Test(expected = ProtocolException::class)
    fun unknownCommandRejected() {
        Codec.decodeCmd(byteArrayOf(1, 0x7F))
    }
}
