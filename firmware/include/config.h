// Настройки прошивки. Всё, что может поменяться после первых тестов, — здесь.
#pragma once
#include <stdint.h>

// Версия прошивки (уходит в INFO)
constexpr uint8_t FW_MAJOR = 0;
constexpr uint8_t FW_MINOR = 1;
constexpr uint8_t FW_PATCH = 11;

// Пищалка: тип выяснится при сборке (ТЗ, раздел 2).
// 1 — активная (просто подать HIGH), 0 — пассивная (тон через ledc)
#ifndef BUZZER_ACTIVE
#define BUZZER_ACTIVE 1
#endif
constexpr uint16_t BUZZER_TONE_HZ = 2700;  // для пассивной

// Кнопки
constexpr uint32_t BTN_LONG_MS = 1500;
constexpr uint32_t BTN_DEBOUNCE_MS = 30;

// Экран: включается по кнопке B на столько
constexpr uint32_t SCREEN_ON_MS = 10000;

// Запись (по умолчанию; часть меняется командами с телефона и хранится в NVS)
constexpr uint16_t DEF_LOG_MOVING_S = 10;
constexpr uint16_t DEF_LOG_CAMP_S = 60;
constexpr uint16_t DEF_BEACON_S = 30;     // маячок в пути; на стоянке ×2, в экономии ×4
constexpr uint16_t DEF_GPS_PULSE_S = 30;
constexpr uint32_t SD_FLUSH_S = 900;      // на стоянке копим в RTC-памяти, сбрасываем раз в 15 мин

// Радио
constexpr uint32_t BEACON_BURST_MS = 300;      // короткий маячок ради экономии; срочное подключение — кнопкой B
constexpr uint16_t BEACON_ADV_INTERVAL_MS = 50;
constexpr uint32_t WINDOW_MS = 120000;         // окно после кнопки B / тревоги
constexpr uint16_t WINDOW_ADV_INTERVAL_MS = 100;
constexpr uint32_t SESSION_TIMEOUT_MS = 60000; // телефон молчит — рвём связь

// Батарея
constexpr float BATT_EMPTY_V = 3.30f;
constexpr float BATT_FULL_V = 4.15f;
constexpr float BATT_CAPACITY_MAH = 2000.0f;   // 18650 на тестах ~2000–3000; LiPo в расчётах 2000

// Перегрев корпуса: поправка температуры = k × (внутренняя температура − BME280)
constexpr float TEMP_SELF_HEAT_K = 0.0f;       // поправка выключена до калибровки в собранном корпусе

// Высота: давление на уровне моря по умолчанию (калибруется командой CALIB_ALT)
constexpr float DEF_SEA_LEVEL_HPA = 1013.25f;
