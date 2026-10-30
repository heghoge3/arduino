// Датчики на общей шине I²C: BME280, INA219, MPU6050, компас (только наличие), OLED (наличие).
// В сборке FAKE_SENSORS все значения эмулируются — для платы без датчиков.
#pragma once
#include <stdint.h>

namespace sensors {

struct Env {
  bool ok;
  float tempRaw;     // BME280 как есть (греется от электроники в корпусе)
  float tempCorr;    // с поправкой на перегрев
  float hum;
  float pressHpa;
  float altBaro;     // от давления на уровне моря (калибруется)
  float tempInternal;
};

struct Power {
  bool ok;
  float voltage;
  float currentMa;   // плюс — разряд
  uint8_t pct;
  // Около нуля бывает при USB-питании без аккумулятора или обрыве измерительной цепи.
  // Это неизвестный заряд, а не разряженная батарея; источник питания так не определяем.
  bool batteryKnown() const { return ok && voltage >= 0.5f && voltage <= 6.0f; }
  uint8_t controlPercent() const { return batteryKnown() ? pct : 100; }
};

struct Presence {
  bool bme, bmeIdOk, ina, mpu, oled, compass;
  uint8_t bmeAddr, compassAddr, bmeChipId;
};

// Запуск шины и датчиков. coldBoot — после включения (полная проверка), иначе после сна (быстро)
void begin(bool coldBoot);
const Presence& presence();
// Перепроверить шину (самопроверка раз в минуту)
void rescan();

Env readEnv();
Power readPower();
// Ускорение в g. false — MPU6050 не отвечает
bool readAccel(float& ax, float& ay, float& az);
// Пока поддерживается QMC5883P. Для направления плата компаса должна быть горизонтальна.
void pollCompass(float offsetDeg, bool inverted);
float compassHeading(); // NAN — нет калибровки, сильный наклон, помеха или устаревшее чтение
bool calibrateCompass(bool start);
bool compassCalibrating();
bool compassReady();

// Проверка защёлкнутого прерывания движения без сна ESP32.
bool armMotion(bool sensitive = false);
struct MotionStatus { uint8_t enabled, config, power, status, who, accel, threshold, duration, control; };
bool readMotion(MotionStatus& out);  // чтение INT_STATUS сбрасывает защёлку
void stopMotion();

// Перед глубоким сном: MPU6050 в режим «будить при движении», остальное в сон
void prepareSleep();
// Сбросить защёлку прерывания движения (после пробуждения)
void clearMotion();

// Калибровка высоты по известной точке; давление на уровне моря хранится в NVS
void calibrateAltitude(float knownAltM);
void setSeaLevel(float hpa);
float seaLevel();

bool i2cProbe(uint8_t addr);

// Процент заряда по напряжению Li-ion (в покое)
uint8_t batteryPercent(float volts);

}  // namespace sensors
