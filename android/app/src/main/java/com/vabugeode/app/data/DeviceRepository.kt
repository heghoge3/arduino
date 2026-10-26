package com.vabugeode.app.data

import com.vabugeode.app.model.BlacklistPoint
import com.vabugeode.app.model.DeviceInfo
import com.vabugeode.app.model.DeviceStatus
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.model.TrackPoint
import kotlinx.coroutines.flow.StateFlow

/**
 * Источник данных об устройстве. Сейчас — демо-симулятор,
 * после прихода железа — реализация поверх BLE с тем же интерфейсом.
 */
interface DeviceRepository {
    val link: StateFlow<LinkState>
    val status: StateFlow<DeviceStatus?>
    val track: StateFlow<List<TrackPoint>>
    val marks: StateFlow<List<Mark>>
    val info: StateFlow<DeviceInfo?>
    val blacklist: StateFlow<List<BlacklistPoint>>

    /** Время старта текущего похода (UTC, мс). */
    val hikeStartMs: StateFlow<Long?>

    fun addMark(type: MarkType, text: String = "")

    /** «Хлопушка»: писк + вспышка на устройстве и метка SYNC. */
    fun clap()

    fun setRecording(on: Boolean)

    /** «Найти устройство» — писк. */
    fun findDevice()
}
