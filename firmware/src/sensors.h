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
