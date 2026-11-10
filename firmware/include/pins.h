// Раскладка пинов (ТЗ, раздел 3). Сверить с шелкографией платы до пайки!
#pragma once

// I²C: BME280, MPU6050, компас, INA219, LCD ST7567, DS3231
constexpr int PIN_SDA = 8;
constexpr int PIN_SCL = 9;

// GPS ATGM336H на Serial1. Отладка идёт через USB CDC
constexpr int PIN_GPS_RX = 44;  // вход ESP32 ← TX модуля
constexpr int PIN_GPS_TX = 43;  // выход ESP32 → RX модуля
constexpr int PIN_GPS_EN = 6;   // затвор AO3401: LOW = GPS включён

// SD (SPI)
constexpr int PIN_SD_CS = 10;
constexpr int PIN_SD_MOSI = 11;
constexpr int PIN_SD_SCK = 12;
constexpr int PIN_SD_MISO = 13;

// Кнопки (на GND, подтяжка внутренняя). Только RTC-GPIO — будят из сна
constexpr int PIN_BTN_A = 1;
constexpr int PIN_BTN_B = 2;

// MPU6050 INT — прерывание движения, будит из сна
constexpr int PIN_MPU_INT = 4;

// Пищалка
constexpr int PIN_BUZZER = 5;

// DS3231 подключён к общей I2C; прежние GPIO DS1302 больше не используются.
// V2 HOLD: GPIO7 -> анод D2 -> общая точка EN (катод D2).
// DS1302 удалён; GPIO7 свободен и поддерживает RTC hold.
// -1 отключает узел питания для стенда от независимого питания.
#ifndef V2_POWER_HOLD_PIN
#define V2_POWER_HOLD_PIN 7
#endif
constexpr int PIN_POWER_HOLD=V2_POWER_HOLD_PIN;

// Встроенный RGB-светодиод WS2812 на S3-Zero (у Waveshare GPIO21 — проверить по плате)
constexpr int PIN_RGB = 21;

// Владелец исключил труднодоступные нижние площадки из подключений V2.
constexpr bool v2ForbiddenPin(int pin) {
  return pin==45 || pin==42 || pin==41 || pin==40 || pin==39 || pin==38 ||
         pin==18 || pin==17 || pin==16 || pin==15 || pin==14;
}
static_assert(!v2ForbiddenPin(PIN_SDA) && !v2ForbiddenPin(PIN_SCL) &&
 !v2ForbiddenPin(PIN_GPS_RX) && !v2ForbiddenPin(PIN_GPS_TX) && !v2ForbiddenPin(PIN_GPS_EN) &&
 !v2ForbiddenPin(PIN_SD_CS) && !v2ForbiddenPin(PIN_SD_MOSI) && !v2ForbiddenPin(PIN_SD_SCK) &&
 !v2ForbiddenPin(PIN_SD_MISO) && !v2ForbiddenPin(PIN_BTN_A) && !v2ForbiddenPin(PIN_BTN_B) &&
 !v2ForbiddenPin(PIN_MPU_INT) && !v2ForbiddenPin(PIN_BUZZER) && !v2ForbiddenPin(PIN_POWER_HOLD),
 "V2 external wiring must not use inaccessible bottom pads");
static_assert(PIN_POWER_HOLD==-1 || PIN_POWER_HOLD==7,"V2 HOLD is assigned to GPIO7 only");
