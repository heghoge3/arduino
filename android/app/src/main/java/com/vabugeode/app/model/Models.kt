package com.vabugeode.app.model

/**
 * Модель данных приложения. Одна и та же для демо-режима и для будущего BLE.
 * Поля повторяют колонки CSV из ТЗ (раздел 5), чтобы потом не переделывать.
 */

/** Режим питания устройства (ТЗ, раздел 4). */
enum class PowerMode(val title: String) {
    DEEP_SLEEP("Сон"),
    IDLE_LOG("Стоянка"),
    MOVING("Движение"),
    BLE_SESSION("Сеанс BLE"),
}

/** Источник позиции точки трека. */
enum class PositionSource(val title: String) {
    GPS("GPS"),
    PDR("По шагам"),
    NONE("Нет"),
}

/** Состояние приёмника GPS. */
enum class GpsFix(val title: String) {
    OFF("Выключен"),
    SEARCHING("Поиск"),
    FIX_2D("2D"),
    FIX_3D("3D"),
}

/** Причины подозрения на подмену. Биты маски spoof_reason (ТЗ, раздел 7). */
enum class SpoofReason(val bit: Int, val title: String) {
    BLACKLIST(0, "Точка из чёрного списка"),
    PHONE_MISMATCH(1, "Расходится с телефоном"),
    IMPOSSIBLE_JUMP(2, "Невозможный скачок"),
    SNR_FLAT(3, "Одинаковый SNR спутников"),
    BARO_MISMATCH(4, "Высота не совпадает с барометром"),
    ACCEL_MISMATCH(5, "Шаги не совпадают с движением");

    companion object {
        fun fromMask(mask: Int): List<SpoofReason> = entries.filter { mask and (1 shl it.bit) != 0 }
        fun toMask(reasons: Collection<SpoofReason>): Int = reasons.fold(0) { acc, r -> acc or (1 shl r.bit) }
    }
}

/** Спутник из GSV: система, номер, сила сигнала, участвует ли в решении. */
data class Satellite(
    val system: String, // "GPS", "BDS", "GLO"
    val prn: Int,
    val snr: Int,       // дБ-Гц, 0 — не слышно
    val used: Boolean,
)

/** Состояние GPS на текущий момент. */
data class GpsState(
    val fix: GpsFix,
    val satsUsed: Int,
    val satsInView: Int,
    val hdop: Double,
    val speedKmh: Double,
    val courseDeg: Double,
    val altGps: Double?,
    val satellites: List<Satellite>,
    val spoof: Boolean,
    val spoofMask: Int,
)

/** Питание по INA219. */
data class PowerState(
    val voltage: Double,
    val currentMa: Double,
    val percent: Int,
    val charging: Boolean,
    val usedMah: Double,
    val hoursLeft: Double,
)

/** Датчики окружения. */
data class EnvState(
    val tempRaw: Double,
    val tempCorr: Double,
    val humidity: Double,
    val pressureHpa: Double,
    val altBaro: Double,
    val pressureTrend3h: Double, // гПа за 3 часа, минус — падает
)

/** Результат самопроверки одного узла (ТЗ, раздел 13). */
enum class CheckLevel { OK, WARN, ERROR }

data class SelfCheck(
    val name: String,
    val detail: String,
    val level: CheckLevel,
    val address: String? = null,
)

/** Сводное состояние устройства — то, что придёт в характеристике Status. */
data class DeviceStatus(
    val timeUtcMs: Long,
    val mode: PowerMode,
    val recording: Boolean,
    val gps: GpsState,
    val env: EnvState,
    val power: PowerState,
    val steps: Int,
    val headingDeg: Double,
    val distanceKm: Double,
    val positionSource: PositionSource,
    val lat: Double?,
    val lon: Double?,
)

/** Одна строка лога трека (CSV на SD). */
data class TrackPoint(
    val tsUtcMs: Long,
    val lat: Double,          // лучшая оценка позиции (GPS или PDR)
    val lon: Double,
    val altGps: Double?,
    val sats: Int,
    val hdop: Double,
    val speedKmh: Double,
    val spoof: Boolean,
    val spoofMask: Int,
    val spoofLat: Double?,    // куда «увёл» GPS при подмене — показываем отдельным слоем
    val spoofLon: Double?,
    val tempRaw: Double,
    val tempCorr: Double,
    val humidity: Double,
    val pressureHpa: Double,
    val altBaro: Double,
    val battV: Double,
    val battMa: Double,
    val battPercent: Int,
    val steps: Int,
    val headingDeg: Double,
    val src: PositionSource,
    val markId: Int?,
)

/** Типы меток для монтажа (ТЗ, раздел 9). */
enum class MarkType(val title: String, val emoji: String) {
    MARK("Метка", "📍"),
    ENTER("Зашли", "🚪"),
    FOUND("Нашли", "🔦"),
    GUARD("Охрана", "🚨"),
    INTERESTING("Интересно", "⭐"),
    SYNC("Хлопушка", "🎬"),
    TEXT("Текст", "✏️"),
}

data class Mark(
    val id: Int,
    val tsUtcMs: Long,
    val type: MarkType,
    val text: String,
    val lat: Double?,
    val lon: Double?,
)

/** Информация об устройстве. */
data class DeviceInfo(
    val name: String,
    val chipModel: String,
    val firmware: String,
    val uptimeSec: Long,
    val sdTotalMb: Int,
    val sdFreeMb: Int,
    val gnssSystems: String,
    val checks: List<SelfCheck>,
)

/** Точка из чёрного списка подмены. */
data class BlacklistPoint(val lat: Double, val lon: Double, val radiusM: Int, val note: String)

/** Подключение к устройству. */
enum class LinkState(val title: String) {
    DISCONNECTED("Нет связи"),
    CONNECTING("Подключение…"),
    CONNECTED("Подключено"),
}
