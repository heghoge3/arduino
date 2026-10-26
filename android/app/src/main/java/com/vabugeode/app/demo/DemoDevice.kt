package com.vabugeode.app.demo

import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.model.DeviceStatus
import com.vabugeode.app.model.EnvState
import com.vabugeode.app.model.GpsFix
import com.vabugeode.app.model.GpsState
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.PowerMode
import com.vabugeode.app.model.PowerState
import com.vabugeode.app.model.Satellite
import com.vabugeode.app.model.SpoofReason
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.protocol.BeaconMsg
import com.vabugeode.app.protocol.CheckMsg
import com.vabugeode.app.protocol.Cmd
import com.vabugeode.app.protocol.InfoMsg
import com.vabugeode.app.protocol.MarkMsg
import com.vabugeode.app.protocol.PointMsg
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.protocol.SatMsg
import com.vabugeode.app.protocol.SatsMsg
import com.vabugeode.app.protocol.StatusMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Виртуальное устройство: ведёт себя как прошивка — пишет «на SD» трек и метки,
 * принимает команды и отдаёт данные в виде сообщений протокола (docs/protocol.md).
 * Наружу общается только через [VirtualTransport] байтами — как настоящий ESP32 по BLE.
 *
 * Симулирует поход под Петербургом. Сценарий:
 * - старт сегодня в 14:00 МСК, первые 2 часа уже «записаны» (история);
 * - дальше поход идёт вживую с ускорением времени;
 * - после заката резко падает температура (исходная идея проекта);
 * - стоянки с выключенным GPS, потеря сигнала в здании, эпизоды подмены GNSS.
 */
class DemoDevice(private val scope: CoroutineScope) {

    private val zone = ZoneId.of("Europe/Moscow")
    private val rnd = Random(42)

    /** Чёрный список «на SD» — меняется командой BLACKLIST. */
    var blacklist: List<Cmd.Blacklist.Entry> = listOf(
        Cmd.Blacklist.Entry(60.0346, 30.5693, 1000),
        Cmd.Blacklist.Entry(60.1161, 31.3864, 1000),
    )
        private set

    /** Меняется при каждом перезапуске — это «новый поход» (другое hike_start). */
    val restarts = MutableStateFlow(0)

    /** Во сколько раз демо-время быстрее реального. */
    val speed = MutableStateFlow(30)

    // ---- Внутреннее состояние симуляции ----
    private var startMs = 0L
    private var simMs = 0L
    private var sunsetMs = 0L
    private var lat = START_LAT
    private var lon = START_LON
    private var heading = 40.0
    private var steps = 0
    private var distanceM = 0.0
    private var usedMah = 0.0
    private var lastCurrent = 0.0
    private var tempNoise = 0.0
    private var pressNoise = 0.0
    private var sinceLastPointMs = Long.MAX_VALUE
    private var recording = true
    private var markSeq = 0
    private var points = ArrayList<TrackPoint>()
    private var markList = ArrayList<Mark>()

    // Счисление по шагам: оценка позиции, пока GPS нет или он подменён
    private var pdrLat = 0.0
    private var pdrLon = 0.0
    private var pdrActive = false
    private var prevSpoof = false

    private var job: Job? = null
    // Свой hike_start у каждого запуска приложения (секунды внутри минуты): иначе база склеит старый и новый демо-походы
    private var restartCount = ((System.currentTimeMillis() / 1000) % 60).toInt()
    private var current: DeviceStatus? = null
    private var timeSynced = false
    private var movingIntervalMs = 10_000L
    private var idleIntervalMs = 60_000L
    private val scriptedDone = mutableSetOf<Int>()

    init {
        restart()
    }

    /**
     * Перезапуск сценария с начала.
     * historyMin — сколько минут похода «уже записано» к моменту открытия.
     */
    fun restart(historyMin: Int = 120) {
        job?.cancel()
        val today = LocalDate.now(zone)
        // +N секунд на каждый перезапуск: у каждого похода свой hike_start, как на настоящем устройстве
        startMs = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli() + restartCount * 1000L
        restartCount++
        simMs = startMs
        sunsetMs = SunCalc.times(today, START_LAT, START_LON).sunsetUtcMs ?: (startMs + 3 * HOUR)
        lat = START_LAT; lon = START_LON; heading = 40.0
        steps = 0; distanceM = 0.0; usedMah = 420.0
        tempNoise = 0.0; pressNoise = 0.0
        sinceLastPointMs = Long.MAX_VALUE
        recording = true; markSeq = 0
        points = ArrayList(); markList = ArrayList()
        pdrActive = false; prevSpoof = false
        scriptedDone.clear()
        timeSynced = false

        // История: первые 2 часа похода считаем сразу
        addMarkInternal(MarkType.MARK, "Старт похода")
        val historyEnd = startMs + historyMin * 60_000L
        while (simMs < historyEnd) {
            advance(5_000)
            scriptedMarks()
        }
        publish()
        restarts.value++

        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                advance(TICK_MS * speed.value)
                scriptedMarks()
                publish()
            }
        }
    }

    // ---- Сценарий ----

    private fun minute(): Double = (simMs - startMs) / 60_000.0

    /** Стоянка: GPS выключен, запись раз в 60 с. Цикл: 50 мин идём, 15 стоим. */
    private fun isResting(): Boolean {
        val m = minute()
        if (m < 45) return false
        val cycle = (m - 45) % 65.0
        return cycle < 15
    }

    /** Эпизоды подмены (минуты от старта). */
    private fun spoofTarget(): Pair<Double, Double>? {
        val m = minute()
        return when {
            m in 128.0..140.0 -> 60.0346 to 30.5693
            m in 252.0..262.0 -> 60.1161 to 31.3864
            else -> null
        }
    }

    /** Потеря сигнала внутри здания (урбекс). */
    private fun isIndoor(): Boolean = minute() in 196.0..206.0

    private fun scriptedMarks() {
        val m = minute().toInt()
        val script = mapOf(
            32 to (MarkType.INTERESTING to "Старая водонапорная башня"),
            70 to (MarkType.ENTER to "Зашли на территорию"),
            82 to (MarkType.FOUND to "Граффити на стене цеха"),
            84 to (MarkType.SYNC to ""),
            101 to (MarkType.GUARD to "Собака, уходим"),
            196 to (MarkType.ENTER to "Зашли в корпус"),
            206 to (MarkType.FOUND to "Крыша, вид на закат"),
        )
        script[m]?.let { (type, text) ->
            if (scriptedDone.add(m)) addMarkInternal(type, text)
        }
    }

    // ---- Шаг симуляции ----

    private fun advance(dtMs: Long) {
        simMs += dtMs
        val dt = dtMs / 1000.0
        val resting = isResting()
        val spoof = spoofTarget()
        val indoor = isIndoor()

        // Движение
        if (!resting) {
            heading += rnd.nextGaussian() * 6.0 * sqrt(dt / 5.0)
            // Не уходим дальше ~3 км от старта: плавно разворачиваемся
            val dLatM = (lat - START_LAT) * M_PER_DEG
            val dLonM = (lon - START_LON) * M_PER_DEG * cos(Math.toRadians(lat))
            if (sqrt(dLatM * dLatM + dLonM * dLonM) > 2500) {
                val toStart = Math.toDegrees(atan2(-dLonM, -dLatM))
                heading += angleDiff(toStart, heading) * 0.05
            }
            val speedMs = (4.3 + rnd.nextGaussian() * 0.3) / 3.6 * (if (indoor) 0.4 else 1.0)
            val d = speedMs * dt
            lat += d * cos(Math.toRadians(heading)) / M_PER_DEG
            lon += d * sin(Math.toRadians(heading)) / (M_PER_DEG * cos(Math.toRadians(lat)))
            distanceM += d
            steps += (d / STEP_M).toInt().coerceAtLeast(1)
            if (pdrActive) {
                // Оценка по шагам немного «уплывает» (~5 % пути)
                val err = Math.toRadians(heading + 8.0)
                pdrLat += d * 1.02 * cos(err) / M_PER_DEG
                pdrLon += d * 1.02 * sin(err) / (M_PER_DEG * cos(Math.toRadians(lat)))
            }
        }

        // PDR включается при подмене или потере сигнала, от последней достоверной точки
        val needPdr = (spoof != null || indoor) && !resting
        if (needPdr && !pdrActive) {
            val lastGood = points.lastOrNull { !it.spoof && it.src == PositionSource.GPS }
            pdrLat = lastGood?.lat ?: lat
            pdrLon = lastGood?.lon ?: lon
            pdrActive = true
        } else if (!needPdr && pdrActive) {
            pdrActive = false
        }

        // Ток потребления
        val current = when {
            resting -> 13.0 + rnd.nextGaussian()
            else -> 47.0 + rnd.nextGaussian() * 3
        }
        lastCurrent = current
        usedMah += current * dt / 3600.0

        // Медленные колебания: воздух не скачет на градус за 10 секунд
        val k = dt / 60.0
        tempNoise = (tempNoise * (1 - 0.05 * k) + rnd.nextGaussian() * 0.04 * sqrt(k)).coerceIn(-0.5, 0.5)
        pressNoise = (pressNoise * 0.95 + rnd.nextGaussian() * 0.02).coerceIn(-0.3, 0.3)

        // Запись точки: раз в 10 с в движении, раз в 60 с на стоянке
        sinceLastPointMs = if (sinceLastPointMs == Long.MAX_VALUE) Long.MAX_VALUE else sinceLastPointMs + dtMs
        val interval = if (resting) idleIntervalMs else movingIntervalMs
        if (recording && (sinceLastPointMs >= interval)) {
            sinceLastPointMs = 0
            points.add(makePoint(resting, spoof, indoor))
        }
    }

    private fun makePoint(resting: Boolean, spoof: Pair<Double, Double>?, indoor: Boolean): TrackPoint {
        val env = envNow(resting)
        val gps = gpsNow(resting, spoof, indoor)
        val src = when {
            pdrActive -> PositionSource.PDR
            resting -> PositionSource.NONE
            else -> PositionSource.GPS
        }
        val (pLat, pLon) = if (pdrActive) pdrLat to pdrLon else lat to lon
        val pw = powerNow()
        return TrackPoint(
            tsUtcMs = simMs,
            lat = pLat, lon = pLon,
            altGps = gps.altGps,
            sats = gps.satsUsed, hdop = gps.hdop, speedKmh = gps.speedKmh,
            spoof = gps.spoof, spoofMask = gps.spoofMask,
            spoofLat = spoof?.let { it.first + rnd.nextGaussian() * 0.00003 },
            spoofLon = spoof?.let { it.second + rnd.nextGaussian() * 0.00003 },
            tempRaw = env.tempRaw, tempCorr = env.tempCorr, humidity = env.humidity,
            pressureHpa = env.pressureHpa, altBaro = env.altBaro,
            battV = pw.voltage, battMa = pw.currentMa, battPercent = pw.percent,
            steps = steps, headingDeg = norm360(heading),
            src = src, markId = null,
        )
    }

    // ---- Модели датчиков ----

    /** Рельеф: холмы 30–110 м. */
    private fun terrain(la: Double, lo: Double) = 70 + 25 * sin(la * 300) + 15 * cos(lo * 450)

    /** Давление на уровне моря: стабильно, после 2,5 ч начинает падать (портится погода). */
    private fun seaLevelPressure(ms: Long = simMs): Double {
        val h = ((ms - startMs) / 60_000.0 - 150).coerceAtLeast(0.0) / 60.0
        return 1016.0 - 1.3 * h
    }

    /** Температура воздуха: днём ~6,5°, после заката экспоненциально падает к −1°. */
    private fun airTemp(): Double {
        val h = (simMs - sunsetMs) / HOUR.toDouble()
        val base = when {
            h < -1.5 -> 6.6
            h < 0 -> 6.6 - 0.6 * (h + 1.5) / 1.5
            else -> 6.0 - 7.0 * (1 - exp(-h / 1.3))
        }
        return base + tempNoise
    }

    private fun envNow(resting: Boolean): EnvState {
        val t = airTemp()
        // Корпус греется от электроники: в движении (GPS включён) сильнее
        val heat = (if (resting) 0.9 else 1.8) + tempNoise * 0.3
        val alt = terrain(lat, lon)
        val p = seaLevelPressure() * (1 - alt / 44330.0).pow(5.255) + pressNoise
        // Барометрическая высота считается от давления калибровки (1016 на старте) — поэтому «плывёт» с погодой
        val altBaro = 44330.0 * (1 - (p / 1016.0).pow(1 / 5.255))
        val hum = (62 + (6.6 - t) * 4.2 + tempNoise * 5).coerceIn(35.0, 98.0)
        return EnvState(
            tempRaw = t + heat, tempCorr = t, humidity = hum,
            pressureHpa = p, altBaro = altBaro,
            // Тренд погоды — по давлению, приведённому к уровню моря, иначе подъёмы и спуски выглядят как «непогода».
            // В прошивке приведение будет по барометрической высоте с калибровкой.
            pressureTrend3h = seaLevelPressure() - seaLevelPressure(max(startMs, simMs - 3 * HOUR)),
        )
    }


    private fun gpsNow(resting: Boolean, spoof: Pair<Double, Double>?, indoor: Boolean): GpsState {
        if (resting) {
            return GpsState(GpsFix.OFF, 0, 0, 99.9, 0.0, 0.0, null, emptyList(), false, 0)
        }
        if (indoor) {
            val sats = (1..4).map { Satellite(if (it % 2 == 0) "BDS" else "GPS", 3 + it * 5, 12 + rnd.nextInt(8), false) }
            return GpsState(GpsFix.SEARCHING, 0, sats.size, 99.9, 0.0, 0.0, null, sats, false, 0)
        }
        if (spoof != null) {
            // Подмена: всё «идеально» — много спутников, одинаковый сильный сигнал
            val sats = (1..11).map {
                Satellite(if (it <= 7) "GPS" else "BDS", it * 3, 41 + rnd.nextInt(2), it <= 10)
            }
            val reasons = mutableSetOf(
                SpoofReason.BLACKLIST, SpoofReason.PHONE_MISMATCH,
                SpoofReason.SNR_FLAT, SpoofReason.ACCEL_MISMATCH,
            )
            if (!prevSpoof) reasons += SpoofReason.IMPOSSIBLE_JUMP
            prevSpoof = true
            return GpsState(
                GpsFix.FIX_3D, 10, 11, 0.7, 0.0, 0.0, 12.0, sats, true, SpoofReason.toMask(reasons),
            )
        }
        prevSpoof = false
        val inView = 9 + rnd.nextInt(5)
        val sats = (1..inView).map {
            val sys = if (it <= inView / 2 + 1) "GPS" else "BDS"
            val snr = (18 + rnd.nextInt(28))
            Satellite(sys, if (sys == "GPS") it * 2 + 1 else 20 + it, snr, snr > 24)
        }
        val used = sats.count { it.used }
        return GpsState(
            fix = if (used >= 4) GpsFix.FIX_3D else GpsFix.FIX_2D,
            satsUsed = used, satsInView = inView,
            hdop = (0.8 + rnd.nextDouble() * 0.9),
            speedKmh = 4.3 + rnd.nextGaussian() * 0.3,
            courseDeg = norm360(heading),
            altGps = terrain(lat, lon) + rnd.nextGaussian() * 4,
            satellites = sats, spoof = false, spoofMask = 0,
        )
    }

    private fun powerNow(): PowerState {
        val pct = ((1 - usedMah / BATT_MAH) * 100).coerceIn(0.0, 100.0)
        // Упрощённая кривая разряда Li-ion
        val v = 3.30 + 0.9 * (pct / 100.0).pow(0.7) - lastCurrent * 0.0008
        val avg = 28.0 // средний ток с учётом стоянок
        return PowerState(
            voltage = v, currentMa = lastCurrent, percent = pct.toInt(), charging = false,
            usedMah = usedMah, hoursLeft = (BATT_MAH - usedMah) / avg,
        )
    }

    // ---- Публикация в UI ----

    private fun publish() {
        val resting = isResting()
        val spoof = spoofTarget()
        val indoor = isIndoor()
        val gps = gpsNow(resting, spoof, indoor)
        val env = envNow(resting)
        val src = when {
            pdrActive -> PositionSource.PDR
            resting -> PositionSource.NONE
            else -> PositionSource.GPS
        }
        val pos = when {
            pdrActive -> pdrLat to pdrLon
            else -> points.lastOrNull { !it.spoof }?.let { it.lat to it.lon } ?: (lat to lon)
        }
        current = DeviceStatus(
            timeUtcMs = simMs,
            mode = if (resting) PowerMode.CAMP else PowerMode.MOVING,
            recording = recording,
            gps = gps, env = env, power = powerNow(),
            steps = steps, headingDeg = norm360(heading),
            distanceKm = distanceM / 1000.0,
            positionSource = src,
            lat = pos.first, lon = pos.second,
        )
    }

    // ---- Выдача данных в формате протокола (как прошивка) ----

    val hikeStartUtcSec: Long get() = startMs / 1000
    val pointCount: Int get() = points.size
    val markCount: Int get() = markList.size

    fun statusMsg(seq: Int): StatusMsg {
        val st = current ?: run { publish(); current!! }
        val g = st.gps
        var flags = Proto.F_SD_OK
        if (st.recording) flags = flags or Proto.F_RECORDING
        if (g.fix != GpsFix.OFF) flags = flags or Proto.F_GPS_ON
        if (g.spoof) flags = flags or Proto.F_SPOOF
        if (timeSynced) flags = flags or Proto.F_TIME_SYNCED
        if (st.power.percent < 20) flags = flags or Proto.F_LOW_BATT
        return StatusMsg(
            timeUtc = st.timeUtcMs / 1000, mode = st.mode.ordinal, flags = flags, fix = g.fix.ordinal,
            satsUsed = g.satsUsed, satsView = g.satsInView,
            hdop = if (g.fix == GpsFix.FIX_2D || g.fix == GpsFix.FIX_3D) g.hdop else null,
            lat = st.lat, lon = st.lon, src = srcCode(st.positionSource), spoofMask = g.spoofMask,
            altGps = g.altGps, speedKmh = if (st.mode == PowerMode.MOVING) g.speedKmh.coerceAtLeast(0.0) else 0.0,
            courseDeg = g.courseDeg,
            tempRaw = st.env.tempRaw, tempCorr = st.env.tempCorr, humidity = st.env.humidity,
            pressureHpa = st.env.pressureHpa, altBaro = st.env.altBaro, trend3h = st.env.pressureTrend3h,
            battMv = (st.power.voltage * 1000).toInt(), battMa = st.power.currentMa,
            battPct = st.power.percent, usedMah = st.power.usedMah.toInt(),
            steps = st.steps.toLong(), headingDeg = st.headingDeg, distanceM = (st.distanceKm * 1000).toLong(),
            hoursLeft = st.power.hoursLeft, seq = seq and 0xFFFF,
        )
    }

    /** Сводка для маячка — как в прошивке (makeBeacon). */
    fun beaconMsg(): BeaconMsg {
        val st = current ?: run { publish(); current!! }
        var flags = Proto.B_SD_OK
        if (st.recording) flags = flags or Proto.B_RECORDING
        if (st.gps.fix == GpsFix.FIX_3D || st.gps.fix == GpsFix.FIX_2D) flags = flags or Proto.B_FIX
        if (st.gps.spoof) flags = flags or Proto.B_SPOOF
        if (timeSynced) flags = flags or Proto.B_TIME_OK
        return BeaconMsg(
            mode = st.mode.ordinal, flags = flags,
            alerts = if (st.gps.spoof) Proto.AL_SPOOF else 0,
            tempCorr = st.env.tempCorr, battPct = st.power.percent, satsUsed = st.gps.satsUsed,
            pressureHpa = st.env.pressureHpa, lastRecordUtc = (points.lastOrNull()?.tsUtcMs ?: 0) / 1000,
            hikeId = (hikeStartUtcSec and 0xFFFF).toInt(), counter = (beaconCounter++) and 0xFF,
        )
    }
    private var beaconCounter = 0

    fun satsMsg(): SatsMsg = SatsMsg(
        (current?.gps?.satellites ?: emptyList()).map {
            SatMsg(
                system = when (it.system) { "GPS" -> 0; "BDS" -> 1; "GLO" -> 2; else -> 3 },
                prn = it.prn, snr = it.snr, used = it.used,
            )
        }
    )

    fun infoMsg(): InfoMsg {
        val st = current ?: run { publish(); current!! }
        val g = st.gps
        val gpsCheck = when {
            g.fix == GpsFix.OFF -> CheckMsg(7, 0, 7, 0)
            g.spoof -> CheckMsg(7, 1, 5, 0)
            g.fix == GpsFix.SEARCHING -> CheckMsg(7, 1, 6, 0)
            else -> CheckMsg(7, 0, 0, 0)
        }
        return InfoMsg(
            fwMajor = 0, fwMinor = 1, fwPatch = 0, chip = 1,
            uptimeSec = (simMs - startMs) / 1000,
            sdTotalMb = 7580, sdFreeMb = 7480L - points.size / 40, gnssMask = 0b11,
            hikeStartUtc = startMs / 1000,
            checks = listOf(
                CheckMsg(1, 0, 0, 0x3C),
                CheckMsg(2, 0, 0, 0x40),
                CheckMsg(3, 0, 0, 0x68),
                CheckMsg(4, 1, 3, 0x0D),
                CheckMsg(5, 0, 0, 0x76),
                CheckMsg(6, 0, 0, 0),
                gpsCheck,
                CheckMsg(8, if (st.power.percent < 20) 1 else 0, if (st.power.percent < 20) 8 else 0, 0),
                // Часы DS1302: до первой сверки с телефоном время считается непроверенным
                if (timeSynced) CheckMsg(9, 0, 0, 0) else CheckMsg(9, 1, 11, 0),
            ),
        )
    }

    fun pointMsg(i: Int): PointMsg {
        val p = points[i]
        return PointMsg(
            timeUtc = p.tsUtcMs / 1000, lat = p.lat, lon = p.lon, src = srcCode(p.src),
            spoof = p.spoof, spoofMask = p.spoofMask, spoofLat = p.spoofLat, spoofLon = p.spoofLon,
            tempRaw = p.tempRaw, tempCorr = p.tempCorr, humidity = p.humidity, pressureHpa = p.pressureHpa,
            altBaro = p.altBaro, altGps = p.altGps, battMv = (p.battV * 1000).toInt(), battPct = p.battPercent,
            sats = p.sats, hdop = if (p.sats > 0) p.hdop else null, steps = p.steps.toLong(),
            headingDeg = p.headingDeg, speedKmh = p.speedKmh.coerceAtLeast(0.0),
        )
    }

    fun markMsg(i: Int): MarkMsg {
        val m = markList[i]
        return MarkMsg(m.id, m.tsUtcMs / 1000, m.type.ordinal, m.lat, m.lon, m.text)
    }

    /** Индекс первой точки не раньше времени since (Unix-с). */
    fun firstPointSince(sinceSec: Long): Int {
        val ms = sinceSec * 1000
        var lo = 0; var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].tsUtcMs < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun firstMarkSince(sinceSec: Long): Int = markList.indexOfFirst { it.tsUtcMs >= sinceSec * 1000 }.let { if (it < 0) markList.size else it }

    private fun srcCode(s: PositionSource) = when (s) {
        PositionSource.NONE -> 0
        PositionSource.GPS -> 1
        PositionSource.PDR -> 2
    }

    // ---- Команды (как обработчик в прошивке). Возвращает код ACK ----

    fun handle(cmd: Cmd): Int {
        when (cmd) {
            is Cmd.SetTime -> timeSynced = true // время в демо ускорено — телефонное не применяем
            is Cmd.PhonePos -> {}
            is Cmd.Record -> recording = cmd.on
            is Cmd.Mark -> {
                val type = MarkType.entries.getOrNull(cmd.type) ?: return Proto.ACK_BAD_ARGS
                addMarkInternal(type, cmd.text)
            }
            Cmd.Clap -> addMarkInternal(MarkType.SYNC, "")
            Cmd.Find -> {} // пищать нечему
            is Cmd.SetIntervals -> {
                if (cmd.movingSec !in 1..3600 || cmd.idleSec !in 1..3600) return Proto.ACK_BAD_ARGS
                movingIntervalMs = cmd.movingSec * 1000L
                idleIntervalMs = cmd.idleSec * 1000L
            }
            is Cmd.CalibAlt -> {}
            is Cmd.CalibCompass -> {}
            is Cmd.Blacklist -> blacklist = cmd.points
            Cmd.GetInfo, is Cmd.SyncTrack, is Cmd.SyncMarks -> {} // ответы шлёт транспорт
            is Cmd.Session, Cmd.Bye -> {} // режим сеанса ведёт транспорт
            is Cmd.SetGpsMode, is Cmd.SetRadio, is Cmd.AckAlert -> {}
        }
        publish()
        return Proto.ACK_OK
    }

    private fun addMarkInternal(type: MarkType, text: String) {
        val p = points.lastOrNull { !it.spoof }
        markList.add(Mark(++markSeq, simMs, type, text, p?.lat ?: lat, p?.lon ?: lon))
    }

    // ---- Утилиты ----

    private fun Random.nextGaussian(): Double {
        // Бокс — Мюллер
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    private fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    companion object {
        const val START_LAT = 60.1580
        const val START_LON = 30.5150
        private const val HOUR = 3_600_000L
        private const val TICK_MS = 200L
        private const val M_PER_DEG = 111_320.0
        private const val STEP_M = 0.75
        private const val BATT_MAH = 3000.0 // 18650 на тестах
    }
}
