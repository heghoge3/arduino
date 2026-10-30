// Тесты ядра прошивки на компьютере: pio test -e native
// Протокол сверяется с теми же эталонными байтами, что и приложение (docs/protocol_vectors.json).
#include <unity.h>

#include <stdio.h>
#include <string.h>

#include <string>
#include <set>
#include "hike_files.h"
#include "pdr.h"
#include "walk_distance.h"
#include "blacklist_defaults.h"
#include "compass.h"
#include "inertial.h"

#include "logbuf.h"
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

  n = vec("cmd_sync_log", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(proto::CMD_SYNC_LOG, c.code);
  TEST_ASSERT_EQUAL_UINT32(4241, c.time);
  n = vec("cmd_set_debug", b, sizeof(b));
  TEST_ASSERT_EQUAL_UINT8(proto::ACK_OK, proto::decodeCommand(b, n, c));
  TEST_ASSERT_EQUAL_UINT8(1, c.flag);
}

// ---------------------------------------------------------------------------
// Журнал: LOG по протоколу, кольцевой буфер, строки файла
// ---------------------------------------------------------------------------

void test_log_vector() {
  proto::LogLine l{};
  l.seq = 4242; l.time = 1793000020; l.uptime = 3605;
  l.level = logbuf::WARN | logbuf::TIME_OK; l.module = logbuf::M_GPS;
  const char* t = "Фикс потерян, спутн 3";
  l.textLen = (uint8_t)strlen(t);
  memcpy(l.text, t, l.textLen);
  uint8_t b[128];
  size_t n = proto::encodeLog(l, b, sizeof(b));
  assertVector("log", b, n);
  proto::LogLine back{};
  TEST_ASSERT_TRUE(proto::decodeLog(b, n, back));
  TEST_ASSERT_EQUAL_UINT32(4242, back.seq);
  TEST_ASSERT_EQUAL_STRING(t, back.text);
  // Длинный текст обрезается по границе символа и влезает в пакет
  char longText[200];
  for (int i = 0; i < 50; i++) memcpy(longText + i * 2, "ж", 2);
  l.textLen = 100;
  memcpy(l.text, longText, proto::MAX_LOG_TEXT);
  n = proto::encodeLog(l, b, sizeof(b));
  TEST_ASSERT_TRUE(n > 0 && n <= proto::MAX_PACKET);
}

void test_log_ring() {
  static logbuf::Ring r;
  r.init();
  TEST_ASSERT_TRUE(r.valid());
  // Номер ещё не известен — строки ждут его
  r.push(0, 1, logbuf::INFO, logbuf::M_SYS, "старт", 10);
  r.push(0, 2, logbuf::ERR, logbuf::M_SD, "SD нет", 7);
  TEST_ASSERT_EQUAL_UINT32(logbuf::NO_SEQ, r.pendingAt(0).seq);
  r.assignSeq(100);
  TEST_ASSERT_EQUAL_UINT32(100, r.pendingAt(0).seq);
  TEST_ASSERT_EQUAL_UINT32(101, r.pendingAt(1).seq);
  r.push(0, 3, logbuf::DBG, logbuf::M_BLE, "x", 1);
  TEST_ASSERT_EQUAL_UINT32(102, r.pendingAt(2).seq);
  r.markFlushed(3);
  TEST_ASSERT_EQUAL_UINT32(0, r.pending());
  // Переполнение без SD: теряются самые старые, потери считаются
  for (int i = 0; i < logbuf::RING_N + 5; i++) r.push(0, i, logbuf::INFO, logbuf::M_SYS, "a", 1);
  TEST_ASSERT_EQUAL_UINT32(logbuf::RING_N, r.pending());
  TEST_ASSERT_EQUAL_UINT32(5, r.dropped);
  TEST_ASSERT_EQUAL_UINT32(103 + 5, r.pendingAt(0).seq);
  TEST_ASSERT_TRUE(r.valid());
  // Мусор в RTC-памяти после включения питания — не принимается за журнал
  r.check ^= 1;
  TEST_ASSERT_FALSE(r.valid());
}

void test_log_format_and_days() {
  proto::LogLine l{};
  l.time = nmea::toUnix(2026, 10, 31, 14, 3, 22);
  l.uptime = 125;
  l.level = logbuf::INFO | logbuf::TIME_OK;
  l.module = logbuf::M_PWR;
  strcpy(l.text, "стоянка -> в пути");
  char out[160];
  logbuf::formatLine(l, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("2026-10-31 14:03:22 +125 I PWR стоянка -> в пути", out);
  l.level = logbuf::ERR;  // время не проверено
  logbuf::formatLine(l, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("2026-10-31 14:03:22? +125 E PWR стоянка -> в пути", out);
  l.time = 0;
  logbuf::formatLine(l, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("---- +125 E PWR стоянка -> в пути", out);

  char day[12];
  logbuf::dayName(nmea::toUnix(2026, 10, 31, 23, 59, 0), day, sizeof(day));
  TEST_ASSERT_EQUAL_STRING("20261031", day);
  TEST_ASSERT_EQUAL_INT32((int32_t)(nmea::toUnix(2026, 10, 31, 0, 0, 0) / 86400), (int32_t)logbuf::dayNumber("20261031"));
  TEST_ASSERT_EQUAL_INT32(30, (int32_t)(logbuf::dayNumber("20261031") - logbuf::dayNumber("20261001")));
  TEST_ASSERT_EQUAL_INT32(-1, (int32_t)logbuf::dayNumber("all.bin."));
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

void test_spoof_jump_after_pause() {
  // Пауза записи / стоянка с выключенным GPS, потом переезд на машине на 10 км — не подмена
  spoof::Detector d;
  d.check(inputAt(1000, 60.1580, 30.5150));
  TEST_ASSERT_FALSE(d.check(inputAt(1000 + 900, 60.2480, 30.5150)).mask & spoof::R_JUMP);
  // А тот же скачок через минуту при включённом GPS — скачок
  spoof::Detector d2;
  d2.check(inputAt(1000, 60.1580, 30.5150));
  TEST_ASSERT_TRUE(d2.check(inputAt(1060, 60.2480, 30.5150)).mask & spoof::R_JUMP);
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
  // После подмены сначала подтверждаем устойчивое восстановление.
  for (uint32_t t = 1001; t <= 1011; t++) { in.now = t; r = d.check(in); }
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

void test_spoof_recovery() {
  spoof::Detector d;
  double lat[] = {60.1161}, lon[] = {31.3864};
  uint16_t radius[] = {1000};
  d.setBlacklist(lat, lon, radius, 1);
  TEST_ASSERT_TRUE(d.check(inputAt(1000, lat[0], lon[0])).spoof);
  for (uint32_t t = 1001; t <= 1010; t++) {
    auto r = d.check(inputAt(t, 60.1161, 31.363));
    TEST_ASSERT_TRUE(r.spoof);
    TEST_ASSERT_TRUE(r.mask & spoof::R_RECOVERY);
    TEST_ASSERT_FALSE(d.hasLastGood());
  }
  // Новый ложный фикс сбрасывает накопленное подтверждение.
  TEST_ASSERT_TRUE(d.check(inputAt(1011, lat[0], lon[0])).spoof);
  TEST_ASSERT_TRUE(d.check(inputAt(1012, 60.1161, 31.363)).spoof);
  // Долгое отсутствие сигнала также не считается восстановлением.
  TEST_ASSERT_TRUE(d.check(inputAt(1100, 60.1161, 31.363)).spoof);
  for (uint32_t t = 1101; t < 1110; t++) TEST_ASSERT_TRUE(d.check(inputAt(t, 60.1161, 31.363)).spoof);
  TEST_ASSERT_FALSE(d.check(inputAt(1110, 60.1161, 31.363)).spoof);
  TEST_ASSERT_TRUE(d.hasLastGood());
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

void test_clock_jump_back() {
  // Телефон перевёл часы назад на ~3 ч: расписание импульсов и «в пути» сдвигаются вместе с часами
  const uint32_t T = 1793000000;
  const int32_t d = -10713;
  logic::GpsPulse g;
  g.configure(30, 20, 3);
  TEST_ASSERT_TRUE(g.update(T, false, false));
  g.update(T + 3, true, true);
  g.update(T + 4, true, true);
  TEST_ASSERT_FALSE(g.update(T + 5, true, true));
  g.shiftTime(d);
  TEST_ASSERT_TRUE(g.update(T + 30 + d, false, false));  // без сдвига ждал бы 3 ч

  logic::PowerLogic p;
  p.update(T, 80, 0);
  TEST_ASSERT_EQUAL(logic::MOVING, p.update(T + 20, 80, 12));
  p.shiftTime(d);
  TEST_ASSERT_EQUAL(logic::MOVING, p.update(T + 20 + d + 240, 80, 12));
  TEST_ASSERT_EQUAL(logic::CAMP, p.update(T + 20 + d + 300, 80, 12));

  uint32_t unset = 0, at = T;
  logic::shiftTime(unset, d);
  logic::shiftTime(at, d);
  TEST_ASSERT_EQUAL_UINT32(0, unset);
  TEST_ASSERT_EQUAL_UINT32(T + d, at);
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

void test_steps_gentle_and_resume() {
  steps::Detector d;
  TEST_ASSERT_INT_WITHIN(4, 36, (int)simulateWalk(d, 1.8f, 0.12f, 0.01f, 20000, 600000));
  uint32_t before = d.count();
  d.resume();
  TEST_ASSERT_EQUAL_UINT32(before, d.count());
  TEST_ASSERT_INT_WITHIN(4, 36, (int)simulateWalk(d, 1.8f, 0.12f, 0.01f, 20000));
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

void test_hike_commands() {
  uint8_t cmd[] = {proto::VERSION, proto::CMD_DELETE_HIKE, 0x40, 0x02, 0xDF, 0x6A};
  proto::Command c{};
  TEST_ASSERT_EQUAL(proto::ACK_OK, proto::decodeCommand(cmd, sizeof(cmd), c));
  TEST_ASSERT_EQUAL_UINT32(1793000000, c.time);
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS, proto::decodeCommand(cmd, sizeof(cmd) - 1, c));
  cmd[1] = proto::CMD_NEW_HIKE;
  TEST_ASSERT_EQUAL(proto::ACK_OK, proto::decodeCommand(cmd, sizeof(cmd), c));
  uint8_t out[8];
  TEST_ASSERT_EQUAL(8, proto::encodeHikeResult(proto::CMD_DELETE_HIKE, proto::ACK_OK, 1793000000, out, sizeof(out)));
  TEST_ASSERT_EQUAL(proto::MSG_HIKE_RESULT, out[1]);
  TEST_ASSERT_EQUAL_MEMORY(cmd + 2, out + 4, 4);
}

void test_pdr_steps_and_missing_reference() {
  pdr::Tracker p;
  TEST_ASSERT_FALSE(p.advance(10, 90)); // без начальной позиции координаты не выдумываем
  TEST_ASSERT_TRUE(p.anchor(60, 30, 0));
  TEST_ASSERT_TRUE(p.advance(10, 0));
  TEST_ASSERT_DOUBLE_WITHIN(0.05, 7.5, spoof::distanceM(60, 30, p.lat(), p.lon()));
  TEST_ASSERT_DOUBLE_WITHIN(0.000001, 30, p.lon());
  double a = p.lat(), b = p.lon();
  TEST_ASSERT_TRUE(p.advance(20, 90));
  TEST_ASSERT_DOUBLE_WITHIN(0.05, 7.5, spoof::distanceM(a, b, p.lat(), p.lon()));
  TEST_ASSERT_FLOAT_WITHIN(0.001, 15, p.distance());
  TEST_ASSERT_TRUE(p.advance(20, 180)); // без шагов нет движения
  TEST_ASSERT_FALSE(p.advance(21, NAN));
  TEST_ASSERT_FALSE(p.valid());
  TEST_ASSERT_TRUE(p.anchor(60, 30, 100));
  TEST_ASSERT_FALSE(p.advance(0, 0)); // сброс счётчика не вызывает телепортацию
}

static std::set<std::string> testFiles;
static std::string failRemove;
static bool fileExists(const char* p) { return testFiles.count(p); }
static bool fileRemove(const char* p) { if (failRemove == p) return false; return testFiles.erase(p); }
void test_delete_archived_hike_files() {
  const uint32_t old = 1793000000, current = old + 60;
  char paths[4][40], other[4][40];
  hikefiles::pathsForHike(old, paths[0], paths[1], paths[2], paths[3]);
  TEST_ASSERT_EQUAL_STRING("/tracks/20261026_073320.csv", paths[0]);
  TEST_ASSERT_EQUAL_STRING("/tracks/20261026_073320.bin", paths[1]);
  TEST_ASSERT_EQUAL_STRING("/marks/20261026_073320.csv", paths[2]);
  TEST_ASSERT_EQUAL_STRING("/marks/20261026_073320.bin", paths[3]);
  hikefiles::pathsForHike(current, other[0], other[1], other[2], other[3]);
  testFiles.clear(); failRemove.clear();
  for (auto& p : paths) testFiles.insert(p);
  for (auto& p : other) testFiles.insert(p);
  testFiles.insert("/blacklist.json"); testFiles.insert("/logs/all.bin");
  TEST_ASSERT_FALSE(hikefiles::removeArchived(current, current, true, fileExists, fileRemove));
  TEST_ASSERT_FALSE(hikefiles::removeArchived(old, current, false, fileExists, fileRemove));
  TEST_ASSERT_EQUAL(10, testFiles.size());
  failRemove = paths[0];
  TEST_ASSERT_FALSE(hikefiles::removeArchived(old, current, true, fileExists, fileRemove));
  TEST_ASSERT_TRUE(fileExists(paths[0]));
  failRemove.clear();
  TEST_ASSERT_TRUE(hikefiles::removeArchived(old, current, true, fileExists, fileRemove));
  TEST_ASSERT_TRUE(hikefiles::removeArchived(old, current, true, fileExists, fileRemove)); // повтор после потери ответа
  TEST_ASSERT_EQUAL(6, testFiles.size());
  for (auto& p : other) TEST_ASSERT_TRUE(fileExists(p));
  TEST_ASSERT_TRUE(fileExists("/blacklist.json"));
}

void test_compass_calibration_and_rejection() {
  compass::Recorder recorder; recorder.reset();
  float g[3] = {0, 0, 1}, m[3]; compass::Calibration cal{};
  TEST_ASSERT_FALSE(recorder.finish(g, cal));
  // Full three-axis rotations in a field with axis scaling and hard-iron offsets.
  const float offsets[] = {100, -200, 300}, radii[] = {9000, 6000, 7500};
  for (int n = 0; n < 120; n++) {
    for (int j = 0; j < 3; j++) m[j] = offsets[j];
    m[n % 3] += (n % 6 < 3 ? 1 : -1) * radii[n % 3];
    recorder.add(m);
  }
  TEST_ASSERT_TRUE(recorder.finish(g, cal));
  TEST_ASSERT_TRUE(compass::valid(cal));
  m[0] = offsets[0] + radii[0]; m[1] = offsets[1]; m[2] = offsets[2];
  TEST_ASSERT_FLOAT_WITHIN(.01, 0, compass::heading(cal, m, g, 0));
  m[0] = offsets[0]; m[1] += radii[1];
  TEST_ASSERT_FLOAT_WITHIN(.01, 90, compass::heading(cal, m, g, 0));
  TEST_ASSERT_FLOAT_WITHIN(.01, 225, compass::heading(cal, m, g, -45, true));
  float tilted[] = {1, 0, 0};
  TEST_ASSERT_TRUE(isnan(compass::heading(cal, m, tilted, 0)));
  m[1] *= 4; TEST_ASSERT_TRUE(isnan(compass::heading(cal, m, g, 0)));
  cal.field = NAN; TEST_ASSERT_FALSE(compass::valid(cal));
  // Rotating on a flat table cannot calibrate the third axis.
  recorder.reset();
  for (int n = 0; n < 120; n++) {
    float a = n * M_PI / 30; float flat[] = {(float)cos(a) * 7500, (float)sin(a) * 7500, 100};
    recorder.add(flat);
  }
  TEST_ASSERT_FALSE(recorder.finish(g, cal));
}
void test_pdr_commands_validation() {
  uint8_t data[] = {proto::VERSION, proto::CMD_PDR_SETTINGS, 75, 0, 0x3E, 0xFE, 1}; // -450 tenths
  proto::Command c;
  TEST_ASSERT_EQUAL(proto::ACK_OK, proto::decodeCommand(data, sizeof(data), c));
  TEST_ASSERT_EQUAL(75, c.a); TEST_ASSERT_EQUAL(-450, c.altDm); TEST_ASSERT_EQUAL(1, c.flag);
  data[2] = 0; TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS, proto::decodeCommand(data, sizeof(data), c));
  data[2] = 75; data[6] = 2; TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS, proto::decodeCommand(data, sizeof(data), c));
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS, proto::decodeCommand(data, sizeof(data) - 1, c));
  uint8_t calibrate[] = {proto::VERSION, proto::CMD_CALIB_COMPASS, 2};
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS, proto::decodeCommand(calibrate, sizeof(calibrate), c));
}

struct SimPose {
  float q[4];
  static SimPose euler(float yaw,float pitch,float roll) {
    float c=cosf(yaw/2),s=sinf(yaw/2),cp=cosf(pitch/2),sp=sinf(pitch/2),cr=cosf(roll/2),sr=sinf(roll/2);
    return {{c*cp*cr+s*sp*sr,c*cp*sr-s*sp*cr,c*sp*cr+s*cp*sr,s*cp*cr-c*sp*sr}};
  }
  void toBody(const float world[3],float out[3]) const {
    float w=q[0],x=q[1],y=q[2],z=q[3];
    out[0]=(1-2*(y*y+z*z))*world[0]+2*(x*y+w*z)*world[1]+2*(x*z-w*y)*world[2];
    out[1]=2*(x*y-w*z)*world[0]+(1-2*(x*x+z*z))*world[1]+2*(y*z+w*x)*world[2];
    out[2]=2*(x*z+w*y)*world[0]+2*(y*z-w*x)*world[1]+(1-2*(x*x+y*y))*world[2];
  }
  void angularVelocity(const SimPose& previous,float out[3]) const {
    // q(previous)^-1 * q(now), body angular velocity over 40 ms.
    float w=previous.q[0],x=-previous.q[1],y=-previous.q[2],z=-previous.q[3];
    float v[]={w*q[1]+x*q[0]+y*q[3]-z*q[2], w*q[2]-x*q[3]+y*q[0]+z*q[1], w*q[3]+x*q[2]-y*q[1]+z*q[0]};
    float a=w*q[0]-x*q[1]-y*q[2]-z*q[3],n=sqrtf(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
    float k=n>1e-8f ? 2*atan2f(n,a)/n/.04f*180/M_PI : 0;
    for(int j=0;j<3;j++) out[j]=v[j]*k;
  }
};
struct WalkSimulation {
  inertial::Navigator nav;
  SimPose previous=SimPose::euler(1.8f,.7f,-.5f);
  uint32_t ms=100,steps=0;
  float walkTime=0;
  double latitude=60,longitude=30;
  WalkSimulation(float roll=-.5f) {
    previous=SimPose::euler(1.8f,.7f,roll);
    float gravity[]={0,0,1},a[3]; previous.toBody(gravity,a);
    for(int j=0;j<90;j++) { nav.feed(a[0],a[1],a[2],.2f,-.1f,.3f,0,ms); ms+=40; }
  }
  void walk(float seconds,float fromCourse,float toCourse,bool gps,bool rotate=false) {
    int samples=lroundf(seconds/.04f);
    for(int i=0;i<samples;i++) {
      float t=(i+1)*.04f,course=fromCourse+(toCourse-fromCourse)*t/seconds,rad=course*M_PI/180;
      float gait=.16f*sinf(2*M_PI*1.8f*walkTime);
      float world[]={gait*cosf(rad),-gait*sinf(rad),1+.08f*cosf(2*M_PI*1.8f*walkTime)};
      SimPose pose=rotate ? SimPose::euler(1.8f+walkTime*.7f,.7f+.5f*sinf(walkTime*.4f),-.5f+.4f*sinf(walkTime*.3f)) : previous;
      float a[3],g[3]; pose.toBody(world,a); pose.angularVelocity(previous,g);
      steps=(uint32_t)(walkTime*1.8f);
      nav.feed(a[0],a[1],a[2],g[0]+.2f,g[1]-.1f,g[2]+.3f,steps,ms);
      latitude+=1.4*.04*cos(rad)/111194.93;
      longitude+=1.4*.04*sin(rad)/(111194.93*cos(latitude*M_PI/180));
      if(gps && i%25==0) nav.offerGps(latitude,longitude,steps,ms);
      previous=pose; walkTime+=.04f; ms+=40;
    }
  }
  float heading() const { return nav.heading(ms-40); }
};
static float headingError(float actual,float expected) { return fabsf(fmodf(actual-expected+540,360)-180); }
void test_inertial_free_orientation_and_turn() {
  WalkSimulation s;
  TEST_ASSERT_TRUE(s.nav.gyroReady());
  s.walk(14,0,0,true,true);
  TEST_ASSERT_TRUE(s.nav.aligned());
  TEST_ASSERT_TRUE(headingError(s.heading(),0)<10);
  s.walk(12,0,0,false,true); // Turning/tilting the device, walking straight.
  char message[100]; snprintf(message,sizeof(message),"heading after rotating device: %.1f",s.heading());
  TEST_ASSERT_TRUE_MESSAGE(isfinite(s.heading()),message);
  TEST_ASSERT_TRUE_MESSAGE(headingError(s.heading(),0)<20,"device rotation must not become a route turn");
  s.walk(5,0,90,false,true); // Person turns with the device held at changing angles.
  s.walk(8,90,90,false,true);
  TEST_ASSERT_TRUE_MESSAGE(headingError(s.heading(),90)<20,"walking turn should change the estimated route");
}
void test_inertial_upside_down_left_turn_and_bias() {
  WalkSimulation s(3.0f);
  s.walk(14,0,0,true);
  TEST_ASSERT_TRUE(s.nav.aligned());
  s.walk(5,0,-90,false); s.walk(8,-90,-90,false);
  TEST_ASSERT_TRUE(headingError(s.heading(),270)<10);
  s.walk(5,-90,-180,false); s.walk(8,-180,-180,false);
  TEST_ASSERT_TRUE(headingError(s.heading(),180)<10);
  TEST_ASSERT_TRUE(s.nav.keepAwake(s.ms));
  TEST_ASSERT_FALSE(s.nav.keepAwake(s.ms+300000));
  // Datasheet permits substantial zero-rate offsets; a stationary unit must auto-calibrate.
  inertial::Navigator factoryBias;
  for (uint32_t t=100;t<5000;t+=40) factoryBias.feed(0,0,-1,15,-10,8,0,t);
  TEST_ASSERT_TRUE(factoryBias.gyroReady());
  float measured[3]; factoryBias.copyBias(measured);
  TEST_ASSERT_FLOAT_WITHIN(.01,15,measured[0]);
  TEST_ASSERT_FLOAT_WITHIN(.01,-10,measured[1]);
  TEST_ASSERT_FLOAT_WITHIN(.01,8,measured[2]);
  TEST_ASSERT_TRUE(isnan(factoryBias.heading(5000))); // Bias alone cannot invent north.
}
void test_inertial_unknown_and_discontinuity() {
  WalkSimulation s;
  s.walk(10,0,0,false,true);
  TEST_ASSERT_FALSE(s.nav.aligned()); TEST_ASSERT_TRUE(isnan(s.heading()));
  s.walk(14,0,0,true,true);
  TEST_ASSERT_TRUE(s.nav.aligned());
  TEST_ASSERT_TRUE(isnan(s.nav.heading(s.ms+11000)));
  float bias[3]; TEST_ASSERT_TRUE(s.nav.copyBias(bias));
  s.nav.feed(0,0,1,0,0,0,s.steps,s.ms+1000); // Missing rotation samples breaks orientation.
  TEST_ASSERT_FALSE(s.nav.aligned());
  TEST_ASSERT_TRUE(s.nav.gyroReady()); // Sensor bias survives an orientation reset.
  s.nav.restoreBias(bias);
  TEST_ASSERT_FALSE(s.nav.offerGps(NAN,30,s.steps,s.ms+1000));
  TEST_ASSERT_FALSE(s.nav.aligned());
  s.nav.feed(0,0,1,0,0,500,s.steps,s.ms+1040); // Saturated gyro cannot produce a valid heading.
  TEST_ASSERT_FALSE(s.nav.aligned());
}


void test_economy_distance_without_gps_or_double_chord() {
  walking::Distance d; d.reset();
  d.update(100,0,true,true,60,30,1,.75);
  d.update(110,130,true,true,60.0009,30,1,.75);
  double first=d.meters(); TEST_ASSERT_DOUBLE_WITHIN(1,100,first);
  d.update(120,230,true,false,0,0,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,first+75,d.meters());
  d.update(125,250,true,true,60.0018,30,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,first+90,d.meters()); // no GPS chord after loss
  double before=d.meters();
  d.update(126,1000,false,false,0,0,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,before,d.meters());
  d.update(127,1000,true,true,61,31,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,before,d.meters()); // no car transfer on resume
  d.update(128,5,true,false,0,0,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,before,d.meters()); // counter reset is not 2^32 steps
  d.update(129,15,true,false,0,0,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,before+7.5,d.meters());
  walking::Distance noPosition; noPosition.reset();
  noPosition.update(100,40,true,false,0,0,1,.75);
  TEST_ASSERT_DOUBLE_WITHIN(.01,30,noPosition.meters());
}
void test_final_commands_and_start_packet() {
  uint8_t temp[]={proto::VERSION,proto::CMD_TEMP_OFFSET,0xA2,0xFE};
  proto::Command c{};
  TEST_ASSERT_EQUAL(proto::ACK_OK,proto::decodeCommand(temp,sizeof(temp),c));
  TEST_ASSERT_EQUAL(-350,c.altDm);
  temp[2]=0xFF;temp[3]=0x7F;
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS,proto::decodeCommand(temp,sizeof(temp),c));
  uint8_t start[14]={proto::VERSION,proto::CMD_SET_START};
  uint32_t id=1793000000; int32_t lat=601000000,lon=303000000;
  memcpy(start+2,&id,4);memcpy(start+6,&lat,4);memcpy(start+10,&lon,4);
  TEST_ASSERT_EQUAL(proto::ACK_OK,proto::decodeCommand(start,sizeof(start),c));
  TEST_ASSERT_EQUAL(lat,c.lat7); TEST_ASSERT_EQUAL(lon,c.lon7);
  lat=910000000;memcpy(start+6,&lat,4);
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS,proto::decodeCommand(start,sizeof(start),c));
  proto::Point point{}; point.startAnchor=true; point.spoof=true; point.steps=123;
  uint8_t bytes[proto::POINT_SIZE];proto::encodePoint(point,bytes,sizeof(bytes));
  TEST_ASSERT_EQUAL(0x0C,bytes[14]);
  proto::Point decoded{}; TEST_ASSERT_TRUE(proto::decodePoint(bytes,sizeof(bytes),decoded));
  TEST_ASSERT_TRUE(decoded.startAnchor); TEST_ASSERT_TRUE(decoded.spoof); TEST_ASSERT_EQUAL(123,decoded.steps);
  proto::Info info{}; info.hasSettings=true;info.tempOffset100=-350;info.strideCm=82;
  uint8_t packet[128];size_t n=proto::encodeInfo(info,packet,sizeof(packet));
  TEST_ASSERT_EQUAL(33,n); TEST_ASSERT_EQUAL(0xA2,packet[n-4]);TEST_ASSERT_EQUAL(82,packet[n-2]);
}
void test_blacklist_defaults_and_observed_false_position() {
  proto::BlEntry parsed[proto::MAX_BLACKLIST];uint8_t count=0;
  TEST_ASSERT_TRUE(blacklistdefaults::parse("[\n  [60.0346000,30.5693000,5000],\n [60.1161000,31.3864000,2250]\n]",parsed,count));
  TEST_ASSERT_EQUAL(2,count); TEST_ASSERT_EQUAL(601161000,parsed[1].lat7);TEST_ASSERT_EQUAL(2250,parsed[1].radiusM);
  TEST_ASSERT_FALSE(blacklistdefaults::parse("[[NaN,30,5000]]",parsed,count));
  TEST_ASSERT_FALSE(blacklistdefaults::parse("[[60,30,0]]",parsed,count));
  proto::BlEntry known={601161000,313864000,1000};
  TEST_ASSERT_TRUE(blacklistdefaults::migrate(known)); TEST_ASSERT_EQUAL(5000,known.radiusM);
  known.radiusM=2250; TEST_ASSERT_FALSE(blacklistdefaults::migrate(known));
  proto::BlEntry custom={601000000,300000000,1000};TEST_ASSERT_FALSE(blacklistdefaults::migrate(custom));
  double lat[]={60.1161},lon[]={31.3864};uint16_t radius[]={5000};
  spoof::Detector detector;detector.setBlacklist(lat,lon,radius,1);
  TEST_ASSERT_TRUE(detector.blacklisted(60.11741,31.36770));
  spoof::Input input{};input.now=100;input.lat=60.11741;input.lon=31.36770;
  for(unsigned t=100;t<140;t++) {input.now=t;auto r=detector.check(input);TEST_ASSERT_TRUE(r.spoof);TEST_ASSERT_BITS_HIGH(spoof::R_BLACKLIST,r.mask);}
  input.lat=NAN; TEST_ASSERT_TRUE(detector.check(input).spoof);
}
void test_recovery_requires_coherent_positions() {
  double la[]={60},lo[]={30};uint16_t radius[]={1000};
  spoof::Detector detector;detector.setBlacklist(la,lo,radius,1);
  spoof::Input input{};input.now=100;input.lat=60;input.lon=30;
  TEST_ASSERT_TRUE(detector.check(input).spoof);
  input.lon=31;
  for (unsigned t=101;t<131;t++) {
    input.now=t;input.lat=60+(t-101)*.001; // no trusted reference, fake 100 m/s drift
    TEST_ASSERT_TRUE(detector.check(input).spoof);
  }
  input.lat=61;
  bool clean=false;
  for(unsigned t=131;t<146;t++) {input.now=t;clean=!detector.check(input).spoof;}
  TEST_ASSERT_TRUE(clean);
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
  RUN_TEST(test_spoof_jump_after_pause);
  RUN_TEST(test_spoof_phone_and_time);
  RUN_TEST(test_spoof_weak_signs_need_two);
  RUN_TEST(test_spoof_steps_but_standing);
  RUN_TEST(test_spoof_recovery);
  RUN_TEST(test_power_moving_and_camp);
  RUN_TEST(test_power_battery_hysteresis);
  RUN_TEST(test_gps_pulse);
  RUN_TEST(test_alerts);
  RUN_TEST(test_clock_jump_back);
  RUN_TEST(test_steps_walking);
  RUN_TEST(test_steps_noise_and_bumps);
  RUN_TEST(test_steps_gentle_and_resume);
  RUN_TEST(test_sunset);
  RUN_TEST(test_hike_commands);
  RUN_TEST(test_pdr_steps_and_missing_reference);
  RUN_TEST(test_compass_calibration_and_rejection);
  RUN_TEST(test_inertial_free_orientation_and_turn);
  RUN_TEST(test_inertial_unknown_and_discontinuity);
  RUN_TEST(test_inertial_upside_down_left_turn_and_bias);
  RUN_TEST(test_pdr_commands_validation);
  RUN_TEST(test_economy_distance_without_gps_or_double_chord);
  RUN_TEST(test_final_commands_and_start_packet);
  RUN_TEST(test_blacklist_defaults_and_observed_false_position);
  RUN_TEST(test_recovery_requires_coherent_positions);
  RUN_TEST(test_delete_archived_hike_files);
  RUN_TEST(test_log_vector);
  RUN_TEST(test_log_ring);
  RUN_TEST(test_log_format_and_days);
  return UNITY_END();
}
