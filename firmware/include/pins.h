// Раскладка пинов (ТЗ, раздел 3). Сверить с шелкографией платы до пайки!
#pragma once

// I²C: BME280, MPU6050, QMC5883L, INA219, OLED
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

// Часы DS1302 (3-проводной интерфейс, не I²C)
constexpr int PIN_RTC_CLK = 14;
constexpr int PIN_RTC_DAT = 15;
constexpr int PIN_RTC_RST = 7;

// Встроенный RGB-светодиод WS2812 на S3-Zero (у Waveshare GPIO21 — проверить по плате)
constexpr int PIN_RGB = 21;
