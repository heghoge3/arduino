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
        // Наблюдение было включено — убедиться, что сервис работает (XOS мог его убить)
        if (watchEnabled.value && link.source.value == Source.GADGET && link.selectedId.value != null) WatchService.start(app)
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
