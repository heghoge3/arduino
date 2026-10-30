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
import com.vabugeode.app.store.MarkRow
import com.vabugeode.app.store.GadgetLogRow
import com.vabugeode.app.log.AppLog
import com.vabugeode.app.protocol.LogMsg
import com.vabugeode.app.store.PointRow
import com.vabugeode.app.store.StoreDao
import com.vabugeode.app.store.saveState
import com.vabugeode.app.transport.Packet
import com.vabugeode.app.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.vabugeode.app.protocol.HikeResultMsg
import kotlinx.coroutines.Job
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
 * Источник можно сменить на ходу ([use]) — экраны подписаны на этот же объект.
 */
class ProtocolDeviceRepository(
    private val scope: CoroutineScope,
    initialTransport: Transport,
    private val initialBlacklist: List<BlacklistPoint>,
    private val store: StoreDao? = null,
) : DeviceRepository {

    private var transport: Transport = initialTransport
    private var transportJobs: List<Job> = emptyList()

    override val link = MutableStateFlow(LinkState.DISCONNECTED)

    /** Когда последний раз что-то приходило от гаджета (или сохранено в базе) */
    val lastSeenMs = MutableStateFlow<Long?>(null)

    /** Приложение на экране: тревоги видны человеку — можно подтверждать их гаджету */
    var foreground = true

    /** Живой сеанс (STATUS раз в секунду) или тихий (только метки и тревоги) */
    private var liveWanted = true
    private var ackedAlerts = 0
    private var restoreJob: Job? = null

    // Последние сырые пакеты — для сохранения в базу
    private var lastStatusBytes: ByteArray? = null
    private var lastSatsBytes: ByteArray? = null
    private var lastInfoBytes: ByteArray? = null
    private var lastSavedMs = 0L
    private var savedWithStatus = false
    private val pendingPoints = ArrayList<PointRow>()
    private val pendingMarks = ArrayList<MarkRow>()
    private val pendingLogs = ArrayList<GadgetLogRow>()
    /** Последняя полученная строка журнала гаджета — догружаем только новые */
    private var logSeq = 0L

    /** Подробный журнал на гаджете (отладка). Отправляется при каждом подключении */
    var debugLog: Boolean = true
        set(v) {
            field = v
            if (link.value == LinkState.CONNECTED) send(Cmd.SetDebug(v))
        }

    /** Позиция телефона для проверки подмены (lat, lon, точность м). null — нет разрешения или данных. */
    var phonePosition: (() -> Triple<Double, Double, Int>?)? = null
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

    // Настройки телефона отправляются только прошивкам с поддержкой 0x17.
    var pdrSettings = Cmd.PdrSettings(75, 0, false)
    val navigationState = MutableStateFlow<Int?>(null)
    val compassState = MutableStateFlow<Int?>(null) // INFO: 0 готов, 3 не откалиброван, 13 идёт калибровка
    fun setPdrSettings(strideCm: Int, offset10: Int, inverted: Boolean) {
        require(strideCm in 30..150 && offset10 in -1800..1800)
        pdrSettings = Cmd.PdrSettings(strideCm, offset10, inverted)
        if (link.value == LinkState.CONNECTED && supportsPdr()) send(pdrSettings)
    }
    private fun supportsPdr(): Boolean = lastInfo?.let { it.fwMajor > 0 || it.fwMinor > 1 || (it.fwMinor == 1 && it.fwPatch >= 14) } == true
    fun anchorFromPhone(lat: Double, lon: Double, accuracyM: Int) {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && accuracyM in 1..1000)
        send(Cmd.PhonePos(lat, lon, accuracyM))
    }
    fun calibrateCompass(start: Boolean) {
        if (link.value != LinkState.CONNECTED || !supportsPdr()) return
        send(Cmd.CalibCompass(start))
        if (!start) phonePosition?.invoke()?.let { (lat, lon, acc) -> send(Cmd.PhonePos(lat, lon, acc)) }
    }

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
        attach(initialTransport)
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
                if (link.value == LinkState.CONNECTED && !stats.value.syncing) send(Cmd.Session(live = liveWanted))
            }
        }
        // Запись в базу пачками раз в секунду
        if (store != null) scope.launch {
            while (isActive) {
                delay(1000)
                persist()
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
    val currentTransport: Transport get() = transport

    /** Сменить источник байтов: старый отключается, данные очищаются (это другое устройство). */
    fun use(t: Transport) {
        if (t === transport) return
        scope.launch { persist(force = true) }
        transport.disconnect()
        // Загрузка из базы для прошлого источника могла ещё не закончиться — иначе она допишет
        // его поход (например, эмулятора) в данные нового гаджета
        restoreJob?.cancel()
        transportJobs.forEach { it.cancel() }
        points.clear()
        markMap.clear()
        lastSats = null
        lastInfo = null
        compassState.value = null
        navigationState.value = null
        hikeStartSec = null
        hikeStartMs.value = null
        status.value = null
        info.value = null
        track.value = emptyList()
        marks.value = emptyList()
        stats.value = LinkStats()
        log.value = emptyList()
        lastSeenMs.value = null
        lastStatusBytes = null
        lastSatsBytes = null
        lastInfoBytes = null
        ackedAlerts = 0
        savedWithStatus = false
        attach(t)
    }

    /** Живой или тихий сеанс (приложение на экране или свёрнуто) */
    fun setLive(on: Boolean) {
        if (liveWanted == on) return
        liveWanted = on
        if (link.value == LinkState.CONNECTED) send(Cmd.Session(live = on))
    }

    /** Попрощаться: гаджет сразу вернётся к маячку и не будет ждать 60 с */
    fun bye() {
        if (link.value != LinkState.CONNECTED) return
        scope.launch {
            sendLock.withLock { transport.send(Codec.encode(Cmd.Bye)) }
            delay(300)
            transport.disconnect()
        }
    }

    // ---- Локальная база ----

    private suspend fun restore(key: String) {
        val dao = store ?: return
        val g = dao.gadget(key) ?: return
        if (transport.key != key) return  // источник сменился, пока читали базу
        lastSeenMs.value = g.lastSeenMs
        logSeq = dao.maxLogSeq(key) ?: 0
        g.hike?.let { hike ->
            hikeStartSec = hike
            hikeStartMs.value = hike * 1000
            loadHike(key, hike)
        }
        lastSatsBytes = g.sats
        lastSats = g.sats?.let { runCatching { Codec.decodeMsg(it) }.getOrNull() as? SatsMsg }
        lastStatusBytes = g.status
        g.status?.let { b -> (runCatching { Codec.decodeMsg(b) }.getOrNull() as? StatusMsg)?.let { status.value = it.toStatus(lastSats) } }
        lastInfoBytes = g.info
        g.info?.let { b ->
            (runCatching { Codec.decodeMsg(b) }.getOrNull() as? InfoMsg)?.let {
                lastInfo = it
                info.value = buildInfo(it, status.value)
            }
        }
    }

    /** Точки и метки похода из базы в память (то, что уже скачано с гаджета раньше) */
    private suspend fun loadHike(key: String, hike: Long) {
        val dao = store ?: return
        val rows = dao.points(key, hike)
        if (transport.key != key) return
        rows.forEach { row ->
            (runCatching { Codec.decodeMsg(row.data) }.getOrNull() as? PointMsg)?.let { points[it.timeUtc * 1000] = it.toTrackPoint() }
        }
        val markRows = dao.marks(key, hike)
        if (transport.key != key) return
        markRows.forEach { row ->
            (runCatching { Codec.decodeMsg(row.data) }.getOrNull() as? MarkMsg)?.let { markMap[it.id] = it.toMark() }
        }
        dirty = true
    }

    private suspend fun persist(force: Boolean = false) {
        val dao = store ?: return
        val key = transport.key
        if (key == "none") return
        val pts: List<PointRow>
        val mks: List<MarkRow>
        val lgs: List<GadgetLogRow>
        synchronized(pendingPoints) {
            pts = ArrayList(pendingPoints); pendingPoints.clear()
            mks = ArrayList(pendingMarks); pendingMarks.clear()
            lgs = ArrayList(pendingLogs); pendingLogs.clear()
        }
        if (pts.isNotEmpty()) dao.putPoints(pts)
        if (mks.isNotEmpty()) dao.putMarks(mks)
        if (lgs.isNotEmpty()) {
            dao.putGadgetLog(lgs)
            if (lgs.size > 50) dao.trimGadgetLog(50_000)
        }
        val seen = lastSeenMs.value ?: return
        // Состояние — раз в 30 с; сразу — при первом STATUS, при отключении и смене источника
        val firstStatus = !savedWithStatus && lastStatusBytes != null
        if (force || firstStatus || System.currentTimeMillis() - lastSavedMs > 30_000) {
            lastSavedMs = System.currentTimeMillis()
            savedWithStatus = lastStatusBytes != null
            dao.saveState(key, hikeStartSec, lastStatusBytes, lastSatsBytes, lastInfoBytes, seen)
        }
    }

    private fun attach(t: Transport) {
        transport = t
        restoreJob = if (store != null) scope.launch { restore(t.key) } else null
        transportJobs = listOf(
            scope.launch { t.incoming.collect { onPacket(it) } },
            scope.launch {
                t.state.collect { st ->
                    val was = link.value
                    link.value = st
                    if (st != was) AppLog.i("BLE", "${t.deviceName}: ${was.title} → ${st.title}")
                    if (st == LinkState.CONNECTED) onConnected()
                    if (st == LinkState.DISCONNECTED && was != LinkState.DISCONNECTED) persist(force = true)
                }
            },
        )
    }

    // ---- Команды ----

    // BLE не принимает параллельные записи — команды строго по очереди
    private val sendLock = Mutex()
    private val hikeLock = Mutex()
    private var hikeReply: Triple<Int, Long, CompletableDeferred<Int>>? = null
    val hikeBusy = MutableStateFlow(false)

    suspend fun manageHike(hike: Long, delete: Boolean): Boolean = hikeLock.withLock {
        val selected = transport
        if (link.value != LinkState.CONNECTED || stats.value.syncing) {
            _notices.tryEmit("Подключитесь к гаджету и дождитесь окончания синхронизации")
            return@withLock false
        }
        val code = if (delete) Proto.CMD_DELETE_HIKE else Proto.CMD_NEW_HIKE
        val cmd = if (delete) Cmd.DeleteHike(hike) else Cmd.NewHike(hike)
        val reply = CompletableDeferred<Int>()
        hikeBusy.value = true
        hikeReply = Triple(code, hike, reply)
        try {
            val sent = sendLock.withLock { selected.send(Codec.encode(cmd)) }
            val result = if (sent) withTimeoutOrNull(15_000) { reply.await() } else null
            if (transport !== selected || result != Proto.ACK_OK) {
                _notices.tryEmit(when (result) {
                    Proto.ACK_UNSUPPORTED -> "Обновите прошивку до 0.1.13 для управления походами"
                    Proto.ACK_BUSY -> "Гаджет занят, поход ещё текущий или SD недоступна. Дождитесь синхронизации и повторите"
                    else -> "Нет подтверждения гаджета. Поход в телефоне не удалён; переподключитесь перед повтором"
                })
                return@withLock false
            }
            persist(force = true)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.e("HIKE", "операция с походом не завершена: ${e.message}")
            _notices.tryEmit("Не удалось завершить операцию. Копия в телефоне не удалена")
            false
        } finally {
            hikeReply = null
            hikeBusy.value = false
        }
    }

    private fun send(cmd: Cmd) {
        scope.launch {
            val bytes = Codec.encode(cmd)
            if (sendLock.withLock { transport.send(bytes) }) {
                addLog(LogEntry(System.currentTimeMillis(), true, cmdName(bytes), bytes.size, bytes.toHex()))
                stats.value = stats.value.copy(packetsOut = stats.value.packetsOut + 1)
            } else {
                AppLog.w("BLE", "команда ${cmdName(bytes)} не ушла: нет связи")
                _notices.tryEmit("Устройство не подключено")
            }
        }
    }

    override fun addMark(type: MarkType, text: String) = send(Cmd.Mark(type.ordinal, text))
    override fun clap() = send(Cmd.Clap)
    override fun setRecording(on: Boolean) {
        send(Cmd.Record(on))
        if (on) phonePosition?.invoke()?.let { (lat, lon, acc) -> send(Cmd.PhonePos(lat, lon, acc)) }
        send(Cmd.GetInfo) // мог начаться новый поход
    }
    override fun findDevice() = send(Cmd.Find)

    fun setBlacklistRadius(index: Int, radiusM: Int) {
        require(index in blacklist.value.indices && radiusM in 100..50000)
        blacklist.value = blacklist.value.mapIndexed { i, p -> if (i == index) p.copy(radiusM = radiusM) else p }
        if (link.value == LinkState.CONNECTED) {
            send(Cmd.Blacklist(blacklist.value.map { Cmd.Blacklist.Entry(it.lat, it.lon, it.radiusM) }))
        }
    }

    fun refreshInfo() = send(Cmd.GetInfo)

    private fun onConnected() = scope.launch {
        // Сначала дочитать базу: тогда догрузим только то, чего в телефоне ещё нет
        restoreJob?.join()
        syncAfterInfo = true
        stats.value = stats.value.copy(lastSeq = null, syncing = false)
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
        send(Cmd.SetTime(now / 1000, tz))
        send(Cmd.Blacklist(blacklist.value.map { Cmd.Blacklist.Entry(it.lat, it.lon, it.radiusM) }))
        phonePosition?.invoke()?.let { (lat, lon, acc) -> send(Cmd.PhonePos(lat, lon, acc)) }
        // Дальше — по ответу INFO: понять, тот же ли поход, и догрузить недостающее
        send(Cmd.GetInfo)
    }

    // ---- Входящие пакеты ----

    private fun onPacket(p: Packet) {
        val msg = try {
            Codec.decodeMsg(p.data)
        } catch (e: ProtocolException) {
            AppLog.e("PROTO", "не разобран пакет ${p.data.size} Б: ${e.message} · ${p.data.toHex().take(60)}")
            addLog(LogEntry(System.currentTimeMillis(), false, e.message ?: "ошибка", p.data.size, p.data.toHex(), error = true))
            stats.value = stats.value.copy(errors = stats.value.errors + 1)
            return
        }
        val s = stats.value
        stats.value = s.copy(packetsIn = s.packetsIn + 1, bytesIn = s.bytesIn + p.data.size)
        lastSeenMs.value = System.currentTimeMillis()
        // STATUS, POINT и LOG идут потоком — в журнал пишем не каждый, иначе он забьётся
        val noisy = msg is PointMsg || msg is LogMsg || (msg is StatusMsg && msg.seq % 10 != 0)
        if (!noisy) addLog(LogEntry(System.currentTimeMillis(), false, msgName(msg), p.data.size, p.data.toHex()))

        when (msg) {
            is StatusMsg -> {
                lastStatusBytes = p.data
                onStatus(msg)
            }
            is SatsMsg -> {
                lastSats = msg
                lastSatsBytes = p.data
            }
            is InfoMsg -> {
                lastInfoBytes = p.data
                onInfo(msg)
            }
            is MarkMsg -> {
                markMap[msg.id] = msg.toMark()
                dirty = true
                hikeStartSec?.let { h -> synchronized(pendingPoints) { pendingMarks += MarkRow(transport.key, h, msg.id, p.data) } }
            }
            is PointMsg -> {
                points[msg.timeUtc * 1000] = msg.toTrackPoint()
                dirty = true
                hikeStartSec?.let { h -> synchronized(pendingPoints) { pendingPoints += PointRow(transport.key, h, msg.timeUtc, p.data) } }
            }
            is SyncDoneMsg -> {
                if (msg.what != Proto.CMD_SYNC_LOG || msg.count > 0) AppLog.d("SYNC", "${cmdNameByCode(msg.what)}: ${msg.count} записей")
                if (msg.what == Proto.CMD_SYNC_MARKS) stats.value = stats.value.copy(syncing = false)
            }
            is AlertMsg -> {
                // Подмену всплывашкой не показываем — она видна на Главной и на карте
                val fresh = msg.fresh and Proto.AL_SPOOF.inv()
                if (fresh != 0) {
                    AppLog.w("ALERT", "тревога: ${alertText(fresh)}, заряд ${msg.battPct}%")
                    _notices.tryEmit("Тревога: ${alertText(fresh)}")
                }
            }
            is LogMsg -> {
                if (msg.seq > logSeq) logSeq = msg.seq
                synchronized(pendingPoints) {
                    pendingLogs += GadgetLogRow(
                        transport.key, msg.seq, msg.timeUtc * 1000, msg.uptimeSec, msg.level, msg.timeOk, msg.module, msg.text,
                    )
                }
            }
            is HikeResultMsg -> {
                hikeReply?.let { (code, hike, reply) ->
                    if (code == msg.cmd && hike == msg.hike) reply.complete(msg.result)
                }
            }
            is AckMsg -> if (msg.result != Proto.ACK_OK) {
                hikeReply?.let { (code, _, reply) -> if (code == msg.cmd) reply.complete(msg.result) }
                AppLog.w("BLE", "гаджет отклонил ${cmdNameByCode(msg.cmd)}: ${ackText(msg.result)}")
                _notices.tryEmit(if (msg.cmd == Proto.CMD_CALIB_COMPASS && msg.result == Proto.ACK_BAD_ARGS)
                    "Калибровка не сохранена: вращайте по всем трём осям не менее 20 с, затем положите горизонтально и повторите."
                    else "Устройство отклонило ${cmdNameByCode(msg.cmd)}: ${ackText(msg.result)}")
            }
        }
    }

    private fun onInfo(m: InfoMsg) {
        val firstInfo = syncAfterInfo
        lastInfo = m
        compassState.value = m.checks.firstOrNull { it.id == 4 }?.code
        navigationState.value = m.checks.firstOrNull { it.id == 10 }?.code
        if (firstInfo && supportsPdr()) send(pdrSettings)
        val newHike = m.hikeStartUtc != hikeStartSec
        if (newHike) {
            // Другой поход на устройстве: в памяти — только его точки. Что уже скачано раньше, берём из базы,
            // иначе вкладка «Графики» покажет лишь хвост, а гаджет будет заново слать весь поход
            AppLog.i("SYNC", "поход на гаджете ${m.hikeStartUtc}, в приложении был $hikeStartSec")
            hikeStartSec = m.hikeStartUtc
            hikeStartMs.value = m.hikeStartUtc?.let { it * 1000 }
            if (!firstInfo) phonePosition?.invoke()?.let { (lat, lon, acc) -> send(Cmd.PhonePos(lat, lon, acc)) }
            points.clear()
            markMap.clear()
            dirty = true
        }
        // После подключения или смены похода догружаем только недостающее — с последней известной записи
        if (syncAfterInfo || newHike) {
            syncAfterInfo = false
            val key = transport.key
            val hike = m.hikeStartUtc
            stats.value = stats.value.copy(syncing = true)
            scope.launch {
                if (newHike && hike != null) loadHike(key, hike)
                val sincePoint = points.lastEntry()?.key?.let { it / 1000 + 1 } ?: 0
                val sinceMark = markMap.values.maxOfOrNull { it.tsUtcMs }?.let { it / 1000 + 1 } ?: 0
                send(Cmd.SyncTrack(sincePoint))
                send(Cmd.SyncMarks(sinceMark))
                send(Cmd.SetDebug(debugLog))
                // Живой сеанс: устройство начнёт слать STATUS раз в секунду (protocol.md, порядок подключения)
                send(Cmd.Session(live = liveWanted))
            }
        }
        // Журнал гаджета — при каждом INFO (раз в 30 с): только новые строки
        send(Cmd.SyncLog(logSeq))
        info.value = buildInfo(m, status.value)
    }

    private fun onStatus(m: StatusMsg) {
        val prev = stats.value.lastSeq
        if (prev != null) {
            val gap = ((m.seq - prev) and 0xFFFF) - 1
            if (gap in 1..1000) stats.value = stats.value.copy(lostStatus = stats.value.lostStatus + gap)
        }
        stats.value = stats.value.copy(lastSeq = m.seq)
        // Тревогу увидели на экране — сказать гаджету, чтобы не повторял окно
        if (m.alerts == 0) ackedAlerts = 0
        else if (foreground && m.alerts and ackedAlerts.inv() != 0) {
            ackedAlerts = m.alerts
            send(Cmd.AckAlert(m.alerts))
        }
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

        private fun srcOf(s: Int) = when (s) { 1 -> PositionSource.GPS; 2 -> PositionSource.PDR; 3 -> PositionSource.PAUSE; else -> PositionSource.NONE }

        private fun f(fmt: String, vararg a: Any) = String.format(Locale("ru"), fmt, *a)

        /** Текст проверки: по id и коду причины + живые значения из STATUS. */
        fun checkText(c: CheckMsg, st: DeviceStatus?, info: InfoMsg): SelfCheck {
            val level = when (c.level) { 0 -> CheckLevel.OK; 1 -> CheckLevel.WARN; else -> CheckLevel.ERROR }
            val name = when (c.id) {
                1 -> "Экран SSD1306"
                2 -> "Питание INA219"
                3 -> "Акселерометр MPU6050"
                4 -> when (c.addr) {
                    0x2C -> "Компас QMC5883P"
                    0x1E -> "Компас HMC5883L"
                    else -> "Компас QMC5883L"
                }
                5 -> "Климат BME280"
                6 -> "Карта SD"
                7 -> "GPS ATGM336H"
                8 -> "Аккумулятор"
                9 -> "Часы DS1302"
                10 -> "Приблизительный маршрут"
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
                10 -> "Направление автоматически настроено по GPS и ходьбе"
                else -> null
            } ?: "Отвечает"
            val detail = when (c.code) {
                0 -> okDetail
                1 -> "Не найден на шине — проверьте провода и питание"
                2 -> "Неверный chip id — возможно, другой датчик (BMP280?)"
                3 -> if (c.id == 4 && c.addr == 0x2C && (info.fwMajor > 0 || info.fwMinor > 1 || (info.fwMinor == 1 && info.fwPatch >= 14)))
                    if (info.fwMajor > 0 || info.fwMinor > 1 || info.fwPatch >= 15)
                        "Дополнительный компас не откалиброван; автоматический маршрут работает без него"
                    else "Нужна калибровка: Настройки → Маршрут по шагам" else "Обнаружен; калибровка и направление пока не поддерживаются"
                13 -> "Калибровка: вращайте корпус по трём осям, затем завершите в настройках"
                14 -> "Нужна короткая прогулка с достоверным GPS; шаги считаются при любой ориентации"
                15 -> "Гироскоп настроится автоматически, когда гаджет пару секунд неподвижен"
                16 -> "Нет устойчивого направления ходьбы; приблизительная линия может прерваться"
                17 -> "Долго без GPS: ошибка направления накапливается"
                4 -> "Нет данных NMEA — проверьте TX/RX и питание"
                5 -> "NMEA идёт, но подозрение на подмену"
                6 -> "Ищет спутники${st?.let { " (${it.gps.satsInView} видно)" } ?: ""}"
                7 -> "Выключен — стоянка, экономия заряда"
                8 -> "Низкий заряд${st?.let { " — ${it.power.percent} %" } ?: ""}"
                9 -> "Карта не вставлена или не читается"
                10 -> "Мало места на карте"
                11 -> "Часы стояли (села батарейка?) — время не проверено, ждём телефон или GPS"
                12 -> "Напряжение аккумулятора не измеряется — проверьте подключение; возможно питание от USB"
                else -> "Код ${c.code}"
            }
            val addr = if (c.addr != 0) "0x%02X".format(c.addr) else when (c.id) { 6 -> "SPI"; 7 -> "UART"; 9 -> "3-wire"; else -> null }
            return SelfCheck(name, detail, level, addr)
        }

        fun msgName(m: Any): String = when (m) {
            is HikeResultMsg -> "HIKE_RESULT ${m.hike}: ${ackText(m.result)}"
            is StatusMsg -> "STATUS #${m.seq}"
            is SatsMsg -> "SATS ×${m.sats.size}"
            is InfoMsg -> "INFO"
            is MarkMsg -> "MARK #${m.id}"
            is PointMsg -> "POINT"
            is SyncDoneMsg -> "SYNC_DONE ${when (m.what) { Proto.CMD_SYNC_TRACK -> "трек"; Proto.CMD_SYNC_LOG -> "журнал"; else -> "метки" }}: ${m.count}"
            is LogMsg -> "LOG #${m.seq}"
            is AckMsg -> "ACK ${cmdNameByCode(m.cmd)} → ${ackText(m.result)}"
            is AlertMsg -> "ALERT ${alertText(m.alerts)}"
            else -> "?"
        }

        fun cmdName(bytes: ByteArray) = cmdNameByCode(Codec.cmdCode(bytes))

        fun cmdNameByCode(code: Int) = when (code) {
            Proto.CMD_NEW_HIKE -> "NEW_HIKE"
            Proto.CMD_DELETE_HIKE -> "DELETE_HIKE"
            Proto.CMD_PDR_SETTINGS -> "PDR_SETTINGS"
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
            Proto.CMD_SYNC_LOG -> "SYNC_LOG"
            Proto.CMD_SET_DEBUG -> "SET_DEBUG"
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

/** Пакеты из базы и по радио → модель экранов */
internal fun PointMsg.toTrackPoint() = TrackPoint(
    tsUtcMs = timeUtc * 1000, lat = lat ?: Double.NaN, lon = lon ?: Double.NaN,
    altGps = altGps, sats = sats, hdop = hdop ?: 99.9, speedKmh = speedKmh,
    spoof = spoof, spoofMask = spoofMask, spoofLat = spoofLat, spoofLon = spoofLon,
    tempRaw = tempRaw ?: Double.NaN, tempCorr = tempCorr ?: Double.NaN, humidity = humidity,
    pressureHpa = pressureHpa, altBaro = altBaro ?: Double.NaN,
    battV = battMv / 1000.0, battMa = 0.0, battPercent = battPct,
    steps = steps.toInt(), headingDeg = headingDeg, src = srcOfCode(src), markId = null,
)

internal fun MarkMsg.toMark() = Mark(
    id = id, tsUtcMs = timeUtc * 1000,
    type = MarkType.entries.getOrElse(type) { MarkType.MARK },
    text = text, lat = lat, lon = lon,
)

private fun srcOfCode(s: Int) = when (s) { 1 -> PositionSource.GPS; 2 -> PositionSource.PDR; 3 -> PositionSource.PAUSE; else -> PositionSource.NONE }
