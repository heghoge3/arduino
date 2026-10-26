package com.vabugeode.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.LinkManager
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

    val repo = ProtocolDeviceRepository(
        viewModelScope, virtualTransport,
        listOf(
            BlacklistPoint(60.0346, 30.5693, 1000, "Лес у ручья Генерального"),
            BlacklistPoint(60.1161, 31.3864, 1000, "Ладога, о. Кареджи"),
        ),
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

    init {
        repo.phonePosition = ::phonePosition
    }

    /** Последняя известная позиция телефона (вышки, Wi-Fi, GPS) — для проверки подмены на гаджете. */
    private fun phonePosition(): Triple<Double, Double, Int>? {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = try {
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                // Свежая и точная лучше; старше 30 мин не берём
                .filter { System.currentTimeMillis() - it.time < 30 * 60_000 }
                .minByOrNull { it.accuracy }
        } catch (_: SecurityException) {
            null
        } ?: return null
        return Triple(loc.latitude, loc.longitude, loc.accuracy.roundToInt().coerceIn(1, 65535))
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
