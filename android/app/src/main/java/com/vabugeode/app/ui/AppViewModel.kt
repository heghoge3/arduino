package com.vabugeode.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.BlacklistPoint
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

class AppViewModel : ViewModel() {

    /**
     * Пока железа нет — виртуальное устройство. Данные идут тем же путём, что и с ESP32:
     * устройство → байты протокола → транспорт → репозиторий → экраны.
     * После прихода платы здесь появится выбор: виртуальное или BLE.
     */
    val demo = DemoDevice(viewModelScope)
    val transport = VirtualTransport(viewModelScope, demo)
    val repo = ProtocolDeviceRepository(
        viewModelScope, transport,
        listOf(
            BlacklistPoint(60.0346, 30.5693, 1000, "Лес у ручья Генерального"),
            BlacklistPoint(60.1161, 31.3864, 1000, "Ладога, о. Кареджи"),
        ),
    )

    init {
        repo.connect()
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
