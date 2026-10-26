package com.vabugeode.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vabugeode.app.astro.SunCalc
import com.vabugeode.app.data.DeviceRepository
import com.vabugeode.app.demo.DemoDevice
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

    /** Пока железа нет — демо. Потом здесь будет выбор между демо и BLE. */
    val demo = DemoDevice(viewModelScope)
    val repo: DeviceRepository = demo

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
