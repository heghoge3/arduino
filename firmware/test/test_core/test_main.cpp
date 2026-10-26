// Тесты ядра прошивки на компьютере: pio test -e native
// Протокол сверяется с теми же эталонными байтами, что и приложение (docs/protocol_vectors.json).
#include <unity.h>

#include <stdio.h>
#include <string.h>

#include <string>

#include "logic.h"
#include "nmea.h"
#include "proto.h"
#include "spoof.h"
#include "steps.h"
#include "sun.h"

#include <math.h>
#include <stdlib.h>

// ---------------------------------------------------------------------------
// Эталонные векторы
// ---------------------------------------------------------------------------

static std::string g_vectors;

static void loadVectors() {
  FILE* f = fopen("../docs/protocol_vectors.json", "rb");
  TEST_ASSERT_NOT_NULL_MESSAGE(f, "нет ../docs/protocol_vectors.json (запускать из папки firmware)");
  char buf[4096];
  size_t n;
  g_vectors.clear();
  while ((n = fread(buf, 1, sizeof(buf), f)) > 0) g_vectors.append(buf, n);
  fclose(f);
}

// Байты вектора по имени
static size_t vec(const char* name, uint8_t* out, size_t cap) {
  std::string key = std::string("\"") + name + "\": \"";
  size_t p = g_vectors.find(key);
  if (p == std::string::npos) {
    char msg[96];
    snprintf(msg, sizeof(msg), "нет вектора %s", name);
    TEST_FAIL_MESSAGE(msg);
  }
  p += key.size();
  size_t n = 0;
  while (g_vectors[p] != '"' && n < cap) {
    unsigned v;
    sscanf(g_vectors.c_str() + p, "%2x", &v);
    out[n++] = static_cast<uint8_t>(v);
    p += 2;
  }
  return n;
}

static void assertVector(const char* name, const uint8_t* got, size_t len) {
  uint8_t want[256];
  size_t wl = vec(name, want, sizeof(want));
  TEST_ASSERT_EQUAL_UINT_MESSAGE(wl, len, name);
  TEST_ASSERT_EQUAL_HEX8_ARRAY_MESSAGE(want, got, wl, name);
}

// ---------------------------------------------------------------------------
// Протокол: прошивка кодирует ровно те же байты, что ждёт приложение
// ---------------------------------------------------------------------------

void test_status_vector() {
  proto::Status m{};
  m.time = 1793000000; m.mode = 2;
  m.flags = proto::F_RECORDING | proto::F_GPS_ON | proto::F_SD_OK;
  m.fix = 3; m.satsUsed = 9; m.satsView = 12; m.hdop10 = 9;
  m.lat7 = 601580000; m.lon7 = 305150000; m.src = 1; m.spoofMask = 0;
  m.altGps10 = 713; m.speed10 = 43; m.course10 = 1234;
  m.tempRaw100 = 785; m.tempCorr100 = -125; m.hum100 = 8150; m.pressPa = 101237;
  m.altBaro10 = 682; m.trend100 = -340; m.battMv = 3987; m.battMa10 = 473; m.battPct = 83;
  m.usedMah = 512; m.steps = 12345; m.heading10 = 3599; m.distM = 6789; m.hoursLeft10 = 415;
  m.seq = 7; m.alerts = proto::AL_SPOOF;
  uint8_t b[128];
  size_t n = proto::encodeStatus(m, b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT(proto::STATUS_SIZE, n);
  assertVector("status", b, n);
}

void test_status_no_data_vector() {
  proto::Status m{};
  m.time = 1793000001; m.mode = 1; m.hdop10 = proto::HDOP_NA;
  m.altGps10 = proto::NA16; m.tempRaw100 = proto::NA16; m.tempCorr100 = proto::NA16;
  m.altBaro10 = proto::NA16; m.trend100 = proto::NA16; m.battMa10 = -1200; m.seq = 8;
  uint8_t b[128];
  assertVector("status_no_data", b, proto::encodeStatus(m, b, sizeof(b)));
}

void test_sats_info_mark_vectors() {
  uint8_t b[128];
  proto::Sat sats[] = {{0, 5, 41, 1}, {1, 33, 18, 0}};
  assertVector("sats", b, proto::encodeSats(sats, 2, b, sizeof(b)));

  proto::Info info{};
  info.fwMajor = 0; info.fwMinor = 1; info.fwPatch = 0; info.chip = 1;
  info.uptime = 3600; info.sdTotalMb = 7580; info.sdFreeMb = 7400; info.gnssMask = 3;
  info.hikeStart = 1793000000; info.gpsMode = 1; info.gpsPulseS = 45; info.beaconS = 30;
  info.nChecks = 2;
  info.checks[0] = {4, 1, 3, 0x0D};
  info.checks[1] = {7, 0, 0, 0};
  assertVector("info", b, proto::encodeInfo(info, b, sizeof(b)));

  proto::Mark mk{};
  mk.id = 7; mk.time = 1793000100; mk.type = 2; mk.lat7 = 601000000; mk.lon7 = 305000000;
  const char* t = "Граффити";
  mk.textLen = static_cast<uint8_t>(strlen(t));
  memcpy(mk.text, t, mk.textLen);
  size_t n = proto::encodeMark(mk, b, sizeof(b));
  assertVector("mark", b, n);
  proto::Mark back{};
  TEST_ASSERT_TRUE(proto::decodeMark(b, n, back));
  TEST_ASSERT_EQUAL_STRING(t, back.text);
}

void test_point_vector_and_roundtrip() {
  proto::Point p{};
  p.time = 1793000010; p.lat7 = 601200000; p.lon7 = 305500000; p.src = 2; p.spoof = true;
  p.spoofMask = 0b101011; p.spoofLat7 = 600346000; p.spoofLon7 = 305693000;
  p.tempRaw100 = 200; p.tempCorr100 = 50; p.hum100 = 9000; p.pressPa = 100350;
  p.altBaro10 = 1100; p.altGps10 = 120; p.battMv = 3900; p.battPct = 79; p.sats = 10; p.hdop10 = 7;
  p.steps = 16890; p.heading10 = 850; p.speed10 = 315;
  uint8_t b[64];
  size_t n = proto::encodePoint(p, b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT(proto::POINT_SIZE, n);
  assertVector("point_spoof", b, n);
  proto::Point q{};
  TEST_ASSERT_TRUE(proto::decodePoint(b, n, q));
  TEST_ASSERT_EQUAL_INT32(p.spoofLat7, q.spoofLat7);
  TEST_ASSERT_TRUE(q.spoof);
  TEST_ASSERT_EQUAL_UINT8(2, q.src);
}

void test_small_messages_vectors() {
  uint8_t b[32];
  assertVector("sync_done", b, proto::encodeSyncDone(proto::CMD_SYNC_TRACK, 1234, b, sizeof(b)));
  assertVector("ack", b, proto::encodeAck(proto::CMD_MARK, proto::ACK_BAD_ARGS, b, sizeof(b)));
  assertVector("alert", b, proto::encodeAlert(1793000300, proto::AL_SPOOF | proto::AL_LOW_BATT, proto::AL_SPOOF, 14, 0b1000101, b, sizeof(b)));

  proto::Beacon bc{};
  bc.mode = 1; bc.flags = proto::B_RECORDING | proto::B_FIX | proto::B_SD_OK; bc.alerts = 0;
  bc.tempCorr100 = -125; bc.battPct = 83; bc.satsUsed = 9; bc.press10 = 10124;
  bc.lastRecord = 1793000000; bc.hikeId = 0x0240; bc.counter = 200;
  size_t n = proto::encodeBeacon(bc, b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT(proto::BEACON_SIZE, n);
  assertVector("beacon", b, n);
}

void test_commands_from_phone() {
  uint8_t b[300];
  proto::Command c;
  size_t n;

  n = vec("cmd_set_time", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(proto::CMD_SET_TIME, c.code);
  TEST_ASSERT_EQUAL_UINT32(1793000000, c.time);
  TEST_ASSERT_EQUAL_INT16(180, c.tzMin);

  n = vec("cmd_phone_pos", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_INT32(599386000, c.lat7);
  TEST_ASSERT_EQUAL_UINT16(25, c.accuracyM);

  n = vec("cmd_mark", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(4, c.markType);
  TEST_ASSERT_EQUAL_STRING("Башня", c.text);

  n = vec("cmd_blacklist", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(1, c.blCount);
  TEST_ASSERT_EQUAL_INT32(600346000, c.bl[0].lat7);
  TEST_ASSERT_EQUAL_UINT16(1000, c.bl[0].radiusM);

  n = vec("cmd_set_intervals", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT16(10, c.a);
  TEST_ASSERT_EQUAL_UINT16(60, c.b);

  n = vec("cmd_calib_alt", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_INT16(455, c.altDm);

  n = vec("cmd_session_live", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(1, c.flag);

  n = vec("cmd_set_gps_mode", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(1, c.flag);
  TEST_ASSERT_EQUAL_UINT16(45, c.a);

  n = vec("cmd_set_radio", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT16(30, c.a);

  const char* simple[] = {"cmd_record_on", "cmd_clap", "cmd_get_info", "cmd_bye", "cmd_ack_alert", "cmd_sync_track"};
  for (const char* name : simple) {
    n = vec(name, b, sizeof(b));
    TEST_ASSERT_EQUAL_UINT8_MESSAGE(proto::ACK_OK, proto::decodeCommand(b, n, c), name);
  }
}

void test_bad_commands() {
  proto::Command c;
  uint8_t oldVer[] = {1, proto::CMD_CLAP};
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_UNSUPPORTED, proto::decodeCommand(oldVer, 2, c));
  uint8_t unknown[] = {2, 0x7F};
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_UNSUPPORTED, proto::decodeCommand(unknown, 2, c));
  uint8_t shortTime[] = {2, proto::CMD_SET_TIME, 1, 2};
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_BAD_ARGS, proto::decodeCommand(shortTime, 4, c));
  uint8_t badRadio[] = {2, proto::CMD_SET_RADIO, 5, 0};  // 5 с — меньше минимума
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_BAD_ARGS, proto::decodeCommand(badRadio, 4, c));
  uint8_t longText[] = {2, proto::CMD_MARK, 1, 100};
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_BAD_ARGS, proto::decodeCommand(longText, 4, c));
}

void test_utf8_limit() {
  std::string s;
  for (int i = 0; i < 40; i++) s += "я";  // 80 байт
  TEST_ASSERT_EQUAL_UINT(60, proto::utf8Limit(s.c_str(), s.size(), 60));
  TEST_ASSERT_EQUAL_UINT(58, proto::utf8Limit(s.c_str(), s.size(), 59));  // не режем символ пополам
  TEST_ASSERT_EQUAL_UINT(5, proto::utf8Limit("hello", 5, 60));
}

// ---------------------------------------------------------------------------
// NMEA
// ---------------------------------------------------------------------------

static void feedAll(nmea::Parser& p, const char* const* lines, size_t n) {
  for (size_t i = 0; i < n; i++) {
    for (const char* c = lines[i]; *c; c++) p.feed(*c);
    p.feed('\r');
    p.feed('\n');
  }
}

void test_nmea_fix() {
  const char* lines[] = {
      "$GNRMC,143015.000,A,6009.4800,N,03030.9000,E,2.32,45.6,261026,,,A*7D",
      "$GNGGA,143015.000,6009.4800,N,03030.9000,E,1,09,0.9,71.3,M,15.2,M,,*79",
      "$GPGSA,A,3,05,07,13,15,,,,,,,,,1.6,0.9,1.3,1*27",
      "$BDGSA,A,3,21,26,,,,,,,,,,,1.6,0.9,1.3,4*30",
      "$GPGSV,2,1,06,05,45,120,41,07,30,200,38,13,60,050,44,15,20,300,,0*6B",
      "$GPGSV,2,2,06,18,10,090,22,20,05,010,,0*64",
      "$BDGSV,1,1,02,21,40,100,35,26,35,220,30,0*77",
  };
  nmea::Parser p;
  feedAll(p, lines, 7);
  const nmea::Fix& f = p.fix();
  TEST_ASSERT_TRUE(f.valid);
  TEST_ASSERT_DOUBLE_WITHIN(1e-6, 60.158, f.lat);
  TEST_ASSERT_DOUBLE_WITHIN(1e-6, 30.515, f.lon);
  TEST_ASSERT_TRUE(f.timeValid);
  TEST_ASSERT_EQUAL_UINT32(1793025015, f.utc);
  TEST_ASSERT_EQUAL_UINT8(3, f.fixType);
  TEST_ASSERT_EQUAL_UINT8(9, f.satsUsed);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 0.9, f.hdop);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 71.3, f.altM);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 2.32 * 1.852, f.speedKmh);
  TEST_ASSERT_EQUAL_UINT32(7, p.sentences());
  TEST_ASSERT_EQUAL_UINT32(0, p.checksumErrors());

  // 6 спутников GPS + 2 BeiDou; используемые — из GSA своей системы
  TEST_ASSERT_EQUAL_UINT(8, p.satCount());
  int used = 0, bds = 0;
  for (size_t i = 0; i < p.satCount(); i++) {
    if (p.sat(i).used) used++;
    if (p.sat(i).system == nmea::SYS_BDS) bds++;
  }
  TEST_ASSERT_EQUAL_INT(6, used);  // 05 07 13 15 + 21 26
  TEST_ASSERT_EQUAL_INT(2, bds);
  // Спутник 15 без SNR — 0
  for (size_t i = 0; i < p.satCount(); i++) {
    if (p.sat(i).system == nmea::SYS_GPS && p.sat(i).prn == 15) TEST_ASSERT_EQUAL_UINT8(0, p.sat(i).snr);
  }
  TEST_ASSERT_EQUAL_UINT8((1 << nmea::SYS_GPS) | (1 << nmea::SYS_BDS), p.systemsSeen());
}

void test_nmea_lost_fix_and_errors() {
  nmea::Parser p;
  TEST_ASSERT_TRUE(p.parseLine("$GNRMC,143015.000,A,6009.4800,N,03030.9000,E,2.32,45.6,261026,,,A*7D"));
  TEST_ASSERT_TRUE(p.fix().valid);
  TEST_ASSERT_TRUE(p.parseLine("$GNRMC,143016.000,V,,,,,,,261026,,,N*53"));
  TEST_ASSERT_FALSE(p.fix().valid);
  TEST_ASSERT_TRUE(p.parseLine("$GNGGA,143016.000,,,,,0,00,99.9,,,,,,*70"));
  TEST_ASSERT_EQUAL_UINT8(0, p.fix().quality);
  // Испорченная контрольная сумма
  TEST_ASSERT_FALSE(p.parseLine("$GNRMC,143015.000,A,6009.4800,N,03030.9000,E,2.32,45.6,261026,,,A*7E"));
  TEST_ASSERT_EQUAL_UINT32(1, p.checksumErrors());
  // Мусор между предложениями не ломает разбор
  nmea::Parser q;
  const char* junk = "\xff~garbage$GNGGA,143015.000,6009.4800,N,03030.9000,E,1,09,0.9,71.3,M,15.2,M,,*79\r\n";
  for (const char* c = junk; *c; c++) q.feed(*c);
  TEST_ASSERT_EQUAL_UINT8(9, q.fix().satsUsed);
}

void test_nmea_command_build() {
  char out[48];
  size_t n = nmea::buildCommand("PCAS04,3", out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("$PCAS04,3*1A\r\n", out);
  TEST_ASSERT_EQUAL_UINT(14, n);
  TEST_ASSERT_EQUAL_UINT32(0, nmea::toUnix(1970, 1, 1, 0, 0, 0));
  TEST_ASSERT_EQUAL_UINT32(1793025015, nmea::toUnix(2026, 10, 26, 14, 30, 15));
}

// ---------------------------------------------------------------------------
// Подмена
// ---------------------------------------------------------------------------

static spoof::Input inputAt(uint32_t t, double lat, double lon) {
  spoof::Input in{};
  in.now = t;
  in.timeTrusted = true;
  in.lat = lat;
  in.lon = lon;
  return in;
}

void test_spoof_blacklist_and_jump() {
  spoof::Detector d;
  double bl[] = {60.0346}, bo[] = {30.5693};
  uint16_t br[] = {1000};
  d.setBlacklist(bl, bo, br, 1);

  // Нормальная ходьба: 10 с, ~12 м
  TEST_ASSERT_FALSE(d.check(inputAt(1000, 60.1580, 30.5150)).spoof);
  TEST_ASSERT_FALSE(d.check(inputAt(1010, 60.1581, 30.5151)).spoof);

  // Внезапно 13 км на юг — в известную ложную точку
  spoof::Result r = d.check(inputAt(1020, 60.0346, 30.5693));
  TEST_ASSERT_TRUE(r.spoof);
  TEST_ASSERT_TRUE(r.mask & spoof::R_BLACKLIST);
  TEST_ASSERT_TRUE(r.mask & spoof::R_JUMP);
  // Последняя достоверная точка не сдвинулась
  TEST_ASSERT_DOUBLE_WITHIN(1e-6, 60.1581, d.lastGoodLat());

  // Неизвестная ложная точка далеко — только скачок, этого достаточно
  spoof::Detector d2;
  d2.check(inputAt(1000, 60.1580, 30.5150));
  r = d2.check(inputAt(1030, 59.0, 31.0));
  TEST_ASSERT_TRUE(r.spoof);
  TEST_ASSERT_EQUAL_UINT8(spoof::R_JUMP, r.mask);
}

void test_spoof_phone_and_time() {
  spoof::Detector d;
  d.setPhone(60.1580, 30.5150, 50, 1000);
  TEST_ASSERT_FALSE(d.check(inputAt(1100, 60.1600, 30.5200)).spoof);  // ~400 м — норма
  spoof::Result r = d.check(inputAt(1200, 60.2500, 30.5150));          // ~10 км от телефона
  TEST_ASSERT_TRUE(r.mask & spoof::R_PHONE);
  // Позиция телефона устарела — проверка не работает
  spoof::Detector d2;
  d2.setPhone(60.1580, 30.5150, 50, 1000);
  TEST_ASSERT_FALSE(d2.check(inputAt(1000 + 3600, 60.2500, 30.5150)).mask & spoof::R_PHONE);

  // Время GPS уехало на час от часов
  spoof::Detector d3;
  spoof::Input in = inputAt(5000, 60.1580, 30.5150);
  in.gpsTimeValid = true;
  in.gpsTime = 5000 + 3600;
  r = d3.check(in);
  TEST_ASSERT_TRUE(r.spoof);
  TEST_ASSERT_TRUE(r.mask & spoof::R_TIME);
  // Время не проверено — сравнивать не с чем
  in.timeTrusted = false;
  spoof::Detector d4;
  TEST_ASSERT_FALSE(d4.check(in).mask & spoof::R_TIME);
}

void test_spoof_weak_signs_need_two() {
  uint8_t flat[] = {41, 42, 41, 42, 41, 42};
  uint8_t normal[] = {22, 35, 41, 30, 18, 44};
  spoof::Detector d;
  spoof::Input in = inputAt(1000, 60.1580, 30.5150);
  in.usedSnr = flat;
  in.usedCount = 6;
  spoof::Result r = d.check(in);
  TEST_ASSERT_TRUE(r.mask & spoof::R_SNR_FLAT);
  TEST_ASSERT_FALSE_MESSAGE(r.spoof, "один слабый признак — ещё не подмена");

  // SNR одинаковый + высота GPS расходится с барометром на 300 м — уже подмена
  in.altValid = true; in.altGps = 400; in.baroValid = true; in.altBaro = 100;
  r = d.check(in);
  TEST_ASSERT_TRUE(r.spoof);

  in.usedSnr = normal;
  in.altGps = 110;
  r = d.check(in);
  TEST_ASSERT_FALSE(r.spoof);
  TEST_ASSERT_EQUAL_UINT8(0, r.mask);
}

void test_spoof_steps_but_standing() {
  spoof::Detector d;
  uint8_t flat[] = {41, 42, 41, 42, 41};
  for (uint32_t t = 0; t <= 180; t += 10) {
    spoof::Input in = inputAt(1000 + t, 60.0346, 30.5693);  // точка на месте
    in.steps = t;                                            // а шаги идут (1 шаг/с)
    in.usedSnr = flat;
    in.usedCount = 5;
    spoof::Result r = d.check(in);
    if (t >= 120) {
      TEST_ASSERT_TRUE(r.mask & spoof::R_ACCEL);
      TEST_ASSERT_TRUE(r.spoof);  // шаги + SNR — два слабых
    }
  }
}

// ---------------------------------------------------------------------------
// Логика состояний
// ---------------------------------------------------------------------------

void test_power_moving_and_camp() {
  logic::PowerLogic p;
  uint32_t steps = 0;
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(0, 80, steps));
  // 5 шагов — ещё не поход
  steps = 5;
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(10, 80, steps));
  // 12 шагов за 20 с — в пути
  steps = 12;
  TEST_ASSERT_EQUAL(logic::MOVING, p.update(20, 80, steps));
  TEST_ASSERT_EQUAL_UINT16(10, p.logIntervalS());
  TEST_ASSERT_TRUE(p.gpsContinuous(logic::GPS_ALWAYS));
  TEST_ASSERT_FALSE(p.gpsContinuous(logic::GPS_PULSE));
  TEST_ASSERT_TRUE(p.gpsPulsed(logic::GPS_PULSE));
  // Остановились: 4 мин ещё в пути, 5 мин — стоянка
  TEST_ASSERT_EQUAL(logic::MOVING, p.update(20 + 240, 80, steps));
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(20 + 300, 80, steps));
  TEST_ASSERT_EQUAL_UINT16(60, p.logIntervalS());
  TEST_ASSERT_FALSE(p.gpsContinuous(logic::GPS_ALWAYS));
  TEST_ASSERT_EQUAL_UINT16(60, p.beaconIntervalS(30));
  TEST_ASSERT_EQUAL_UINT16(0, p.beaconIntervalS(0));  // невидимка
  // Медленные одиночные шаги (раз в минуту) не включают «в пути»
  logic::PowerLogic q;
  for (uint32_t t = 0; t < 600; t += 60) q.update(t, 80, t / 60);
  TEST_ASSERT_EQUAL(logic::CAMP, q.state());
}

void test_power_battery_hysteresis() {
  logic::PowerLogic p;
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(0, 20, 0));
  TEST_ASSERT_EQUAL(logic::ECONOMY, p.update(10, 14, 0));
  TEST_ASSERT_EQUAL(logic::ECONOMY, p.update(20, 16, 0));  // гистерезис: ещё экономия
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(30, 19, 0));
  TEST_ASSERT_EQUAL(logic::CRITICAL, p.update(40, 4, 0));
  TEST_ASSERT_EQUAL_UINT16(0, p.beaconIntervalS(30));
  TEST_ASSERT_EQUAL_UINT16(300, p.logIntervalS());
  TEST_ASSERT_EQUAL(logic::CRITICAL, p.update(50, 7, 0));
  TEST_ASSERT_EQUAL(logic::ECONOMY, p.update(60, 9, 0));
}

void test_gps_pulse() {
  logic::GpsPulse g;
  g.configure(30, 20, 3);
  TEST_ASSERT_TRUE(g.update(0, false, false));   // включился
  TEST_ASSERT_TRUE(g.update(3, true, true));     // 1 точка
  TEST_ASSERT_TRUE(g.update(4, true, true));     // 2
  TEST_ASSERT_FALSE(g.update(5, true, true));    // 3 — хватит, выключился
  TEST_ASSERT_FALSE(g.update(20, false, false)); // ждём
  TEST_ASSERT_TRUE(g.update(30, false, false));  // следующий импульс через 30 с от начала
  TEST_ASSERT_TRUE(g.update(45, false, false));
  TEST_ASSERT_FALSE(g.update(50, false, false)); // 20 с без фикса — сдались до следующего раза
  TEST_ASSERT_TRUE(g.update(60, false, false));
}

void test_alerts() {
  logic::Alerts a;
  logic::Alerts::Decision d = a.update(0, 0);
  TEST_ASSERT_FALSE(d.openWindow);
  // Появилась подмена — окно сразу
  d = a.update(100, proto::AL_SPOOF);
  TEST_ASSERT_TRUE(d.openWindow);
  TEST_ASSERT_EQUAL_UINT8(proto::AL_SPOOF, d.fresh);
  // Повторы раз в 10 мин, не больше трёх
  TEST_ASSERT_FALSE(a.update(200, proto::AL_SPOOF).openWindow);
  TEST_ASSERT_TRUE(a.update(700, proto::AL_SPOOF).openWindow);
  TEST_ASSERT_TRUE(a.update(1300, proto::AL_SPOOF).openWindow);
  TEST_ASSERT_TRUE(a.update(1900, proto::AL_SPOOF).openWindow);
  TEST_ASSERT_FALSE(a.update(2500, proto::AL_SPOOF).openWindow);
  // Ушла и вернулась — снова тревога
  a.update(2600, 0);
  TEST_ASSERT_TRUE(a.update(2700, proto::AL_SPOOF).openWindow);
  // Подтвердили — без повторов
  a.ack(proto::AL_SPOOF);
  TEST_ASSERT_FALSE(a.update(3400, proto::AL_SPOOF).openWindow);
  // Новая тревога рядом с подтверждённой — окно, свежая только новая
  d = a.update(3500, proto::AL_SPOOF | proto::AL_LOW_BATT);
  TEST_ASSERT_TRUE(d.openWindow);
  TEST_ASSERT_EQUAL_UINT8(proto::AL_LOW_BATT, d.fresh);
}

// ---------------------------------------------------------------------------
// Шаги
// ---------------------------------------------------------------------------

// Ходьба: вертикальное ускорение ~ синус с частотой шагов + шум
static uint32_t simulateWalk(steps::Detector& d, float hz, float amp, float noise, uint32_t ms, uint32_t t0 = 0) {
  srand(1);
  uint32_t start = d.count();
  for (uint32_t t = 0; t < ms; t += 40) {  // 25 Гц, как в прошивке
    float n = noise * ((rand() % 2001) / 1000.0f - 1.0f);
    float z = 1.0f + amp * sinf(2 * (float)M_PI * hz * t / 1000.0f) + n;
    d.feed(0.02f, 0.01f, z, t0 + t);
  }
  return d.count() - start;
}

void test_steps_walking() {
  steps::Detector d;
  uint32_t n = simulateWalk(d, 1.8f, 0.35f, 0.03f, 20000);  // 20 с, 1,8 шага/с ≈ 36
  TEST_ASSERT_INT_WITHIN(4, 36, (int)n);
}

void test_steps_noise_and_bumps() {
  steps::Detector d;
  // Лежит в рюкзаке в машине: только мелкий шум
  TEST_ASSERT_EQUAL_UINT32(0, simulateWalk(d, 0.0f, 0.0f, 0.05f, 20000));
  // Одиночный толчок (поставили рюкзак) — не шаг
  steps::Detector e;
  for (uint32_t t = 0; t < 5000; t += 40) {
    float z = (t >= 2000 && t < 2120) ? 1.6f : 1.0f;
    e.feed(0, 0, z, t);
  }
  TEST_ASSERT_EQUAL_UINT32(0, e.count());
}

void test_sunset() {
  // Петербург, 26.10.2026: закат ~17:17–17:20 МСК (как в приложении), 21.06 ~22:26
  uint32_t t;
  TEST_ASSERT_TRUE(sun::sunset(nmea::toUnix(2026, 10, 26, 12, 0, 0), 59.9386, 30.3141, t));
  TEST_ASSERT_INT_WITHIN(6 * 60, nmea::toUnix(2026, 10, 26, 14, 20, 0), (int)t);
  TEST_ASSERT_TRUE(sun::sunset(nmea::toUnix(2026, 6, 21, 12, 0, 0), 59.9386, 30.3141, t));
  TEST_ASSERT_INT_WITHIN(5 * 60, nmea::toUnix(2026, 6, 21, 19, 26, 0), (int)t);
  // Мурманск, полярная ночь
  TEST_ASSERT_FALSE(sun::sunset(nmea::toUnix(2026, 12, 20, 12, 0, 0), 68.97, 33.07, t));
}

int main(int, char**) {
  UNITY_BEGIN();
  loadVectors();
  RUN_TEST(test_status_vector);
  RUN_TEST(test_status_no_data_vector);
  RUN_TEST(test_sats_info_mark_vectors);
  RUN_TEST(test_point_vector_and_roundtrip);
  RUN_TEST(test_small_messages_vectors);
  RUN_TEST(test_commands_from_phone);
  RUN_TEST(test_bad_commands);
  RUN_TEST(test_utf8_limit);
  RUN_TEST(test_nmea_fix);
  RUN_TEST(test_nmea_lost_fix_and_errors);
  RUN_TEST(test_nmea_command_build);
  RUN_TEST(test_spoof_blacklist_and_jump);
  RUN_TEST(test_spoof_phone_and_time);
  RUN_TEST(test_spoof_weak_signs_need_two);
  RUN_TEST(test_spoof_steps_but_standing);
  RUN_TEST(test_power_moving_and_camp);
  RUN_TEST(test_power_battery_hysteresis);
  RUN_TEST(test_gps_pulse);
  RUN_TEST(test_alerts);
  RUN_TEST(test_steps_walking);
  RUN_TEST(test_steps_noise_and_bumps);
  RUN_TEST(test_sunset);
  return UNITY_END();
}
