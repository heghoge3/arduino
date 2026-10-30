package com.vabugeode.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vabugeode.app.astro.SunSchedule
import com.vabugeode.app.data.LinkManager
import com.vabugeode.app.data.BlacklistSettings
import com.vabugeode.app.log.AppLog
import com.vabugeode.app.log.LogText
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
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Источник данных — эмулятор или настоящий гаджет по BLE. Путь данных один:
     * устройство → байты протокола → транспорт → репозиторий → экраны.
     */
    val demo = DemoDevice(viewModelScope)
    private val virtualTransport = VirtualTransport(viewModelScope, demo)

    private val dao = StoreDb.get(app).dao().also { AppLog.start(it) }
    private val blacklistSettings = BlacklistSettings(app.getSharedPreferences("blacklist", 0))

    val repo = ProtocolDeviceRepository(
        viewModelScope, virtualTransport,
        blacklistSettings.load(listOf(
            BlacklistPoint(60.0346, 30.5693, 5000, "Лес у ручья Генерального"),
            BlacklistPoint(60.1161, 31.3864, 5000, "Ладога, о. Кареджи"),
        )),
        store = dao,
    )

    fun setBlacklistRadius(index: Int, radiusM: Int) {
        repo.setBlacklistRadius(index, radiusM)
        blacklistSettings.save(repo.blacklist.value)
    }

    private val pdrPrefs = app.getSharedPreferences("pdr", 0)
    val pdrSettings = MutableStateFlow(com.vabugeode.app.protocol.Cmd.PdrSettings(
        pdrPrefs.getInt("stride", 75).coerceIn(30, 150),
        pdrPrefs.getInt("heading", 0).coerceIn(-1800, 1800), pdrPrefs.getBoolean("inverted", false),
    ))
    fun setPdrSettings(strideCm: Int, heading10: Int, inverted: Boolean) {
        repo.setPdrSettings(strideCm, heading10, inverted)
        pdrSettings.value = repo.pdrSettings
        pdrPrefs.edit().putInt("stride", strideCm).putInt("heading", heading10).putBoolean("inverted", inverted).apply()
    }

    val pdrAnchorMessage = MutableStateFlow<String?>(null)
    fun seedPdrFromPhone() {
        if (repo.link.value != LinkState.CONNECTED) return
        val transport = repo.currentTransport
        pdrAnchorMessage.value = "Уточняю позицию телефона…"
        viewModelScope.launch {
            val fix = PhoneLocation.refresh(getApplication(), repo.blacklist.value)
            if (repo.currentTransport !== transport || repo.link.value != LinkState.CONNECTED) return@launch
            if (fix == null || fix.accuracyM > 1000) {
                pdrAnchorMessage.value = "Нет свежей позиции телефона. Проверьте геолокацию и разрешение; начало маршрута пока неизвестно."
            } else {
                val ok=repo.setStart(fix.lat,fix.lon)
                pdrAnchorMessage.value = if (ok) "Старт задан по телефону, точность ±${fix.accuracyM} м" else "Старт не подтверждён гаджетом"
            }
        }
    }

    fun setTemperatureOffset(value: Int) { viewModelScope.launch { repo.setTemperatureOffset(value) } }
    fun setManualStart(lat: Double,lon: Double,onResult: (String)->Unit) {
        viewModelScope.launch { onResult(if (repo.setStart(lat,lon)) "Старт задан вручную" else "Не удалось задать старт") }
    }
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

    private val achievementStride = pdrSettings.map {it.strideCm/100.0}
        .stateIn(viewModelScope, SharingStarted.Eagerly, pdrSettings.value.strideCm/100.0)
    val achievements = com.vabugeode.app.achievements.AchievementTracker(dao,viewModelScope,repo.blacklist,achievementStride)
    val achievementHikes = dao.achievementSelections().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val achievementNotices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity=8)
    private val statisticsLock=kotlinx.coroutines.sync.Mutex()
    fun includesStatistics(h:HikeSummary) = achievementHikes.value.any {it.device==h.device&&it.hike==h.hike}
    fun includeStatistics(h:HikeSummary) {
        if(h.demo||repo.hikeBusy.value)return
        viewModelScope.launch(Dispatchers.IO){statisticsLock.withLock{if(!repo.hikeBusy.value)dao.includeAchievementHike(h.device,h.hike)}}
    }
    fun seeAchievement(id:String){viewModelScope.launch(Dispatchers.IO){achievements.see(id)}}

    /** Последний маячок выбранного гаджета и где был телефон («где гаджет») — из базы */
    @OptIn(ExperimentalCoroutinesApi::class)
    val where: StateFlow<GadgetRow?> = link.selectedId
        .flatMapLatest { id -> id?.let { dao.gadgetFlow(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Фоновое наблюдение включено */
    val watchEnabled = MutableStateFlow(WatchPrefs(app).enabled)

    /** Подробный журнал (отладка): гаджет пишет больше и чаще, приложение — тоже */
    val debugLog = MutableStateFlow(app.getSharedPreferences("diag", 0).getBoolean("debug", true))

    fun setDebugLog(on: Boolean) {
        getApplication<Application>().getSharedPreferences("diag", 0).edit().putBoolean("debug", on).apply()
        debugLog.value = on
        AppLog.debug = on
        repo.debugLog = on
        AppLog.i("APP", if (on) "подробный журнал включён" else "подробный журнал выключен")
    }

    /** Журналы для экрана: последние строки гаджета и телефона */
    val gadgetLog = dao.gadgetLogFlow(3000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val appLog = dao.appLogFlow(3000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Оба журнала целиком одним текстом — чтобы поделиться */
    suspend fun exportLogs(): String = withContext(Dispatchers.IO) { LogText.export(dao.allGadgetLog(), dao.allAppLog()) }

    /** Прогноз погоды для места телефона: последний скачанный (в походе интернета может не быть) */
    val weather = MutableStateFlow(com.vabugeode.app.data.Weather.cached(app))

    init {
        viewModelScope.launch {
            var lastChime=0L
            achievements.notices.collect {event ->
                com.vabugeode.app.achievements.AchievementNotifications.post(app,event)
                achievementNotices.tryEmit(event.text)
                val current=repo.currentTransport.key to (repo.hikeStartMs.value?.div(1000)?:0L)
                val now=System.currentTimeMillis()
                if(current in event.liveHikes && now-lastChime>=60_000 && repo.link.value==LinkState.CONNECTED && repo.status.value?.recording==true){
                    repo.achievementChime();lastChime=now
                }
            }
        }
        AppLog.debug = debugLog.value
        repo.debugLog = debugLog.value
        repo.pdrSettings = pdrSettings.value
        AppLog.i("APP", "запуск приложения ${com.vabugeode.app.BuildConfig.VERSION_NAME}")
        // Прогноз: раз в 10 мин проверяем, не старше ли он часа; обновляем, если есть интернет и позиция
        viewModelScope.launch {
            while (true) {
                val age = System.currentTimeMillis() - (weather.value?.fetchedMs ?: 0)
                if (age > 60 * 60_000) {
                    PhoneLocation.last(app, maxAgeMs = 6 * 60 * 60_000)?.let { pos ->
                        com.vabugeode.app.data.Weather.refresh(app, pos.lat, pos.lon)?.let { weather.value = it }
                    }
                }
                kotlinx.coroutines.delay(10 * 60_000)
            }
        }
        // Позиция телефона — гаджету для проверки подмены (сверка с телефоном)
        repo.phonePosition = { PhoneLocation.last(app, repo.blacklist.value, maxAgeMs = 30_000)?.let { Triple(it.lat, it.lon, it.accuracyM) } }
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
    private var summaryConfig: Pair<List<BlacklistPoint>,Int>?=null

    /** Все сохранённые походы. Сводку пересчитываем только у тех, где прибавились точки */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hikes: StateFlow<List<HikeSummary>?> = combine(dao.hikeKeys(), dao.hikeNames()) { keys, names -> keys to names }
        .combine(repo.blacklist) { pair, zones -> Triple(pair.first,pair.second,zones) }
        .combine(pdrSettings) { inputs, settings -> inputs to settings.strideCm }
        .mapLatest { (inputs, strideCm) ->
            val (keys,names,zones)=inputs
            if(summaryConfig != (zones to strideCm)) {summaryCache.clear();summaryConfig=zones to strideCm}
            val named = names.associate { (it.device to it.hike) to it.name }
            keys.map { k ->
                val id = k.device to k.hike
                val cached = summaryCache[id]
                val sum = if (cached != null && cached.first == k.n) cached.second
                else summarize(k.device, k.hike, null, com.vabugeode.app.data.excludeBlacklisted(dao.loadTrack(k.device, k.hike),zones), dao.loadMarks(k.device, k.hike),strideCm/100.0)
                    .also { summaryCache[id] = k.n to it }
                sum.copy(name = named[id])
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun loadHike(device: String, hike: Long): Pair<List<TrackPoint>, List<Mark>> = withContext(Dispatchers.IO) {
        com.vabugeode.app.data.excludeBlacklisted(dao.loadTrack(device, hike),repo.blacklist.value) to com.vabugeode.app.data.excludeBlacklistedMarks(dao.loadMarks(device, hike),repo.blacklist.value)
    }

    /** Поход, который сейчас пишет подключённый гаджет — его не удаляем, он тут же скачается снова */
    fun isCurrent(h: HikeSummary) = repo.currentTransport.key == h.device && repo.hikeStartMs.value == h.hike * 1000

    fun renameHike(h: HikeSummary, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank()) dao.deleteHikeName(h.device, h.hike) else dao.putHike(HikeRow(h.device, h.hike, name.trim()))
        }
    }

    fun newHike() {
        if (repo.hikeBusy.value) return
        if (repo.currentTransport.key == VIRTUAL_ID) { demo.restart(); return }
        val hike = repo.hikeStartMs.value?.div(1000) ?: return
        viewModelScope.launch { repo.manageHike(hike, delete = false) }
    }

    fun deleteHike(h: HikeSummary, fromSd: Boolean, onDeleted: () -> Unit) {
        if (isCurrent(h)) return
        viewModelScope.launch {
          statisticsLock.withLock {
            if (withContext(Dispatchers.IO){dao.isAchievementHike(h.device,h.hike)}) return@withLock
            if (fromSd) {
                if (repo.currentTransport.key != h.device || !repo.manageHike(h.hike, delete = true)) return@withLock
            }
            withContext(Dispatchers.IO) { dao.deleteHike(h.device, h.hike) }
            onDeleted()
          }
        }
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
     * Восходы, закаты и сумерки на весь поход (и на сутки вперёд) по текущей позиции.
     * Координаты округляем до ~10 км, время — до часа, чтобы не пересчитывать на каждый шаг.
     */
    val sun: StateFlow<SunSchedule?> = combine(repo.hikeStartMs, repo.status) { start, st ->
        val lat = st?.lat ?: return@combine null
        val lon = st.lon ?: return@combine null
        listOf((start ?: st.timeUtcMs) / HOUR, st.timeUtcMs / HOUR, (lat * 10).roundToInt().toLong(), (lon * 10).roundToInt().toLong())
    }
        .distinctUntilChanged()
        .map { k ->
            k ?: return@map null
            SunSchedule.compute(k[0] * HOUR, (k[1] + 36) * HOUR, k[2] / 10.0, k[3] / 10.0)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private companion object {
        const val HOUR = 3_600_000L
    }
}
