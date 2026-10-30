// vabuGEOde — прошивка устройства.
// Логика: docs/gadget-logic.md. Протокол: docs/protocol.md. ТЗ: CLAUDE.md.
//
// Цикл: кнопки → акселерометр (шаги) → GPS → раз в секунду: датчики, состояние питания,
// запись, тревоги, маячок, живой сеанс → команды телефона → синхронизация → сон, если можно.

#include <Arduino.h>
#include <Preferences.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>
#include <math.h>

#include "app.h"
#include "clock.h"
#include "config.h"
#include "dlog.h"
#include "gps.h"
#include "gps_override.h"
#include "nmea.h"
#include "pins.h"
#include "proto.h"
#include "radio.h"
#include "sensors.h"
#include "spoof.h"
#include "storage.h"
#include "sun.h"
#include "ui.h"

Persist P;
Settings S;
Snapshot N;

namespace {

RTC_DATA_ATTR uint8_t g_rtcBlob[sizeof(Persist)];

Preferences g_prefs;
bool g_storageReady = false;
bool g_sdError = false;
GpsOverride g_consoleGps;
uint32_t g_bootMs = 0;
uint32_t g_motionCheckUntil = 0;
uint32_t g_awakeUntil = 0;       // не спать до (окно после включения и т. п.)
uint32_t g_lastAccelMs = 0, g_lastSecondMs = 0;
uint32_t g_lastEnvMs = 0, g_lastSelftestMs = 0, g_lastResyncMs = 0;
uint32_t g_tick = 0;
uint32_t g_powerOnUtc = 0;
// Записи с этих номеров сделаны по непроверенным часам (с включения) — сдвинуть, когда придёт время
constexpr uint32_t NO_SHIFT = 0xFFFFFFFF;
RTC_DATA_ATTR uint32_t g_shiftFromPoint = NO_SHIFT, g_shiftFromMark = NO_SHIFT;  // переживают сон
RTC_DATA_ATTR bool g_hikeUntrusted = false;  // поход начат по непроверенным часам
bool g_fixAnnounced = false;

// Синхронизация: идёт порциями в цикле, чтобы не блокировать остальное
struct SyncJob {
  bool active = false;
  uint8_t what = 0;
  uint32_t idx = 0, end = 0, sent = 0;
};
SyncJob g_sync;
// Ожидающие — по одной на вид (точки, метки, журнал): повторный запрос того же вида заменяет старый,
// другие виды не вытесняет (раньше запрос журнала мог вытеснить метки, пока шёл длинный трек)
SyncJob g_syncWait[3];
int syncSlot(uint8_t what) { return what == proto::CMD_SYNC_TRACK ? 0 : (what == proto::CMD_SYNC_MARKS ? 1 : 2); }

// ---------------------------------------------------------------------------
// Настройки и состояние
// ---------------------------------------------------------------------------

void loadSettings() {
  g_prefs.begin("vabu", false);
  S.gpsMode = g_prefs.getUChar("gm", logic::GPS_ALWAYS);
  S.gpsPulseS = g_prefs.getUShort("gp", DEF_GPS_PULSE_S);
  S.beaconS = g_prefs.getUShort("bs", DEF_BEACON_S);
  S.logMovingS = g_prefs.getUShort("lm", DEF_LOG_MOVING_S);
  S.logCampS = g_prefs.getUShort("lc", DEF_LOG_CAMP_S);
  S.tzMin = g_prefs.getShort("tz", 180);
  S.seaLevelHpa = g_prefs.getFloat("sl", DEF_SEA_LEVEL_HPA);
  S.baroCalibrated = g_prefs.getBool("bc", false);
  S.debug = g_prefs.getBool("dbg", true);
  sensors::setSeaLevel(S.seaLevelHpa);
  dlog::setDebug(S.debug);
}

void saveSettings() {
  g_prefs.putUChar("gm", S.gpsMode);
  g_prefs.putUShort("gp", S.gpsPulseS);
  g_prefs.putUShort("bs", S.beaconS);
  g_prefs.putUShort("lm", S.logMovingS);
  g_prefs.putUShort("lc", S.logCampS);
  g_prefs.putShort("tz", S.tzMin);
  g_prefs.putFloat("sl", S.seaLevelHpa);
  g_prefs.putBool("bc", S.baroCalibrated);
  g_prefs.putBool("dbg", S.debug);
}

void applySettings() {
  logic::PowerConfig& c = P.power.config();
  c.logMovingS = S.logMovingS;
  c.logCampS = S.logCampS;
  P.pulse.configure(S.gpsPulseS);
}

void initPersist() {
  memset(g_rtcBlob, 0, sizeof(g_rtcBlob));
  P = Persist();
  P.magic = PERSIST_MAGIC;
  P.recording = true;
  P.avgMa = 30;
}

void ensureStorage() {
  if (g_storageReady) return;
  storage::begin();
  storage::openHike(P.hikeStart);
  g_storageReady = true;
  dlog::attachStorage();
}

// Журнал на SD. SD будим только когда есть что писать
void flushLog() {
  if (!dlog::pending()) return;
  ensureStorage();
  dlog::flush();
}

// Часы переставили на d секунд (телефон или GPS). Все «когда в следующий раз» и «когда было» сдвигаем
// вместе с ними: иначе после перевода назад запись и маячок замолкают на эти d секунд, а пауза начинает
// новый поход. Номер похода (hikeStart) не трогаем — по нему поход узнают SD и телефон.
void onClockJump(int32_t d) {
  using logic::shiftTime;
  shiftTime(P.nextLogAt, d);
  shiftTime(P.nextBeaconAt, d);
  shiftTime(P.lastFlushAt, d);
  shiftTime(P.lastPointTime, d);
  shiftTime(P.goodTime, d);
  shiftTime(P.spoofAlertUntil, d);
  shiftTime(P.pressHistAt, d);
  shiftTime(g_powerOnUtc, d);
  P.power.shiftTime(d);
  P.pulse.shiftTime(d);
  P.alerts.shiftTime(d);
  P.spoof.shiftTime(d);
  // Записанное с включения по неверным часам — туда же (иначе в приложении данные «из будущего»,
  // а докачка «с последней точки» пропускает новые). Телефон шлёт время первой командой, до докачки
  if ((d > 60 || d < -60) && g_shiftFromPoint != NO_SHIFT) {
    ensureStorage();  // после сна SD ещё может быть не открыта: иначе старые точки не сдвинутся
    storage::shiftTimes(g_shiftFromPoint, g_shiftFromMark, d);
    proto::Point p;
    for (uint8_t i = 0; i < P.campN; i++) {
      if (proto::decodePoint(P.campBuf[i], proto::POINT_SIZE, p)) {
        p.time += (uint32_t)d;
        proto::encodePoint(p, P.campBuf[i], proto::POINT_SIZE);
      }
    }
    if (g_hikeUntrusted) {
      P.hikeStart += (uint32_t)d;
      storage::renameHike(P.hikeStart);
      g_prefs.putULong("hike", P.hikeStart);
    }
    LOGI(M_SYS, "время записей с включения сдвинуто на %+ld с", (long)d);
  }
  g_shiftFromPoint = g_shiftFromMark = NO_SHIFT;
  g_hikeUntrusted = false;
  if (P.lastPointTime) g_prefs.putULong("last", P.lastPointTime);
  N.now = clockx::now();
}

void newHike(uint32_t start) {
  // Без проверенных часов каждое включение стартует с одного времени (сборки) — номер повторил бы
  // прошлый поход, и на SD точки дописались бы в его файлы. Номер нового всегда больше прошлого
  uint32_t prev = g_prefs.getULong("hike", 0);
  if (!clockx::trusted() && start <= prev) start = prev + 1;
  P.hikeStart = start;
  g_hikeUntrusted = !clockx::trusted();
  P.distM = 0;
  P.stepDet.setCount(0);
  P.campN = 0;
  P.lastPointTime = 0;
  g_prefs.putULong("hike", start);
  g_prefs.putULong("last", 0);
  if (g_storageReady) storage::openHike(start);
  LOGI(M_SYS, "новый поход %lu", (unsigned long)start);
}

void loadBlacklist() {
  proto::BlEntry bl[proto::MAX_BLACKLIST];
  uint8_t n = 0;
  if (!storage::loadBlacklist(bl, n) || n == 0) {
    // Известные ложные точки (ТЗ, раздел 7)
    bl[0] = {600346000, 305693000, 1000};
    bl[1] = {601161000, 313864000, 1000};
    n = 2;
  }
  double la[proto::MAX_BLACKLIST], lo[proto::MAX_BLACKLIST];
  uint16_t r[proto::MAX_BLACKLIST];
  for (uint8_t i = 0; i < n; i++) {
    la[i] = bl[i].lat7 / 1e7;
    lo[i] = bl[i].lon7 / 1e7;
    r[i] = bl[i].radiusM;
  }
  P.spoof.setBlacklist(la, lo, r, n);
}

// ---------------------------------------------------------------------------
// Снимок и пакеты
// ---------------------------------------------------------------------------

int16_t s16(float v, float k) {
  if (isnan(v)) return proto::NA16;
  float x = roundf(v * k);
  if (x > 32767) x = 32767;
  if (x < -32767) x = -32767;
  return (int16_t)x;
}

uint8_t hdop10(float h) { return (h >= 25.4f || isnan(h)) ? proto::HDOP_NA : (uint8_t)lroundf(h * 10); }

int32_t c7(double v) { return (int32_t)llround(v * 1e7); }

void updateSun() {
  if (!N.haveLat) return;
  uint32_t t;
  N.sunsetUtc = sun::sunset(N.now, N.lat, N.lon, t) ? t : 0;
}

void updatePressureTrend() {
  if (!N.env.ok) return;
  // Раз в 15 мин кладём давление в историю (13 отсчётов = 3 ч)
  if (P.pressHistN == 0 || N.now - P.pressHistAt >= 900) {
    if (P.pressHistN < 13) P.pressHistN++;
    for (int i = P.pressHistN - 1; i > 0; i--) P.pressHist[i] = P.pressHist[i - 1];
    P.pressHist[0] = N.env.pressHpa;
    P.pressHistAt = N.now;
  }
  N.trend3h = P.pressHistN >= 5 ? N.env.pressHpa - P.pressHist[P.pressHistN - 1] : NAN;
}

void readEnvAndPower() {
  N.env = sensors::readEnv();
  N.pwr = sensors::readPower();
  if (!N.env.ok && sensors::presence().bme) dlog::counters().envFail++;
  if (!N.pwr.ok && sensors::presence().ina) dlog::counters().powerFail++;
  updatePressureTrend();
}

proto::Status makeStatus() {
  proto::Status m{};
  m.time = N.now;
  m.mode = P.power.state();
  m.flags = (P.recording ? proto::F_RECORDING : 0) | (N.gpsOn ? proto::F_GPS_ON : 0) | (N.spoof ? proto::F_SPOOF : 0) |
            (clockx::trusted() ? proto::F_TIME_OK : 0) | (storage::sdOk() ? proto::F_SD_OK : 0) |
            (P.power.low() ? proto::F_LOW_BATT : 0) | (P.alerts.unacked() ? proto::F_ALERT : 0);
  m.fix = !N.gpsOn ? 0 : (!N.fix ? 1 : (N.fixType >= 3 ? 3 : 2));
  m.satsUsed = N.satsUsed;
  m.satsView = N.satsView;
  m.hdop10 = N.fix ? hdop10(N.hdop) : proto::HDOP_NA;
  m.lat7 = N.haveLat ? c7(N.lat) : 0;
  m.lon7 = N.haveLat ? c7(N.lon) : 0;
  m.src = N.src;
  m.spoofMask = N.spoofMask;
  m.altGps10 = N.altValid ? s16(N.altGps, 10) : proto::NA16;
  m.speed10 = (uint16_t)lroundf(N.speedKmh * 10);
  m.course10 = (uint16_t)lroundf(N.courseDeg * 10);
  m.tempRaw100 = s16(N.env.tempRaw, 100);
  m.tempCorr100 = s16(N.env.tempCorr, 100);
  m.hum100 = isnan(N.env.hum) ? 0 : (uint16_t)lroundf(N.env.hum * 100);
  m.pressPa = isnan(N.env.pressHpa) ? 0 : (uint32_t)lroundf(N.env.pressHpa * 100);
  m.altBaro10 = s16(N.env.altBaro, 10);
  m.trend100 = s16(N.trend3h, 100);
  m.battMv = isnan(N.pwr.voltage) ? 0 : (uint16_t)lroundf(N.pwr.voltage * 1000);
  m.battMa10 = s16(N.pwr.currentMa, 10);
  m.battPct = N.pwr.pct;
  m.usedMah = (uint16_t)P.usedMah;
  m.steps = P.stepDet.count();
  m.heading10 = (uint16_t)lroundf(N.courseDeg * 10);
  m.distM = (uint32_t)P.distM;
  float left = BATT_CAPACITY_MAH * N.pwr.pct / 100.0f / fmaxf(P.avgMa, 1);
  m.hoursLeft10 = (uint16_t)fminf(left * 10, 65535);
  m.seq = P.seq++;
  m.alerts = P.alerts.active();
  return m;
}

proto::Beacon makeBeacon() {
  proto::Beacon b{};
  b.mode = P.power.state();
  b.flags = (P.recording ? proto::B_RECORDING : 0) | (N.fix && !N.spoof ? proto::B_FIX : 0) |
            (N.spoof ? proto::B_SPOOF : 0) | (clockx::trusted() ? proto::B_TIME_OK : 0) |
            (storage::sdOk() ? proto::B_SD_OK : 0);
  b.alerts = P.alerts.unacked();
  b.tempCorr100 = s16(N.env.tempCorr, 100);
  b.battPct = N.pwr.batteryKnown() ? N.pwr.pct : 255;
  b.satsUsed = N.fix ? N.satsUsed : 0;
  b.press10 = isnan(N.env.pressHpa) ? 0 : (uint16_t)lroundf(N.env.pressHpa * 10);
  b.lastRecord = P.lastPointTime;
  b.hikeId = P.hikeStart & 0xFFFF;
  b.counter = P.beaconCounter++;
  return b;
}

void sendPacketEvent(const uint8_t* b, size_t n) {
  if (n) radio::sendEvent(b, n);
}

void sendInfo() {
  proto::Info info{};
  info.fwMajor = FW_MAJOR;
  info.fwMinor = FW_MINOR;
  info.fwPatch = FW_PATCH;
  String chip = ESP.getChipModel();
  info.chip = chip.indexOf("S3") >= 0 ? 1 : (chip.indexOf("C3") >= 0 ? 2 : 0);
  info.uptime = N.now - g_powerOnUtc;
  ensureStorage();
  info.sdTotalMb = storage::totalMb();
  info.sdFreeMb = storage::freeMb();
  info.gnssMask = gps::parser().systemsSeen();
  info.hikeStart = P.hikeStart;
  info.gpsMode = S.gpsMode;
  info.gpsPulseS = S.gpsPulseS;
  info.beaconS = S.beaconS;
  info.nChecks = buildChecks(info.checks);
  uint8_t b[128];
  sendPacketEvent(b, proto::encodeInfo(info, b, sizeof(b)));
}

void sendSats() {
  const nmea::Parser& p = gps::parser();
  proto::Sat sats[proto::MAX_SATS];
  size_t n = p.satCount() > proto::MAX_SATS ? proto::MAX_SATS : p.satCount();
  for (size_t i = 0; i < n; i++) {
    const nmea::SatInfo& s = p.sat(i);
    sats[i] = proto::Sat{s.system, s.prn, s.snr, (uint8_t)(s.used ? 1 : 0)};
  }
  uint8_t b[proto::MAX_PACKET];
  size_t len = proto::encodeSats(sats, n, b, sizeof(b));
  if (len) radio::sendStatus(b, len);
}

// ---------------------------------------------------------------------------
// GPS, подмена, запись
// ---------------------------------------------------------------------------

void onFix() {
  const nmea::Parser& p = gps::parser();
  const nmea::Fix& f = p.fix();

  // Вышли из здания: первые фиксы после потери сигнала неточные (отражения, мало спутников) —
  // их не проверяем на подмену и не пишем в трек и километраж, иначе ложный «скачок» на каждом выходе.
  // Подменный сигнал «хороший» (HDOP низкий, спутников много) — его эта отсрочка не прячет, только на 5 с
  static uint32_t lastFixMs = 0;
  static uint8_t settle = 0;
  uint32_t ms = millis();
  if (ms - lastFixMs > 30000) settle = 0;  // сигнала не было 30+ с
  lastFixMs = ms;
  bool rough = f.hdop > 5.0f || f.satsUsed < 4 || settle < 5;
  if (settle < 255) settle++;
  if (rough) {
    N.fix = true;
    N.fixType = f.fixType;
    N.satsUsed = f.satsUsed;
    N.hdop = f.hdop;
    N.spoof = false;
    N.spoofMask = 0;
    N.src = 0;  // позиция — последняя достоверная
    N.altValid = false;
    N.speedKmh = 0;
    return;
  }
  uint8_t usedSnr[nmea::MAX_SATS];
  size_t used = 0;
  for (size_t i = 0; i < p.satCount(); i++) {
    if (p.sat(i).used) usedSnr[used++] = p.sat(i).snr;
  }
  spoof::Input in{};
  in.now = N.now;
  in.timeTrusted = clockx::trusted();
  in.lat = f.lat;
  in.lon = f.lon;
  in.altValid = f.altValid;
  in.altGps = f.altM;
  in.baroValid = S.baroCalibrated && N.env.ok;
  in.altBaro = N.env.altBaro;
  in.gpsTimeValid = f.timeValid;
  in.gpsTime = f.utc;
  in.usedSnr = usedSnr;
  in.usedCount = used;
  in.steps = P.stepDet.count();
  spoof::Result r = P.spoof.check(in);

  // Время от GPS — только если часы не проверены и нет подозрения
#ifndef FAKE_SENSORS
  // В эмуляции время «GPS» берётся из самих часов — проверенным оно от этого не становится
  if (f.timeValid) {
    int32_t d = clockx::offerGps(f.utc, r.spoof);
    if (d) onClockJump(d);
  }
#endif

  N.fix = true;
  N.fixType = f.fixType;
  N.satsUsed = f.satsUsed;
  N.hdop = f.hdop;
  N.spoof = r.spoof;
  N.spoofMask = r.mask;
  if (r.spoof) {
    P.spoofMaskLast = r.mask;
    P.spoofAlertUntil = N.now + 60;  // тревога держится минуту после последнего подозрительного фикса
    // Позиция — последняя достоверная (счисление по шагам — следующий этап)
    N.src = 0;
    N.altValid = false;
    N.speedKmh = 0;
  } else {
    // Дрожание стоящего приёмника в путь не засчитываем: точку сдвигаем, только если ушли дальше погрешности
    bool moved = !P.haveGood;
    if (P.haveGood) {
      double d = spoof::distanceM(P.goodLat, P.goodLon, f.lat, f.lon);
      // GPS молчал дольше 5 мин (пауза записи, стоянка) и точка далеко — переехали (машина, электричка):
      // в километраж не идёт, отсчёт с новой точки
      int32_t gap = P.goodTime ? (int32_t)(N.now - P.goodTime) : 0;
      if (gap > 300 && d > 200) {
        LOGI(M_GPS, "переезд %.1f км за %ld мин — в километраж не идёт", d / 1000, (long)(gap / 60));
        moved = true;
      } else if (d > fmaxf(5.0f, f.hdop * 3.0f)) {
        P.distM += d;
        moved = true;
      }
    }
    if (moved) {
      P.haveGood = true;
      P.goodLat = f.lat;
      P.goodLon = f.lon;
    }
    P.goodAlt = f.altM;
    P.goodTime = N.now;
    N.src = 1;
    N.altValid = f.altValid;
    N.altGps = f.altM;
    N.speedKmh = f.speedKmh;
    N.courseDeg = f.courseDeg;
    N.lat = f.lat;
    N.lon = f.lon;
    N.haveLat = true;
    if (!g_fixAnnounced) {
      g_fixAnnounced = true;
      ui::play(ui::Sound::FIX);
      updateSun();
    }
  }
}

void flushCamp() {
  if (!P.campN) return;
  ensureStorage();
  proto::Point p;
  for (uint8_t i = 0; i < P.campN; i++) {
    if (proto::decodePoint(P.campBuf[i], proto::POINT_SIZE, p)) {
      if (!storage::appendPoint(p, storage::CsvExtra{0, NAN})) g_sdError = true;
    }
  }
  P.campN = 0;
  P.lastFlushAt = N.now;
  g_prefs.putULong("last", P.lastPointTime);
  dlog::flush();  // SD всё равно проснулась — заодно журнал
}

void logPoint() {
  proto::Point p{};
  p.time = N.now;
  bool paused = !P.recording;
  bool haveGps = !paused && N.fix && !N.spoof && N.src == 1;
  // На паузе (едем на машине и т. п.) — только датчики: без координат, в трек и километраж не идёт
  double lat = paused ? 0 : (haveGps ? N.lat : (P.haveGood ? P.goodLat : 0));
  double lon = paused ? 0 : (haveGps ? N.lon : (P.haveGood ? P.goodLon : 0));
  p.lat7 = (lat || lon) ? c7(lat) : 0;
  p.lon7 = (lat || lon) ? c7(lon) : 0;
  p.src = paused ? proto::SRC_PAUSE : (haveGps ? 1 : 0);
  p.spoof = !paused && N.spoof;
  p.spoofMask = p.spoof ? N.spoofMask : 0;
  if (p.spoof) {
    const nmea::Fix& f = gps::parser().fix();
    p.spoofLat7 = c7(f.lat);
    p.spoofLon7 = c7(f.lon);
  }
  p.tempRaw100 = s16(N.env.tempRaw, 100);
  p.tempCorr100 = s16(N.env.tempCorr, 100);
  p.hum100 = isnan(N.env.hum) ? 0 : (uint16_t)lroundf(N.env.hum * 100);
  p.pressPa = isnan(N.env.pressHpa) ? 0 : (uint32_t)lroundf(N.env.pressHpa * 100);
  p.altBaro10 = s16(N.env.altBaro, 10);
  p.altGps10 = haveGps && N.altValid ? s16(N.altGps, 10) : proto::NA16;
  p.battMv = isnan(N.pwr.voltage) ? 0 : (uint16_t)lroundf(N.pwr.voltage * 1000);
  p.battPct = N.pwr.pct;
  p.sats = N.fix ? N.satsUsed : 0;
  p.hdop10 = N.fix ? hdop10(N.hdop) : proto::HDOP_NA;
  p.steps = P.stepDet.count();
  p.heading10 = (uint16_t)lroundf(N.courseDeg * 10);
  p.speed10 = haveGps ? (uint16_t)lroundf(N.speedKmh * 10) : 0;
  P.lastPointTime = p.time;

  bool camp = paused || P.power.state() == logic::CAMP || P.power.state() == logic::CRITICAL ||
              (P.power.state() == logic::ECONOMY && !P.power.moving());
  if (camp) {
    // На стоянке копим в RTC-памяти — SD не трогаем на каждой записи
    proto::encodePoint(p, P.campBuf[P.campN++], proto::POINT_SIZE);
    if (P.campN >= CAMP_BUF || N.now - P.lastFlushAt >= SD_FLUSH_S) flushCamp();
  } else {
    flushCamp();
    ensureStorage();
    if (!storage::appendPoint(p, storage::CsvExtra{N.courseDeg, N.pwr.currentMa})) {
      if (!g_sdError) LOGE(M_SD, "запись точки на SD не удалась — пишу только в память");
      g_sdError = true;
      dlog::counters().sdFail++;
    }
    // Время последней записи в NVS — раз в 30 записей (~5 мин): бережём флеш
    static uint8_t sinceSave = 0;
    if (++sinceSave >= 30) {
      sinceSave = 0;
      g_prefs.putULong("last", P.lastPointTime);
    }
  }
  if (radio::live()) {
    uint8_t b[proto::POINT_SIZE];
    sendPacketEvent(b, proto::encodePoint(p, b, sizeof(b)));
  }
}

void addMark(uint8_t type, const char* text, uint8_t textLen) {
  proto::Mark m{};
  ensureStorage();
  m.id = storage::nextMarkId();
  m.time = N.now;
  m.type = type;
  if (P.haveGood) {
    m.lat7 = c7(P.goodLat);
    m.lon7 = c7(P.goodLon);
  }
  m.textLen = (uint8_t)proto::utf8Limit(text, textLen, proto::MAX_TEXT);
  memcpy(m.text, text, m.textLen);
  m.text[m.textLen] = 0;
  if (!storage::appendMark(m)) g_sdError = true;
  LOGI(M_UI, "метка #%u тип %u%s%s", m.id, type, m.textLen ? ": " : "", m.text);
  // Метки — и в живом, и в тихом сеансе
  uint8_t b[96];
  sendPacketEvent(b, proto::encodeMark(m, b, sizeof(b)));
}

// ---------------------------------------------------------------------------
// Синхронизация
// ---------------------------------------------------------------------------

void startSync(uint8_t what, uint32_t since) {
  flushCamp();
  ensureStorage();
  SyncJob j;
  j.active = true;
  j.what = what;
  if (what == proto::CMD_SYNC_TRACK) {
    j.idx = storage::firstPointSince(since);
    j.end = storage::pointCount();
  } else if (what == proto::CMD_SYNC_LOG) {
    dlog::flush();  // свежие строки — сначала на SD, выгрузка идёт оттуда
    j.idx = dlog::syncStart(since);
    j.end = dlog::syncEnd();
  } else {
    j.idx = storage::firstMarkSince(since);
    j.end = storage::markCount();
  }
  if (g_sync.active) g_syncWait[syncSlot(what)] = j;
  else g_sync = j;
}

void pumpSync() {
  if (!g_sync.active) return;
  if (!radio::secured()) {
    g_sync.active = false;
    for (SyncJob& w : g_syncWait) w.active = false;
    return;
  }
  uint8_t b[96];
  // Порция за итерацию: темп ограничен буферами BLE — подобрать на железе
  for (int i = 0; i < 4 && g_sync.idx < g_sync.end; i++) {
    size_t n = 0;
    if (g_sync.what == proto::CMD_SYNC_TRACK) {
      proto::Point p;
      if (storage::readPoint(g_sync.idx, p)) n = proto::encodePoint(p, b, sizeof(b));
    } else if (g_sync.what == proto::CMD_SYNC_LOG) {
      n = dlog::syncRead(g_sync.idx, b, sizeof(b));
    } else {
      proto::Mark m;
      if (storage::readMark(g_sync.idx, m)) n = proto::encodeMark(m, b, sizeof(b));
    }
    if (n) {
      if (!radio::sendEvent(b, n)) break;  // буферы BLE заняты — эту же запись в следующий раз
      g_sync.sent++;
    }
    g_sync.idx++;
  }
  if (g_sync.idx >= g_sync.end) {
    // SYNC_DONE тоже не терять: без него приложение считает, что выгрузка ещё идёт
    if (!radio::sendEvent(b, proto::encodeSyncDone(g_sync.what, g_sync.sent, b, sizeof(b)))) return;
    if (g_sync.what != proto::CMD_SYNC_LOG || g_sync.sent) LOGD(M_BLE, "выгрузка 0x%02X: %lu записей", g_sync.what, (unsigned long)g_sync.sent);
    g_sync.active = false;
    for (SyncJob& w : g_syncWait) {
      if (w.active) {
        g_sync = w;
        w.active = false;
        break;
      }
    }
  } else {
    delay(4);
  }
}

// ---------------------------------------------------------------------------
// Команды телефона
// ---------------------------------------------------------------------------

void handleCommand(const uint8_t* data, size_t len) {
  radio::touch();
  proto::Command c;
  uint8_t res = proto::decodeCommand(data, len, c);
  uint8_t code = proto::commandCode(data, len);
  uint8_t b[64];
  if (res != proto::ACK_OK) {
    LOGW(M_CMD, "команда 0x%02X отклонена: код %u, %u байт", code, res, (unsigned)len);
    sendPacketEvent(b, proto::encodeAck(code, res, b, sizeof(b)));
    return;
  }
  // SESSION приходит каждые 20 с — в журнал не пишем, иначе он им забьётся
  if (c.code != proto::CMD_SESSION && c.code != proto::CMD_SYNC_LOG && c.code != proto::CMD_GET_INFO) {
    LOGD(M_CMD, "команда 0x%02X", c.code);
  }
  switch (c.code) {
    case proto::CMD_SET_TIME: {
      long delta = (long)c.time - (long)clockx::now();
      bool wasTrusted = clockx::trusted();
      if (!wasTrusted) LOGI(M_RTC, "время с телефона: часы не были проверены, сдвиг %+ld с", delta);
      else if (delta > 300 || delta < -300) LOGW(M_RTC, "время с телефона: часы ушли на %+ld с", delta);
      else LOGD(M_RTC, "время с телефона: сдвиг %+ld с", delta);
      clockx::setFromPhone(c.time);
      if (delta) onClockJump((int32_t)delta);
      if (c.tzMin != S.tzMin) {
        S.tzMin = c.tzMin;
        saveSettings();
      }
      break;
    }
    case proto::CMD_PHONE_POS:
      P.spoof.setPhone(c.lat7 / 1e7, c.lon7 / 1e7, c.accuracyM, N.now);
#ifdef FAKE_SENSORS
      if (gps::fakeAnchor(c.lat7 / 1e7, c.lon7 / 1e7)) {
        P.spoof.forgetLastGood();
        P.haveGood = false;
        LOGI(M_GPS, "эмуляция: трек идёт от позиции телефона, подмена — каждые 10 мин на 2 мин");
      }
#endif
      break;
    case proto::CMD_RECORD:
      if (c.flag && !P.recording && (P.hikeStart == 0 || (int32_t)(N.now - P.lastPointTime) > 6 * 3600)) newHike(N.now);
      P.recording = c.flag;
      break;
    case proto::CMD_MARK:
      addMark(c.markType, c.text, c.textLen);
      ui::play(ui::Sound::MARK);
      break;
    case proto::CMD_CLAP:
      ui::play(ui::Sound::CLAP);
      addMark(5, "", 0);
      break;
    case proto::CMD_FIND:
      ui::play(ui::Sound::FIND);
      break;
    case proto::CMD_SET_INTERVALS:
      S.logMovingS = c.a;
      S.logCampS = c.b;
      saveSettings();
      applySettings();
      break;
    case proto::CMD_CALIB_ALT:
      sensors::calibrateAltitude(c.altDm / 10.0f);
      S.seaLevelHpa = sensors::seaLevel();
      S.baroCalibrated = true;
      saveSettings();
      break;
    case proto::CMD_CALIB_COMPASS:
      res = proto::ACK_UNSUPPORTED;  // компас и счисление по шагам — следующий этап
      break;
    case proto::CMD_BLACKLIST: {
      double la[proto::MAX_BLACKLIST], lo[proto::MAX_BLACKLIST];
      uint16_t r[proto::MAX_BLACKLIST];
      for (uint8_t i = 0; i < c.blCount; i++) {
        la[i] = c.bl[i].lat7 / 1e7;
        lo[i] = c.bl[i].lon7 / 1e7;
        r[i] = c.bl[i].radiusM;
      }
      P.spoof.setBlacklist(la, lo, r, c.blCount);
      ensureStorage();
      storage::saveBlacklist(c.bl, c.blCount);
      break;
    }
    case proto::CMD_GET_INFO:
      break;  // ответ после ACK
    case proto::CMD_SESSION:
      radio::setLive(c.flag);
      break;
    case proto::CMD_BYE:
      break;  // рвём после ACK
    case proto::CMD_SET_GPS_MODE:
      S.gpsMode = c.flag;
      S.gpsPulseS = c.a;
      saveSettings();
      applySettings();
      break;
    case proto::CMD_SET_RADIO:
      S.beaconS = c.a;
      saveSettings();
      break;
    case proto::CMD_SYNC_TRACK:
    case proto::CMD_SYNC_MARKS:
    case proto::CMD_SYNC_LOG:
      startSync(c.code, c.time);
      break;
    case proto::CMD_SET_DEBUG:
      if (S.debug != (bool)c.flag) {
        S.debug = c.flag;
        dlog::setDebug(S.debug);
        saveSettings();
        LOGI(M_SYS, "подробный журнал: %s", S.debug ? "вкл" : "выкл");
      }
      break;
    case proto::CMD_ACK_ALERT:
      P.alerts.ack(c.flag);
      break;
  }
  sendPacketEvent(b, proto::encodeAck(code, res, b, sizeof(b)));
  if (c.code == proto::CMD_GET_INFO) sendInfo();
  if (c.code == proto::CMD_BYE) radio::disconnect();
}

// ---------------------------------------------------------------------------
// Кнопки
// ---------------------------------------------------------------------------

void handleButton(ui::Button e) {
  if (e != ui::Button::NONE) LOGD(M_UI, "кнопка %u", (unsigned)e);
  switch (e) {
    case ui::Button::A_SHORT:  // метка
      addMark(0, "", 0);
      ui::play(ui::Sound::MARK);
      break;
    case ui::Button::A_LONG:  // хлопушка
      ui::play(ui::Sound::CLAP);
      addMark(5, "", 0);
      break;
    case ui::Button::B_SHORT:  // экран / листать
      ui::nextPage();
      break;
    case ui::Button::B_LONG:  // окно подключения + сопряжение нового телефона
      radio::openWindow(makeBeacon(), true);
      ui::play(ui::Sound::WINDOW);
      break;
    default:
      break;
  }
}

// ---------------------------------------------------------------------------
// Наблюдение для журнала: переходы состояний и снимок раз в минуту (отладка) или 10 мин
// ---------------------------------------------------------------------------

// Прошлое состояние переживает сон — после пробуждения не пишем одно и то же заново
struct Watch {
  bool init;
  uint8_t radio;
  bool secured, gpsOn, fix, nmeaDead, spoof, envBad, pwrBad, sdBad, trusted, low, crit;
  bool noBme, noIna, noMpu, noOled, noCompass;
  uint32_t gpsOnUp, fixUp, spoofUp, lastBeatUp;
};
RTC_DATA_ATTR Watch g_w;

const char* spoofText(uint8_t m, char* out, size_t cap) {
  static const char* names[] = {"чёрный список", "телефон", "скачок", "SNR", "барометр", "шаги", "время"};
  size_t n = 0;
  out[0] = 0;
  for (int i = 0; i < 7; i++) {
    if (m & (1 << i)) n += snprintf(out + n, n < cap ? cap - n : 0, "%s%s", n ? ", " : "", names[i]);
  }
  return out;
}

void edge(bool now, bool& was, uint8_t level, uint8_t module, const char* on, const char* off) {
  if (now == was) return;
  was = now;
  dlog::log(now ? level : logbuf::INFO, module, "%s", now ? on : off);
}

void heartbeat() {
  const dlog::Counters& c = dlog::counters();
  const nmea::Parser& g = gps::parser();
  LOGI(M_SYS, "ST mode=%u rec=%d gps=%d/%d sats=%u/%u hdop=%.1f bt=%u spoof=%02X steps=%lu dist=%lum", P.power.state(),
       P.recording, N.gpsOn, N.fix, N.satsUsed, N.satsView, N.fix ? N.hdop : 0.0f, (unsigned)radio::state(),
       N.spoofMask, (unsigned long)P.stepDet.count(), (unsigned long)P.distM);
  LOGI(M_SENS, "ENV T=%.2f/%.2f P=%.1f H=%.0f U=%.3f I=%.1f %u%% mAh=%.0f avg=%.1f", N.env.tempRaw, N.env.tempCorr,
       N.env.pressHpa, N.env.hum, N.pwr.voltage, N.pwr.currentMa, N.pwr.pct, P.usedMah, P.avgMa);
  LOGI(M_SYS, "SYS heap=%luK min=%luK psram=%luK sd=%luM up=%lus boots=%lu", (unsigned long)(ESP.getFreeHeap() / 1024),
       (unsigned long)(ESP.getMinFreeHeap() / 1024), (unsigned long)(ESP.getFreePsram() / 1024),
       (unsigned long)storage::freeMb(), (unsigned long)dlog::uptimeS(), (unsigned long)P.bootCount);
  LOGI(M_SYS, "ERR env=%lu pwr=%lu acc=%lu sd=%lu nmea=%lu/%lu drop=%lu loop=%lums", (unsigned long)c.envFail,
       (unsigned long)c.powerFail, (unsigned long)c.accelFail, (unsigned long)c.sdFail,
       (unsigned long)g.checksumErrors(), (unsigned long)g.sentences(), (unsigned long)c.dropped,
       (unsigned long)c.maxLoopMs);
  LOGI(M_SYS, "WAKE timer=%lu btn=%lu motion=%lu ble=%lu reject=%lu", (unsigned long)c.wakeTimer,
       (unsigned long)c.wakeButton, (unsigned long)c.wakeMotion, (unsigned long)c.bleConnects,
       (unsigned long)c.bleRejects);
}

void watchState() {
  const sensors::Presence& pr = sensors::presence();
  uint32_t up = dlog::uptimeS();
  bool nmeaDead = N.gpsOn && gps::onSeconds() > 5 && !gps::alive();
  if (!g_w.init) {
    // Первое наблюдение после включения: запомнить, не сравнивая
    // Поля датчиков и SD хранят «плохо» (true — не работает), как и сравниваются ниже
    g_w = Watch{true, (uint8_t)radio::state(), radio::secured(), N.gpsOn, N.fix, nmeaDead, N.spoof, !N.env.ok, !N.pwr.ok,
                !storage::sdOk(), clockx::trusted(), P.power.low(), P.power.critical(), !pr.bme, !pr.ina, !pr.mpu,
                !pr.oled, !pr.compass, up, 0, 0, up};
    return;
  }
  // Bluetooth
  uint8_t rs = (uint8_t)radio::state();
  if (rs != g_w.radio) {
    static const char* names[] = {"выкл", "маячок", "окно", "подключён"};
    if (rs >= (uint8_t)radio::State::WINDOW || g_w.radio >= (uint8_t)radio::State::WINDOW) {
      LOGI(M_BLE, "радио: %s -> %s", names[g_w.radio & 3], names[rs & 3]);
    }
    g_w.radio = rs;
  }
  edge(radio::secured(), g_w.secured, logbuf::INFO, logbuf::M_BLE, "связь зашифрована (сопряжённый телефон)",
       "шифрованная связь закончилась");
  // GPS
  if (N.gpsOn != g_w.gpsOn) {
    g_w.gpsOn = N.gpsOn;
    if (N.gpsOn) {
      g_w.gpsOnUp = up;
      LOGI(M_GPS, "GPS вкл");
    } else {
      LOGI(M_GPS, "GPS выкл, работал %lu с", (unsigned long)(up - g_w.gpsOnUp));
    }
  }
  if (N.fix != g_w.fix) {
    g_w.fix = N.fix;
    if (N.fix) {
      g_w.fixUp = up;
      uint8_t sys = gps::parser().systemsSeen();
      LOGI(M_GPS, "фикс за %lu с: %uD, %u из %u спутн, HDOP %.1f, системы%s%s%s", (unsigned long)(up - g_w.gpsOnUp),
           N.fixType, N.satsUsed, N.satsView, N.hdop, (sys & 1) ? " GPS" : "", (sys & 2) ? " BDS" : "",
           (sys & 4) ? " ГЛОНАСС" : "");
    } else if (N.gpsOn) {
      LOGW(M_GPS, "фикс потерян через %lu с, видно спутн %u", (unsigned long)(up - g_w.fixUp), N.satsView);
    }
  }
  edge(nmeaDead, g_w.nmeaDead, logbuf::ERR, logbuf::M_GPS, "нет NMEA от GPS: проверить TX/RX, AO3401, питание",
       "NMEA от GPS снова идёт");
  // Подмена
  if (N.spoof != g_w.spoof) {
    g_w.spoof = N.spoof;
    if (N.spoof) {
      char why[80];
      const nmea::Fix& f = gps::parser().fix();
      g_w.spoofUp = up;
      LOGW(M_SPOOF, "подмена 0x%02X: %s; GPS %.5f,%.5f", N.spoofMask, spoofText(N.spoofMask, why, sizeof(why)), f.lat,
           f.lon);
    } else {
      LOGI(M_SPOOF, "подмена кончилась через %lu с", (unsigned long)(up - g_w.spoofUp));
    }
  }
  // Датчики и SD
  edge(!N.env.ok, g_w.envBad, logbuf::ERR, logbuf::M_SENS, "BME280 не читается", "BME280 снова читается");
  edge(!N.pwr.ok, g_w.pwrBad, logbuf::ERR, logbuf::M_SENS, "INA219 не читается", "INA219 снова читается");
  edge(!pr.bme, g_w.noBme, logbuf::WARN, logbuf::M_SENS, "BME280 пропал с шины I2C", "BME280 нашёлся на шине");
  edge(!pr.ina, g_w.noIna, logbuf::WARN, logbuf::M_SENS, "INA219 пропал с шины I2C", "INA219 нашёлся на шине");
  edge(!pr.mpu, g_w.noMpu, logbuf::WARN, logbuf::M_SENS, "MPU6050 пропал с шины I2C", "MPU6050 нашёлся на шине");
  edge(!pr.oled, g_w.noOled, logbuf::WARN, logbuf::M_SENS, "экран пропал с шины I2C", "экран нашёлся на шине");
  edge(!pr.compass, g_w.noCompass, logbuf::WARN, logbuf::M_SENS, "компас пропал с шины I2C", "компас нашёлся на шине");
  if (g_storageReady) edge(!storage::sdOk(), g_w.sdBad, logbuf::ERR, logbuf::M_SD, "SD недоступна", "SD снова доступна");
  // Время и батарея
  edge(clockx::trusted(), g_w.trusted, logbuf::INFO, logbuf::M_RTC, "время проверено", "время больше не проверено");
  if (P.power.low() != g_w.low) {
    g_w.low = P.power.low();
    if (g_w.low) LOGW(M_PWR, "низкий заряд: %u%%, %.2f В", N.pwr.pct, N.pwr.voltage);
    else LOGI(M_PWR, "заряд в норме: %u%%", N.pwr.pct);
  }
  if (P.power.critical() != g_w.crit) {
    g_w.crit = P.power.critical();
    if (g_w.crit) LOGE(M_PWR, "критический заряд: %u%%, %.2f В", N.pwr.pct, N.pwr.voltage);
  }
  // Снимок: в отладке раз в минуту (на стоянке — раз в 5 мин, чтобы не будить SD), иначе раз в 10 мин
  bool camp = P.power.state() != logic::MOVING;
  uint32_t every = dlog::debug() ? (camp ? 300 : 60) : 600;
  if (up - g_w.lastBeatUp >= every) {
    g_w.lastBeatUp = up;
    heartbeat();
  }
}

// ---------------------------------------------------------------------------
// Раз в секунду
// ---------------------------------------------------------------------------

void secondTick() {
  g_tick++;
  uint32_t ms = millis();
  N.now = clockx::now();

  // Датчики: в пути раз в 5 с, иначе каждое пробуждение
  bool moving = P.power.state() == logic::MOVING;
  if (!moving || ms - g_lastEnvMs >= 5000 || g_lastEnvMs == 0) {
    g_lastEnvMs = ms;
    readEnvAndPower();
    if (N.pwr.ok && !isnan(N.pwr.currentMa)) {
      P.avgMa += 0.01f * (N.pwr.currentMa - P.avgMa);
    }
  }
  if (N.pwr.ok && !isnan(N.pwr.currentMa)) P.usedMah += N.pwr.currentMa / 3600.0f;

  // Состояние питания и GPS
  logic::Power before = P.power.state();
  P.power.update(N.now, N.pwr.controlPercent(), P.stepDet.count());
  if (before != P.power.state()) {
    static const char* names[] = {"стоянка", "в пути", "экономия", "критично"};
    LOGI(M_PWR, "режим %s -> %s (%u%%, %.2f В)", names[before & 3], names[P.power.state() & 3], N.pwr.pct, N.pwr.voltage);
    if (before == logic::CAMP) flushCamp();
  }
  bool newFixThisSecond = gps::takeNewFix();
  if (newFixThisSecond) onFix();
  bool wantGps = P.recording && (P.power.gpsContinuous((logic::GpsMode)S.gpsMode) ||
                                 (P.power.gpsPulsed((logic::GpsMode)S.gpsMode) &&
                                  P.pulse.update(N.now, N.fix, newFixThisSecond)));
  wantGps = g_consoleGps.wanted(wantGps, P.power.critical(), ms);
  if (!wantGps) P.pulse.stop();
  gps::power(wantGps);
  N.gpsOn = gps::isOn();
  if (!N.gpsOn || !gps::parser().fix().valid) {
    N.fix = false;
    N.spoof = false;  // тревога сама держится ещё минуту (spoofAlertUntil)
    N.spoofMask = 0;
  }
  N.satsView = gps::parser().satCount();
  if (!N.fix) {
    N.satsUsed = 0;
    N.src = 0;
  }
  if (!N.haveLat && P.haveGood) {
    N.lat = P.goodLat;
    N.lon = P.goodLon;
    N.haveLat = true;
    updateSun();
  }
  if (g_tick % 600 == 0) updateSun();

  // Запись
  // На паузе GPS выключен, но датчики пишутся дальше — раз в минуту (как на стоянке), без координат
  if (P.hikeStart && (int32_t)(N.now - P.nextLogAt) >= 0) {
    logPoint();
    uint16_t every = P.power.logIntervalS();
    if (!P.recording && every < S.logCampS) every = S.logCampS;
    P.nextLogAt = N.now + every;
  }

  // Тревоги
  uint8_t active = 0;
  // Подмена — не тревога: сделать с ней ничего нельзя, а писк и окно BLE на каждый эпизод только
  // отвлекают и тратят батарею. Она видна в статусе, на карте и в журнале

  if (P.power.low()) active |= proto::AL_LOW_BATT;
  if (P.power.critical()) active |= proto::AL_CRIT_BATT;
  if (g_sdError && storage::sdOk() == false) active |= proto::AL_SD_ERROR;
  logic::Alerts::Decision d = P.alerts.update(N.now, active);
  if (d.openWindow) {
    LOGI(M_SYS, "тревога 0x%02X, новые 0x%02X — открываю окно", P.alerts.active(), d.fresh);
    if (!radio::connected()) radio::openWindow(makeBeacon(), false);
    uint8_t snd = d.fresh ? d.fresh : P.alerts.unacked();
    if (snd & proto::AL_SPOOF) ui::play(ui::Sound::ALERT_SPOOF);
    else if (snd & (proto::AL_LOW_BATT | proto::AL_CRIT_BATT)) ui::play(ui::Sound::ALERT_BATT);
    uint8_t b[16];
    sendPacketEvent(b, proto::encodeAlert(N.now, P.alerts.active(), d.fresh, N.pwr.pct, P.spoofMaskLast, b, sizeof(b)));
  }

  // Маячок по расписанию
  uint16_t beaconEvery = P.power.beaconIntervalS(S.beaconS);
  if (beaconEvery && radio::state() == radio::State::OFF && (int32_t)(N.now - P.nextBeaconAt) >= 0) {
    radio::beacon(makeBeacon());
    P.nextBeaconAt = N.now + beaconEvery;
  } else if (radio::state() == radio::State::WINDOW && g_tick % 5 == 0) {
    radio::updateBeacon(makeBeacon());
  }

  // Живой сеанс
  if (radio::live()) {
    proto::Status st = makeStatus();
    uint8_t b[proto::STATUS_SIZE];
    radio::sendStatus(b, proto::encodeStatus(st, b, sizeof(b)));
    if (N.gpsOn && g_tick % 5 == 0) sendSats();
  }

  // Самопроверка раз в минуту, часы — раз в 10 мин
  if (ms - g_lastSelftestMs >= 60000) {
    g_lastSelftestMs = ms;
    sensors::rescan();
  }
  if (ms - g_lastResyncMs >= 600000) {
    g_lastResyncMs = ms;
    int32_t delta = clockx::resync();
    if (delta) onClockJump(delta);
  }

  watchState();
  // Журнал на SD: сразу при ошибке или полбуфера; в пути — ещё и раз в 30 с (SD и так работает)
  if (dlog::urgent() || (P.power.state() == logic::MOVING && g_tick % 30 == 0)) flushLog();

  ui::draw();
}

// ---------------------------------------------------------------------------
// Сон
// ---------------------------------------------------------------------------

bool canSleep(uint32_t ms) {
#ifdef FAKE_SENSORS
  (void)ms;
  return false;  // плата на USB, записи в PSRAM — не спим
#else
  bool resting = P.power.state() == logic::CAMP || P.power.state() == logic::CRITICAL ||
                 (P.power.state() == logic::ECONOMY && !P.power.moving());
  return resting && radio::state() == radio::State::OFF && !gps::isOn() && !ui::busy() && !ui::screenActive() &&
         !ui::anyHeld() && !g_sync.active && (int32_t)(ms - g_motionCheckUntil) >= 0 &&
         (int32_t)(ms - g_awakeUntil) >= 0;
#endif
}

// forceS > 0 — уснуть ровно на столько (консольная команда sleep, проверка сна на голой плате)
void goToSleep(int32_t forceS = 0) {
  N.now = clockx::now();
  if (P.campN && N.now - P.lastFlushAt >= SD_FLUSH_S) flushCamp();
  // Будим на ближайшее: запись (если идёт) или маячок (если не невидимка); иначе через час
  uint32_t wakeAt = N.now + 3600;
  if (P.hikeStart && (int32_t)(P.nextLogAt - wakeAt) < 0) wakeAt = P.nextLogAt;
  uint16_t beaconEvery = P.power.beaconIntervalS(S.beaconS);
  if (beaconEvery && (int32_t)(P.nextBeaconAt - wakeAt) < 0) wakeAt = P.nextBeaconAt;
  int32_t sleepS = (int32_t)(wakeAt - N.now);
  if (sleepS < 1) sleepS = 1;
  if (sleepS > 3600) sleepS = 3600;
  if (forceS > 0) sleepS = forceS;

  LOGD(M_PWR, "сон %ld с", (long)sleepS);
  // Буфер журнала почти полон — сбросить, иначе за следующие пробуждения начнёт терять строки
  if (dlog::pending() >= logbuf::RING_N * 3 / 4) flushLog();
  dlog::beforeSleep((uint32_t)sleepS);
  memcpy(g_rtcBlob, &P, sizeof(P));
  sensors::prepareSleep();
  gps::power(false);
  radio::shutdown();
  ui::sleep();
  Serial.flush();

  esp_sleep_enable_timer_wakeup((uint64_t)sleepS * 1000000ULL);
  // Кнопки (замыкают на GND) и прерывание движения MPU6050 (активный HIGH)
  rtc_gpio_pullup_en((gpio_num_t)PIN_BTN_A);
  rtc_gpio_pullup_en((gpio_num_t)PIN_BTN_B);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_BTN_A);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_BTN_B);
  esp_sleep_enable_ext1_wakeup((1ULL << PIN_BTN_A) | (1ULL << PIN_BTN_B), ESP_EXT1_WAKEUP_ANY_LOW);
#ifndef FAKE_SENSORS  // в эмуляции MPU нет, вывод INT висит в воздухе — будил бы сразу
  if (sensors::presence().mpu) esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_MPU_INT, 1);
#endif
  esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);  // держать подтяжки кнопок
  esp_deep_sleep_start();
}

// ---------------------------------------------------------------------------
// Консоль отладки (USB): help
// ---------------------------------------------------------------------------

void console() {
  static char line[96];
  static size_t n = 0;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch != '\n' && ch != '\r') {
      if (n < sizeof(line) - 1) line[n++] = ch;
      continue;
    }
    if (!n) continue;
    line[n] = 0;
    n = 0;
    // Кто-то сидит в мониторе порта — 10 мин не уходим в глубокий сон (иначе USB пропадёт)
    if ((int32_t)(millis() + 600000 - g_awakeUntil) > 0) g_awakeUntil = millis() + 600000;
    if (!strcmp(line, "help")) {
      Serial.println("st — состояние; i2c — сканер шины; gps on|off|auto|echo — GPS; pcas <тело> — команда модулю;"
                     " win — окно BLE; info — чип и SD; log — сбросить журнал на SD; debug on|off — подробный журнал");
      Serial.println("Проверки: sleep <с> — глубокий сон (USB пропадёт, монитор переоткрыть); jump <±с> — переставить"
                     " часы, как телефон; newhike — начать новый поход; accel — ускорения MPU6050 в g;"
                     " motion on|off — проверка детектора; motion — INT и регистры (сброс защёлки)");
    } else if (!strcmp(line, "log")) {
      uint32_t n = dlog::pending();
      flushLog();
      Serial.printf("журнал: сброшено %lu, осталось %lu, всего на SD %lu строк\n", (unsigned long)(n - dlog::pending()),
                    (unsigned long)dlog::pending(), (unsigned long)dlog::syncEnd());
    } else if (!strcmp(line, "debug on") || !strcmp(line, "debug off")) {
      S.debug = !strcmp(line, "debug on");
      dlog::setDebug(S.debug);
      saveSettings();
      Serial.printf("подробный журнал: %s\n", S.debug ? "вкл" : "выкл");
    } else if (!strcmp(line, "st")) {
      Serial.printf("t=%lu доверие=%d режим=%u запись=%d T=%.2f/%.2f P=%.1f H=%.0f U=%.3fВ I=%.1fмА %u%% шаги=%lu\n",
                    (unsigned long)N.now, clockx::trusted(), P.power.state(), P.recording, N.env.tempRaw,
                    N.env.tempCorr, N.env.pressHpa, N.env.hum, N.pwr.voltage, N.pwr.currentMa, N.pwr.pct,
                    (unsigned long)P.stepDet.count());
      Serial.printf("GPS вкл=%d фикс=%d %.6f,%.6f спутн=%u/%u HDOP=%.1f подмена=%d маска=0x%02X NMEA=%lu ошибок=%lu\n",
                    N.gpsOn, N.fix, N.lat, N.lon, N.satsUsed, N.satsView, N.hdop, N.spoof, N.spoofMask,
                    (unsigned long)gps::parser().sentences(), (unsigned long)gps::parser().checksumErrors());
    } else if (!strcmp(line, "motion on")) {
      rtc_gpio_deinit((gpio_num_t)PIN_MPU_INT);
      pinMode(PIN_MPU_INT, INPUT);
      Serial.println(sensors::armMotion(true) ? "Тест движения: порог 2 мг, 1 мс; наклони MPU и введи motion" : "MPU6050 недоступен");
    } else if (!strcmp(line, "motion off")) {
      sensors::stopMotion();
      Serial.println("Проверка движения выключена");
    } else if (!strcmp(line, "motion")) {
      int level = digitalRead(PIN_MPU_INT);  // до чтения INT_STATUS: оно сбросит защёлку
      sensors::MotionStatus m{};
      if (sensors::readMotion(m)) {
        Serial.printf("MPU INT GPIO%d=%d MOT=%u ENABLE=0x%02X CFG=0x%02X PWR=0x%02X STATUS=0x%02X\n",
                      PIN_MPU_INT, level, (m.status & 0x40) != 0, m.enabled, m.config, m.power, m.status);
        Serial.printf("MPU WHO_AM_I=0x%02X ACCEL_CFG=0x%02X MOT_THR=%u MOT_DUR=%u MOT_CTRL=0x%02X\n",
                      m.who, m.accel, m.threshold, m.duration, m.control);
      } else Serial.println("MPU6050 недоступен");
    } else if (!strcmp(line, "accel")) {
      float ax, ay, az;
      if (sensors::readAccel(ax, ay, az)) {
        Serial.printf("MPU6050%s X=%.3f Y=%.3f Z=%.3f |a|=%.3f g шаги=%lu\n",
#ifdef FAKE_SENSORS
                      " [FAKE_SENSORS]",
#else
                      "",
#endif
                      ax, ay, az, sqrtf(ax * ax + ay * ay + az * az), (unsigned long)P.stepDet.count());
      } else {
        Serial.println("MPU6050: ошибка чтения ускорений; проверь питание, SDA/SCL и наличие 0x68 в i2c");
      }
    } else if (!strcmp(line, "i2c")) {
      for (uint8_t a = 1; a < 127; a++) {
        if (sensors::i2cProbe(a)) Serial.printf("I2C 0x%02X\n", a);
      }
    } else if (!strcmp(line, "gps on")) {
      g_consoleGps.set(true, millis());
      gps::power(!P.power.critical());
      Serial.println(P.power.critical() ? "GPS выключен: критический заряд" : "GPS включён вручную на 10 мин; gps auto — автоматика");
    } else if (!strcmp(line, "gps off")) {
      g_consoleGps.set(false, millis());
      gps::power(false);
      Serial.println("GPS выключен вручную на 10 мин; gps auto — автоматика");
    } else if (!strcmp(line, "gps auto")) {
      g_consoleGps.clear();
      Serial.println("GPS: автоматика восстановлена");
    } else if (!strcmp(line, "gps echo")) {
      static bool echo = false;
      echo = !echo;
      gps::setEcho(echo);
    } else if (!strncmp(line, "pcas ", 5)) {
      gps::sendCommand(line + 5);
    } else if (!strncmp(line, "sleep ", 6)) {
      int32_t s = atol(line + 6);
      if (s < 1 || s > 3600) {
        Serial.println("sleep 1..3600");
      } else {
        Serial.printf("сплю %ld с; после пробуждения переоткрой монитор и введи st\n", (long)s);
        goToSleep(s);
      }
    } else if (!strncmp(line, "jump ", 5)) {
      int32_t d = atol(line + 5);
      clockx::setFromPhone(clockx::now() + d);
      onClockJump(d);
      Serial.printf("часы переставлены на %+ld с (как от телефона), t=%lu\n", (long)d, (unsigned long)N.now);
    } else if (!strcmp(line, "newhike")) {
      newHike(clockx::now());
      LOGI(M_SYS, "новый поход %lu (команда)", (unsigned long)P.hikeStart);
    } else if (!strcmp(line, "win")) {
      radio::openWindow(makeBeacon(), true);
    } else if (!strcmp(line, "info")) {
      Serial.printf("Чип: %s, ядер %d, PSRAM %u, SD %d (%lu/%lu МБ)\n", ESP.getChipModel(), ESP.getChipCores(),
                    ESP.getPsramSize(), storage::sdOk(), (unsigned long)storage::freeMb(),
                    (unsigned long)storage::totalMb());
    } else {
      Serial.println("? help");
    }
  }
}

}  // namespace

// ---------------------------------------------------------------------------
// Самопроверка (ТЗ, раздел 13) — общая для INFO и экрана
// ---------------------------------------------------------------------------

uint8_t buildChecks(proto::Check* c) {
  const sensors::Presence& pr = sensors::presence();
  uint8_t n = 0;
  c[n++] = {1, (uint8_t)(pr.oled ? 0 : 2), (uint8_t)(pr.oled ? 0 : 1), 0x3C};
  c[n++] = {2, (uint8_t)(pr.ina ? 0 : 2), (uint8_t)(pr.ina ? 0 : 1), 0x40};
  c[n++] = {3, (uint8_t)(pr.mpu ? 0 : 2), (uint8_t)(pr.mpu ? 0 : 1), 0x68};
  // Компас пока не калибруется (следующий этап) — честно «не откалиброван»
  c[n++] = {4, (uint8_t)(pr.compass ? 1 : 2), (uint8_t)(pr.compass ? 3 : 1), pr.compass ? pr.compassAddr : (uint8_t)0x0D};
  if (!pr.bme) c[n++] = {5, 2, 1, 0x76};
  else if (!pr.bmeIdOk) c[n++] = {5, 2, 2, pr.bmeAddr};
  else c[n++] = {5, 0, 0, pr.bmeAddr};
  if (!storage::sdOk()) c[n++] = {6, 2, 9, 0};
  else if (storage::freeMb() < 100) c[n++] = {6, 1, 10, 0};
  else c[n++] = {6, 0, 0, 0};
  if (!gps::isOn()) c[n++] = {7, 0, 7, 0};
  else if (gps::onSeconds() > 5 && !gps::alive()) c[n++] = {7, 2, 4, 0};
  else if (N.spoof) c[n++] = {7, 1, 5, 0};
  else if (!N.fix) c[n++] = {7, 1, 6, 0};
  else c[n++] = {7, 0, 0, 0};
  if (!N.pwr.batteryKnown()) c[n++] = {8, 1, 12, 0};
  else if (P.power.critical()) c[n++] = {8, 2, 8, 0};
  else if (P.power.low()) c[n++] = {8, 1, 8, 0};
  else c[n++] = {8, 0, 0, 0};
  if (!clockx::rtcPresent()) c[n++] = {9, 2, 1, 0};
  else if (!clockx::trusted()) c[n++] = {9, 1, 11, 0};
  else c[n++] = {9, 0, 0, 0};
  return n;
}

// ---------------------------------------------------------------------------

// Причина включения: счётчики в NVS (раз за включение — флеш не изнашиваем) и строка в журнал
void logBoot(Preferences& prefs) {
  esp_reset_reason_t rr = esp_reset_reason();
  const char* why;
  uint8_t level = logbuf::INFO;
  switch (rr) {
    case ESP_RST_POWERON: why = "питание"; break;
    case ESP_RST_EXT: why = "кнопка RESET"; break;
    case ESP_RST_SW: why = "программный сброс"; break;
    case ESP_RST_PANIC: why = "СБОЙ ПРОШИВКИ (panic)"; level = logbuf::ERR; break;
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT: why = "СТОРОЖЕВОЙ ТАЙМЕР (зависание)"; level = logbuf::ERR; break;
    case ESP_RST_BROWNOUT: why = "ПРОСАДКА ПИТАНИЯ"; level = logbuf::ERR; break;
    default: why = "другое"; break;
  }
  Preferences d;
  d.begin("diag", false);
  char key[8];
  snprintf(key, sizeof(key), "r%d", (int)rr);
  d.putULong(key, d.getULong(key, 0) + 1);
  uint32_t boots = d.getULong("boots", 0) + 1;
  d.putULong("boots", boots);
  dlog::log(level, logbuf::M_SYS, "включение #%lu: %s (код %d), прошивка %u.%u.%u, %s", (unsigned long)boots, why, (int)rr,
            FW_MAJOR, FW_MINOR, FW_PATCH, ESP.getChipModel());
  LOGI(M_SYS, "перезагрузки: питание %lu, сбой %lu, сторож %lu, просадка %lu", (unsigned long)d.getULong("r1", 0),
       (unsigned long)d.getULong("r4", 0),
       (unsigned long)(d.getULong("r5", 0) + d.getULong("r6", 0) + d.getULong("r7", 0)),
       (unsigned long)d.getULong("r9", 0));
  d.end();
  (void)prefs;
}

void setup() {
  setCpuFrequencyMhz(80);  // BLE и датчикам хватает, ток меньше
  Serial.begin(115200);
  g_bootMs = millis();
  dlog::begin();

  esp_sleep_wakeup_cause_t wake = esp_sleep_get_wakeup_cause();
  bool cold = wake == ESP_SLEEP_WAKEUP_UNDEFINED;

  memcpy(&P, g_rtcBlob, sizeof(P));
  if (P.magic != PERSIST_MAGIC) initPersist();
  P.bootCount++;

  loadSettings();
  applySettings();
  bool rtcOk = clockx::begin(cold);
  N.now = clockx::now();
  if (cold) {
    logBoot(g_prefs);
    if (!rtcOk) LOGE(M_RTC, "DS1302: нет ответа RAM; CLK=%d DAT=%d RST=%d, проверь питание и общий GND", PIN_RTC_CLK, PIN_RTC_DAT, PIN_RTC_RST);
    else if (!clockx::trusted()) LOGW(M_RTC, "часы не проверены: стояли или не ставились");
  } else {
    dlog::Counters& c = dlog::counters();
    if (wake == ESP_SLEEP_WAKEUP_TIMER) c.wakeTimer++;
    else if (wake == ESP_SLEEP_WAKEUP_EXT1) c.wakeButton++;
    else if (wake == ESP_SLEEP_WAKEUP_EXT0) c.wakeMotion++;
    if (!rtcOk) LOGW(M_RTC, "DS1302 не ответил после сна — время по внутренним часам");
    if (wake != ESP_SLEEP_WAKEUP_TIMER) {
      LOGD(M_PWR, "проснулся: %s", wake == ESP_SLEEP_WAKEUP_EXT1 ? "кнопка" : (wake == ESP_SLEEP_WAKEUP_EXT0 ? "движение" : "?"));
    }
  }
  sensors::begin(cold);
  if (wake == ESP_SLEEP_WAKEUP_EXT0) {
    sensors::clearMotion();
    g_motionCheckUntil = millis() + 30000;  // считаем шаги 30 с: вдруг пошли
  }
  gps::begin();
  int wakePin = -1;
  if (wake == ESP_SLEEP_WAKEUP_EXT1) {
    uint64_t st = esp_sleep_get_ext1_wakeup_status();
    wakePin = (st & (1ULL << PIN_BTN_A)) ? PIN_BTN_A : ((st & (1ULL << PIN_BTN_B)) ? PIN_BTN_B : -1);
  }
  ui::begin(cold, wakePin);

  if (cold) {
    delay(300);
    Serial.printf("\nvabuGEOde %u.%u.%u, чип %s%s\n", FW_MAJOR, FW_MINOR, FW_PATCH, ESP.getChipModel(),
#ifdef FAKE_SENSORS
                  " [FAKE_SENSORS]"
#else
                  ""
#endif
    );
    ui::splash(ESP.getChipModel());
    g_powerOnUtc = N.now;
    ensureStorage();
    {
      const sensors::Presence& pr = sensors::presence();
      if (storage::sdOk()) LOGI(M_SD, "SD %lu МБ, свободно %lu МБ", (unsigned long)storage::totalMb(), (unsigned long)storage::freeMb());
      else LOGE(M_SD, "SD не найдена — трек только в памяти");
      LOGI(M_SENS, "I2C: BME280 %s%s, INA219 %s, MPU6050 %s, компас %s, экран %s", pr.bme ? "да" : "НЕТ",
           pr.bme && !pr.bmeIdOk ? " (не BME!)" : "", pr.ina ? "да" : "НЕТ", pr.mpu ? "да" : "НЕТ",
           pr.compass ? "да" : "НЕТ", pr.oled ? "да" : "НЕТ");
      LOGI(M_SYS, "настройки: GPS %s/%u с, маячок %u с, запись %u/%u с, отладка %s", S.gpsMode ? "импульсы" : "всегда",
           S.gpsPulseS, S.beaconS, S.logMovingS, S.logCampS, S.debug ? "вкл" : "выкл");
    }
    loadBlacklist();
    // Продолжить поход, если последняя запись свежее 6 ч; иначе новый.
    // При непроверенных часах «6 ч назад» посчитать нельзя — начинаем новый: его начало и записи
    // сдвинутся, когда придёт время (onClockJump). Иначе остаётся поход с началом «из будущего»
    uint32_t hike = g_prefs.getULong("hike", 0);
    uint32_t last = g_prefs.getULong("last", 0);
    if (clockx::trusted() && hike && last && N.now >= last && N.now - last < 6 * 3600) {
      P.hikeStart = hike;
      P.lastPointTime = last;
      storage::openHike(hike);
      LOGI(M_SYS, "продолжаю поход %lu (последняя запись %lu с назад)", (unsigned long)hike, (unsigned long)(N.now - last));
    } else {
      newHike(N.now);
    }
    g_shiftFromPoint = clockx::trusted() ? NO_SHIFT : storage::pointCount();
    g_shiftFromMark = clockx::trusted() ? NO_SHIFT : storage::markCount();
    P.nextLogAt = N.now;
    P.nextBeaconAt = N.now;
    // После включения 2 мин окно подключения (без сопряжения новых) и не спим
    g_awakeUntil = millis() + 120000;
    radio::openWindow(makeBeacon(), false);
  } else if (wake == ESP_SLEEP_WAKEUP_EXT1) {
    g_awakeUntil = millis() + 3000;  // дать кнопке отработать
  }
  readEnvAndPower();
  g_lastSecondMs = millis() - 1000;  // первый тик — сразу, не ждать секунду
}

void loop() {
  uint32_t ms = millis();
  // Долгие итерации цикла: SD, BLE или датчик подвисли
  static uint32_t prevLoop = 0;
  if (prevLoop) {
    uint32_t dt = ms - prevLoop;
    if (dt > dlog::counters().maxLoopMs) dlog::counters().maxLoopMs = dt;
    if (dt > 2000) LOGW(M_SYS, "цикл стоял %lu мс", (unsigned long)dt);
  }
  prevLoop = ms;

  handleButton(ui::poll());

  // Акселерометр 25 Гц — шаги
  if (ms - g_lastAccelMs >= 40) {
    g_lastAccelMs = ms;
    float ax, ay, az;
    if (sensors::readAccel(ax, ay, az)) P.stepDet.feed(ax, ay, az, ms);
    else if (sensors::presence().mpu) dlog::counters().accelFail++;
  }

  gps::poll();

  if (ms - g_lastSecondMs >= 1000) {
    g_lastSecondMs = ms;
    secondTick();
  }

  uint8_t cmd[247];
  size_t len;
  while (radio::popCommand(cmd, len)) handleCommand(cmd, len);
  pumpSync();
  radio::poll();
  console();

  if (canSleep(millis())) goToSleep();
  delay(2);
}
