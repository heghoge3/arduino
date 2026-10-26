package com.vabugeode.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.LinkManager
import com.vabugeode.app.data.PhoneLocation
import com.vabugeode.app.data.Source
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.store.GadgetRow
import com.vabugeode.app.store.StoreDb
import com.vabugeode.app.store.saveBeacon
import com.vabugeode.app.watch.WatchPrefs
import com.vabugeode.app.watch.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.data.SharedLinkPrefs
import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.BlacklistPoint
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.transport.BleScanner
import com.vabugeode.app.transport.BleTransport
import com.vabugeode.app.transport.GadgetScanner
import com.vabugeode.app.transport.VirtualScanner
import com.vabugeode.app.transport.VirtualTransport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import com.vabugeode.app.data.HikeSummary
import com.vabugeode.app.data.loadMarks
import com.vabugeode.app.data.loadTrack
import com.vabugeode.app.data.summarize
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.store.HikeRow
import com.vabugeode.app.store.MarkRow
import com.vabugeode.app.store.PointRow
import com.vabugeode.app.transport.VIRTUAL_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Источник данных — эмулятор или настоящий гаджет по BLE. Путь данных один:
     * устройство → байты протокола → транспорт → репозиторий → экраны.
     */
    val demo = DemoDevice(viewModelScope)
    private val virtualTransport = VirtualTransport(viewModelScope, demo)

    private val dao = StoreDb.get(app).dao()

    val repo = ProtocolDeviceRepository(
        viewModelScope, virtualTransport,
        listOf(
            BlacklistPoint(60.0346, 30.5693, 1000, "Лес у ручья Генерального"),
            BlacklistPoint(60.1161, 31.3864, 1000, "Ладога, о. Кареджи"),
        ),
        store = dao,
    )

    private var bleScanner: GadgetScanner? = null

    val link = LinkManager(
        scope = viewModelScope,
        repo = repo,
        prefs = SharedLinkPrefs(app),
        virtualScanner = VirtualScanner(viewModelScope, demo) { repo.currentTransport === virtualTransport && repo.link.value != LinkState.DISCONNECTED },
        virtualTransport = virtualTransport,
        bleScanner = { bleScanner ?: BleScanner(app, viewModelScope).also { bleScanner = it } },
        bleTransport = { id, name -> BleTransport(app, viewModelScope, id, name) },
    )

    /** Последний маячок выбранного гаджета и где был телефон («где гаджет») — из базы */
    @OptIn(ExperimentalCoroutinesApi::class)
    val where: StateFlow<GadgetRow?> = link.selectedId
        .flatMapLatest { id -> id?.let { dao.gadgetFlow(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Фоновое наблюдение включено */
    val watchEnabled = MutableStateFlow(WatchPrefs(app).enabled)

    init {
        // Позиция телефона — гаджету для проверки подмены (сверка с телефоном)
        repo.phonePosition = { PhoneLocation.last(app)?.let { Triple(it.lat, it.lon, it.accuracyM) } }
        // Маячки, услышанные с открытым приложением, тоже запоминаем для «где гаджет»
        viewModelScope.launch {
            var lastMs = 0L
            link.lastBeacon.collect { g ->
                if (g == null || g.seenAtMs == lastMs) return@collect
                lastMs = g.seenAtMs
                val pos = PhoneLocation.last(app, maxAgeMs = 5 * 60_000)
                launch(Dispatchers.IO) { dao.saveBeacon(g.id, Codec.encodeBeacon(g.beacon), g.seenAtMs, pos?.lat, pos?.lon, pos?.accuracyM) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = app.getSharedPreferences("demo", 0)
            if (link.source.value == Source.EMULATOR && !prefs.getBoolean("seeded", false)) {
                seedDemoHikes()
                prefs.edit().putBoolean("seeded", true).apply()
            }
        }
        // Наблюдение было включено — убедиться, что сервис работает (XOS мог его убить)
        if (watchEnabled.value && link.source.value == Source.GADGET && link.selectedId.value != null) WatchService.start(app)
    }

    // ---- Походы ----

    private val summaryCache = HashMap<Pair<String, Long>, Pair<Int, HikeSummary>>()

    /** Все сохранённые походы. Сводку пересчитываем только у тех, где прибавились точки */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hikes: StateFlow<List<HikeSummary>?> = combine(dao.hikeKeys(), dao.hikeNames()) { keys, names -> keys to names }
        .mapLatest { (keys, names) ->
            val named = names.associate { (it.device to it.hike) to it.name }
            keys.map { k ->
                val id = k.device to k.hike
                val cached = summaryCache[id]
                val sum = if (cached != null && cached.first == k.n) cached.second
                else summarize(k.device, k.hike, null, dao.loadTrack(k.device, k.hike), dao.loadMarks(k.device, k.hike))
                    .also { summaryCache[id] = k.n to it }
                sum.copy(name = named[id])
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun loadHike(device: String, hike: Long): Pair<List<TrackPoint>, List<Mark>> = withContext(Dispatchers.IO) {
        dao.loadTrack(device, hike) to dao.loadMarks(device, hike)
    }

    /** Поход, который сейчас пишет подключённый гаджет — его не удаляем, он тут же скачается снова */
    fun isCurrent(h: HikeSummary) = repo.currentTransport.key == h.device && repo.hikeStartMs.value == h.hike * 1000

    fun renameHike(h: HikeSummary, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank()) dao.deleteHikeName(h.device, h.hike) else dao.putHike(HikeRow(h.device, h.hike, name.trim()))
        }
    }

    fun deleteHike(h: HikeSummary) {
        if (isCurrent(h)) return
        viewModelScope.launch(Dispatchers.IO) { dao.deleteHike(h.device, h.hike) }
    }

    /** Эмулятор: пара прошлых походов в базе, чтобы список не был пустым */
    private suspend fun seedDemoHikes() {
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
        val have = dao.hikeKeys().first().filter { it.device == VIRTUAL_ID }.map { it.hike }
        listOf(3L to 300, 8L to 180).forEach { (daysAgo, minutes) ->
            // Уже есть поход эмулятора в этот день — не дублируем
            val dayStart = today.minusDays(daysAgo).atStartOfDay(ZoneId.of("Europe/Moscow")).toEpochSecond()
            if (have.any { it in dayStart until dayStart + 86_400 }) return@forEach
            val d = DemoDevice(viewModelScope, autoStart = false)
            d.restart(historyMin = minutes, day = today.minusDays(daysAgo), live = false)
            val hike = d.hikeStartUtcSec
            val (pts, mks) = d.recorded()
            dao.putPoints(pts.map { PointRow(VIRTUAL_ID, hike, it.timeUtc, Codec.encode(it)) })
            dao.putMarks(mks.map { MarkRow(VIRTUAL_ID, hike, it.id, Codec.encode(it)) })
        }
    }

    fun setWatch(on: Boolean) {
        val app = getApplication<Application>()
        if (on) WatchService.start(app) else WatchService.stop(app)
        watchEnabled.value = on
    }

    /**
     * Закат и сумерки для дня похода по текущей позиции.
     * Координаты округляем до ~10 км, чтобы не пересчитывать на каждый шаг.
     */
    val sun: StateFlow<SunCalc.SunTimes?> = combine(repo.hikeStartMs, repo.status) { start, st ->
        val lat = st?.lat ?: return@combine null
        val lon = st.lon ?: return@combine null
        Triple(start ?: st.timeUtcMs, (lat * 10).roundToInt(), (lon * 10).roundToInt())
    }
        .distinctUntilChanged()
        .map { key ->
            key ?: return@map null
            val date = Instant.ofEpochMilli(key.first).atZone(ZoneId.systemDefault()).toLocalDate()
            SunCalc.times(date, key.second / 10.0, key.third / 10.0)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
