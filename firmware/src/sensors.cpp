#include "sensors.h"

#include <Adafruit_BME280.h>
#include <Adafruit_INA219.h>
#include <Arduino.h>
#include <Wire.h>
#include <math.h>

#include "clock.h"
#include "dlog.h"
#include "config.h"
#include "pins.h"

namespace sensors {

namespace {

constexpr uint8_t ADDR_INA = 0x40;
constexpr uint8_t ADDR_MPU = 0x68;
constexpr uint8_t ADDR_OLED = 0x3C;
constexpr uint8_t ADDR_QMC = 0x0D;
constexpr uint8_t ADDR_QMC_P = 0x2C;
constexpr uint8_t ADDR_HMC = 0x1E;

Adafruit_BME280 bme;
Adafruit_INA219 ina(ADDR_INA);
Presence g_pres{};
float g_seaLevel = DEF_SEA_LEVEL_HPA;

uint8_t readReg(uint8_t addr, uint8_t reg) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return 0;
  if (Wire.requestFrom(addr, (uint8_t)1) != 1) return 0;
  return Wire.read();
}

void writeReg(uint8_t addr, uint8_t reg, uint8_t v) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(v);
  Wire.endTransmission();
}

// MPU6050: акселерометр ±2 g, фильтр 44 Гц, гироскоп в standby (экономия)
void mpuInit() {
  writeReg(ADDR_MPU, 0x6B, 0x00);  // PWR_MGMT_1: проснуться
  delay(5);
  writeReg(ADDR_MPU, 0x6C, 0x07);  // PWR_MGMT_2: гироскоп выкл
  writeReg(ADDR_MPU, 0x1A, 0x03);  // CONFIG: DLPF 44 Гц
  writeReg(ADDR_MPU, 0x1C, 0x00);  // ACCEL_CONFIG: ±2 g
  writeReg(ADDR_MPU, 0x38, 0x00);  // прерывания выкл, пока не спим
}

float mpuTemp() {
  Wire.beginTransmission(ADDR_MPU);
  Wire.write(0x41);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(ADDR_MPU, (uint8_t)2) != 2) return NAN;
  int16_t raw = (Wire.read() << 8) | Wire.read();
  return raw / 340.0f + 36.53f;
}

void detect() {
  g_pres = Presence{};
  g_pres.oled = i2cProbe(ADDR_OLED);
  g_pres.ina = i2cProbe(ADDR_INA);
  g_pres.mpu = i2cProbe(ADDR_MPU);
  for (uint8_t a : {(uint8_t)0x76, (uint8_t)0x77}) {
    if (i2cProbe(a)) {
      g_pres.bme = true;
      g_pres.bmeAddr = a;
      g_pres.bmeChipId = readReg(a, 0xD0);
      g_pres.bmeIdOk = g_pres.bmeChipId == 0x60;  // 0x58 — это BMP280 без влажности
      break;
    }
  }
  if (i2cProbe(ADDR_QMC)) { g_pres.compass = true; g_pres.compassAddr = ADDR_QMC; }
  else if (i2cProbe(ADDR_QMC_P) && readReg(ADDR_QMC_P, 0x00) == 0x80) {
    g_pres.compass = true;
    g_pres.compassAddr = ADDR_QMC_P;
  }
  else if (i2cProbe(ADDR_HMC)) { g_pres.compass = true; g_pres.compassAddr = ADDR_HMC; }
}

#ifdef FAKE_SENSORS
// ---- Эмуляция: плата без датчиков ----
float fakeTemp() {
  // Местное время ≈ UTC+3: после 17 ч температура уходит вниз
  float h = fmodf((clockx::now() % 86400) / 3600.0f + 3.0f, 24.0f);
  float evening = h >= 17 ? fminf((h - 17) / 3.0f, 1.0f) : (h < 6 ? 1.0f : 0.0f);
  return 6.5f - 6.0f * evening + 0.2f * sinf(millis() / 60000.0f);
}
#endif

}  // namespace

bool i2cProbe(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

void begin(bool coldBoot) {
  Wire.begin(PIN_SDA, PIN_SCL, 400000);
#ifdef FAKE_SENSORS
  g_pres = Presence{true, true, true, true, i2cProbe(ADDR_OLED), true, 0x76, ADDR_QMC, 0x60};
  (void)coldBoot;
  return;
#else
  detect();
  if (g_pres.bme && g_pres.bmeIdOk && bme.begin(g_pres.bmeAddr, &Wire)) {
    // Принудительный режим: меряет по запросу и сразу засыпает (~0,1 мкА)
    bme.setSampling(Adafruit_BME280::MODE_FORCED, Adafruit_BME280::SAMPLING_X2, Adafruit_BME280::SAMPLING_X4,
                    Adafruit_BME280::SAMPLING_X1, Adafruit_BME280::FILTER_OFF);
  }
  if (g_pres.ina && ina.begin(&Wire)) ina.setCalibration_16V_400mA();
  if (g_pres.mpu) mpuInit();
  if (g_pres.compass && g_pres.compassAddr == ADDR_QMC) {
    // Компас пока не используется (счисление по шагам — следующий этап): держим в standby
    writeReg(ADDR_QMC, 0x0B, 0x01);
    writeReg(ADDR_QMC, 0x09, 0x00);
  }
  if (g_pres.compass && g_pres.compassAddr == ADDR_QMC_P) {
    // QMC5883P: другой адрес и регистр режима, пока тоже standby.
    writeReg(ADDR_QMC_P, 0x0A, 0x00);
  }
  (void)coldBoot;
#endif
}

const Presence& presence() { return g_pres; }

void rescan() {
#ifndef FAKE_SENSORS
  bool hadBme = g_pres.bme && g_pres.bmeIdOk;
  bool hadIna = g_pres.ina;
  bool hadMpu = g_pres.mpu;
  detect();
  // Датчик появился (переподключили провод) — запустить заново
  if ((!hadBme && g_pres.bme && g_pres.bmeIdOk) || (!hadIna && g_pres.ina) || (!hadMpu && g_pres.mpu)) begin(false);
#endif
}

Env readEnv() {
  Env e{};
  e.tempInternal = temperatureRead();
#ifdef FAKE_SENSORS
  e.ok = true;
  e.tempCorr = fakeTemp();
  e.tempRaw = e.tempCorr + 1.5f;
  e.hum = fminf(95, 60 + (6.5f - e.tempCorr) * 5);
  e.pressHpa = 1012.0f - (millis() / 3600000.0f) * 0.8f;
#else
  if (!(g_pres.bme && g_pres.bmeIdOk) || !bme.takeForcedMeasurement()) {
    e.ok = false;
    e.tempRaw = e.tempCorr = e.hum = e.pressHpa = e.altBaro = NAN;
    return e;
  }
  e.ok = true;
  e.tempRaw = bme.readTemperature();
  e.hum = bme.readHumidity();
  e.pressHpa = bme.readPressure() / 100.0f;
  // Перегрев корпуса: оцениваем по внутренним датчикам (ESP32-S3 и MPU6050) против BME280.
  // Коэффициент — стартовый, подобрать по замерам (ТЗ, раздел 5)
  float inner = e.tempInternal;
  float mt = g_pres.mpu ? mpuTemp() : NAN;
  if (!isnan(mt)) inner = (inner + mt) / 2;
  float heat = TEMP_SELF_HEAT_K * (inner - e.tempRaw);
  if (heat < 0 || isnan(heat)) heat = 0;
  e.tempCorr = e.tempRaw - heat;
#endif
  e.altBaro = 44330.0f * (1.0f - powf(e.pressHpa / g_seaLevel, 1.0f / 5.255f));
  return e;
}

uint8_t batteryPercent(float v) {
  // Кривая разряда Li-ion в покое: напряжение → %
  static const float V[] = {3.30f, 3.50f, 3.62f, 3.68f, 3.72f, 3.75f, 3.79f, 3.84f, 3.90f, 3.97f, 4.05f, 4.15f};
  static const uint8_t P[] = {0, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
  if (v <= V[0]) return 0;
  for (int i = 1; i < 12; i++) {
    if (v <= V[i]) return P[i - 1] + (uint8_t)((v - V[i - 1]) / (V[i] - V[i - 1]) * (P[i] - P[i - 1]));
  }
  return 100;
}

Power readPower() {
  Power p{};
#ifdef FAKE_SENSORS
  p.ok = true;
  float hours = millis() / 3600000.0f;
  p.voltage = 4.05f - hours * 0.02f;
  p.currentMa = 35.0f;
#else
  if (!g_pres.ina) {
    p.ok = false;
    p.voltage = NAN;
    p.currentMa = NAN;
    p.pct = 100;  // не знаем — не уходим в экономию
    return p;
  }
  ina.powerSave(false);
  delay(2);
  p.ok = true;
  // Напряжение на аккумуляторе = после шунта + падение на шунте
  p.voltage = ina.getBusVoltage_V() + ina.getShuntVoltage_mV() / 1000.0f;
  p.currentMa = ina.getCurrent_mA();
  static bool warned = false;
  if (p.currentMa < -5 && !warned) {
    warned = true;
    LOGW(M_SENS, "INA219: ток %.1f мА — отрицательный, VIN+ и VIN- перепутаны?", p.currentMa);
  }
#endif
  p.pct = batteryPercent(p.voltage);
  return p;
}

bool readAccel(float& ax, float& ay, float& az) {
#ifdef FAKE_SENSORS
  // Ходьба 10 мин, стоим 3 мин: синус 1,8 Гц по вертикали
  uint32_t t = millis();
  bool walking = (t / 1000) % 780 < 600;
  ax = 0.02f;
  ay = 0.01f;
  az = 1.0f + (walking ? 0.35f * sinf(2 * PI * 1.8f * t / 1000.0f) : 0.0f);
  return true;
#else
  if (!g_pres.mpu) return false;
  Wire.beginTransmission(ADDR_MPU);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(ADDR_MPU, (uint8_t)6) != 6) return false;
  int16_t x = (Wire.read() << 8) | Wire.read();
  int16_t y = (Wire.read() << 8) | Wire.read();
  int16_t z = (Wire.read() << 8) | Wire.read();
  ax = x / 16384.0f;
  ay = y / 16384.0f;
  az = z / 16384.0f;
  return true;
#endif
}

void prepareSleep() {
#ifndef FAKE_SENSORS
  if (g_pres.mpu) {
    // Прерывание движения в циклическом режиме (порядок по InvenSense AN; проверить на железе):
    writeReg(ADDR_MPU, 0x6B, 0x00);
    writeReg(ADDR_MPU, 0x1C, 0x01);  // ACCEL_HPF 5 Гц — реагировать на изменение, не на гравитацию
    writeReg(ADDR_MPU, 0x1F, 3);     // MOT_THR: ~96 мг
    writeReg(ADDR_MPU, 0x20, 1);     // MOT_DUR: 1 мс
    writeReg(ADDR_MPU, 0x37, 0x20);  // INT: активный HIGH, защёлка до чтения INT_STATUS
    writeReg(ADDR_MPU, 0x38, 0x40);  // включить только MOT_INT
    readReg(ADDR_MPU, 0x3A);         // сбросить старое
    writeReg(ADDR_MPU, 0x6C, 0x07);  // LP_WAKE 1,25 Гц, гироскоп выкл
    writeReg(ADDR_MPU, 0x6B, 0x28);  // CYCLE=1, датчик температуры выкл
  }
  if (g_pres.ina) ina.powerSave(true);
#endif
}

void clearMotion() {
#ifndef FAKE_SENSORS
  if (g_pres.mpu) {
    readReg(ADDR_MPU, 0x3A);
    mpuInit();
  }
#endif
}

void setSeaLevel(float hpa) { g_seaLevel = hpa; }
float seaLevel() { return g_seaLevel; }

void calibrateAltitude(float knownAltM) {
  Env e = readEnv();
  if (!e.ok) return;
  g_seaLevel = e.pressHpa / powf(1.0f - knownAltM / 44330.0f, 5.255f);
}

}  // namespace sensors
