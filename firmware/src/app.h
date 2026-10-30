// Общее состояние прошивки.
// Persist переживает глубокий сон (копируется в RTC-память), Settings — выключение (NVS).
#pragma once
#include <stdint.h>

#include "logic.h"
#include "proto.h"
#include "sensors.h"
#include "spoof.h"
#include "steps.h"

constexpr uint32_t PERSIST_MAGIC = 0x56424732;  // «VBG2»; поменять при изменении Persist
constexpr uint8_t CAMP_BUF = 15;                // записей на стоянке до сброса на SD

// Настройки (NVS): меняются командами с телефона
struct Settings {
  uint8_t gpsMode;      // logic::GpsMode
  uint16_t gpsPulseS;
  uint16_t beaconS;     // 0 — невидимка
  uint16_t logMovingS;
  uint16_t logCampS;
  int16_t tzMin;        // часовой пояс телефона — для экрана
  float seaLevelHpa;
  bool baroCalibrated;
  bool debug;           // подробный журнал (отладка): по умолчанию включён на первые походы
};

// Всё, что нужно помнить между пробуждениями
struct Persist {
  uint32_t magic;
  uint32_t bootCount;
  uint32_t hikeStart;   // 0 — похода нет
  bool recording;

  logic::PowerLogic power;
  logic::GpsPulse pulse;
  logic::Alerts alerts;
  spoof::Detector spoof;
  steps::Detector stepDet;

  // Последняя достоверная позиция (для записи на стоянке и меток)
  bool haveGood;
  double goodLat, goodLon;
  float goodAlt;
  uint32_t goodTime;  // когда была последняя достоверная точка

  double distM;
  float usedMah;
  float avgMa;
  uint16_t seq;
  uint8_t beaconCounter;
  uint8_t spoofMaskLast;
  uint32_t spoofAlertUntil;

  uint32_t nextLogAt;
  uint32_t nextBeaconAt;
  uint32_t lastFlushAt;
  uint32_t lastPointTime;

  // Тренд давления: отсчёты раз в 15 мин за 3 ч
  float pressHist[13];
  uint32_t pressHistAt;
  uint8_t pressHistN;

  // Буфер записей на стоянке (пакеты POINT)
  uint8_t campN;
  uint8_t campBuf[CAMP_BUF][proto::POINT_SIZE];
};

// Текущее «мгновенное» состояние — для STATUS, маячка, экрана
struct Snapshot {
  uint32_t now;
  sensors::Env env;
  sensors::Power pwr;
  bool gpsOn;
  bool fix;
  uint8_t fixType;
  double lat, lon;      // лучшая оценка позиции
  bool haveLat;
  float altGps;
  bool altValid;
  uint8_t satsUsed, satsView;
  float hdop;
  float speedKmh, courseDeg;
  bool spoof;
  uint8_t spoofMask;
  uint8_t src;          // 0 нет, 1 GPS, 2 PDR
  float trend3h;        // гПа за 3 ч, NAN — мало данных
  uint32_t sunsetUtc;   // 0 — не известно
};

extern Persist P;
extern Settings S;
extern Snapshot N;

// Самопроверка (ТЗ, раздел 13): заполнить проверки для INFO и экрана
uint8_t buildChecks(proto::Check* out);
