#include "storage.h"

#include <Arduino.h>
#include <SD.h>
#include <SPI.h>
#include <math.h>
#include <string.h>

#include "pins.h"
#include "hike_files.h"
#include "blacklist_defaults.h"
using hikefiles::pathsForHike;

namespace storage {

namespace {

constexpr size_t MARK_REC = 80;           // запись метки в .bin: [длина][пакет MARK ≤ 78]
constexpr uint32_t RAM_POINTS = 8000;     // ~400 КБ PSRAM: больше суток при записи раз в 10 с
constexpr uint32_t RAM_MARKS = 200;

bool g_sd = false;
uint32_t g_hike = 0;
char g_trackCsv[40], g_trackBin[40], g_markCsv[40], g_markBin[40];

// PSRAM-копия текущего похода
uint8_t* g_ramPoints = nullptr;
uint32_t g_ramPointCount = 0;   // всего записано (кольцо по модулю RAM_POINTS)
uint8_t* g_ramMarks = nullptr;
uint32_t g_ramMarkCount = 0;
uint16_t g_markId = 0;

uint32_t g_sdPointCount = 0, g_sdMarkCount = 0;


void fileNames(uint32_t t) { pathsForHike(t, g_trackCsv, g_trackBin, g_markCsv, g_markBin); }

void isoTime(uint32_t t, char* out, size_t cap) {
  long z = t / 86400 + 719468, era = z / 146097;
  unsigned doe = z - era * 146097, yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100), mp = (5 * doy + 2) / 153;
  unsigned d = doy - (153 * mp + 2) / 5 + 1, m = mp < 10 ? mp + 3 : mp - 9;
  int y = yoe + era * 400 + (m <= 2);
  uint32_t s = t % 86400;
  snprintf(out, cap, "%04d-%02u-%02uT%02lu:%02lu:%02luZ", y, m, d, s / 3600, (s / 60) % 60, s % 60);
}

void ensureHeader(const char* path, const char* header) {
  if (SD.exists(path)) return;
  File f = SD.open(path, FILE_WRITE);
  if (f) {
    f.println(header);
    f.close();
  }
}

uint32_t fileRecords(const char* path, size_t rec) {
  if (!SD.exists(path)) return 0;
  File f = SD.open(path, FILE_READ);
  if (!f) return 0;
  uint32_t n = f.size() / rec;
  f.close();
  return n;
}

// Число из пакета → текст CSV; «нет данных» → пусто
void fmtScaled(char* out, size_t cap, int32_t v, float scale, int digits, bool na) {
  if (na) { out[0] = 0; return; }
  snprintf(out, cap, "%.*f", digits, v / scale);
}

}  // namespace

bool begin() {
  if (!g_ramPoints) g_ramPoints = static_cast<uint8_t*>(ps_malloc(RAM_POINTS * proto::POINT_SIZE));
  if (!g_ramMarks) g_ramMarks = static_cast<uint8_t*>(ps_malloc(RAM_MARKS * MARK_REC));
  SPI.begin(PIN_SD_SCK, PIN_SD_MISO, PIN_SD_MOSI, PIN_SD_CS);
  g_sd = SD.begin(PIN_SD_CS, SPI, 4000000) && SD.cardType() != CARD_NONE;
  if (g_sd) {
    if (!SD.exists("/tracks")) SD.mkdir("/tracks");
    if (!SD.exists("/marks")) SD.mkdir("/marks");
  }
  return g_sd;
}

bool sdOk() { return g_sd; }
uint32_t totalMb() { return g_sd ? SD.totalBytes() / (1024 * 1024) : 0; }
uint32_t freeMb() { return g_sd ? (SD.totalBytes() - SD.usedBytes()) / (1024 * 1024) : 0; }

void openHike(uint32_t hikeStart) {
  g_hike = hikeStart;
  fileNames(hikeStart);
  g_ramPointCount = 0;
  g_ramMarkCount = 0;
  g_sdPointCount = g_sdMarkCount = 0;
  g_markId = 0;
  if (g_sd) {
    ensureHeader(g_trackCsv,
                 "ts_utc,lat,lon,alt_gps,sats,hdop,speed,course,spoof,spoof_reason,temp_raw,temp_corr,hum,press,"
                 "alt_baro,batt_v,batt_ma,steps,heading,src,mark_id");
    ensureHeader(g_markCsv, "ts_utc,id,type,text,lat,lon");
    // Продолжаем поход после сна/перезагрузки: сколько уже записано
    g_sdPointCount = fileRecords(g_trackBin, proto::POINT_SIZE);
    g_sdMarkCount = fileRecords(g_markBin, MARK_REC);
    proto::Mark last;
    if (g_sdMarkCount && readMark(g_sdMarkCount - 1, last)) g_markId = last.id;
  }
}

uint32_t hikeStart() { return g_hike; }

bool deleteHike(uint32_t hike) {
  return hikefiles::removeArchived(hike, g_hike, g_sd,
      [](const char* path) { return SD.exists(path); },
      [](const char* path) { return SD.remove(path); });
}

bool appendPoint(const proto::Point& p, const CsvExtra& extra) {
  uint8_t pkt[proto::POINT_SIZE];
  proto::encodePoint(p, pkt, sizeof(pkt));
  if (g_ramPoints) memcpy(g_ramPoints + (g_ramPointCount % RAM_POINTS) * proto::POINT_SIZE, pkt, sizeof(pkt));
  g_ramPointCount++;
  if (!g_sd) return false;

  File b = SD.open(g_trackBin, FILE_APPEND);
  if (!b) { g_sd = false; return false; }
  bool ok = b.write(pkt, sizeof(pkt)) == sizeof(pkt);
  b.close();
  if (ok) g_sdPointCount++;

  char ts[24], altG[12], altB[12], tr[12], tc[12], hd[8];
  isoTime(p.time, ts, sizeof(ts));
  fmtScaled(altG, sizeof(altG), p.altGps10, 10, 1, p.altGps10 == proto::NA16);
  fmtScaled(altB, sizeof(altB), p.altBaro10, 10, 1, p.altBaro10 == proto::NA16);
  fmtScaled(tr, sizeof(tr), p.tempRaw100, 100, 2, p.tempRaw100 == proto::NA16);
  fmtScaled(tc, sizeof(tc), p.tempCorr100, 100, 2, p.tempCorr100 == proto::NA16);
  fmtScaled(hd, sizeof(hd), p.hdop10, 10, 1, p.hdop10 == proto::HDOP_NA);
  const char* src = p.startAnchor ? "start" : p.src == 1 ? "gps" : (p.src == 2 ? "pdr" : (p.src == proto::SRC_PAUSE ? "pause" : "none"));
  char line[256];
  int n = snprintf(line, sizeof(line), "%s,%.7f,%.7f,%s,%u,%s,%.1f,%.1f,%d,%u,%s,%s,%.2f,%.2f,%s,%.3f,%.1f,%lu,%.1f,%s,",
                   ts, p.lat7 / 1e7, p.lon7 / 1e7, altG, p.sats, hd, p.speed10 / 10.0, extra.courseDeg, p.spoof ? 1 : 0,
                   p.spoofMask, tr, tc, p.hum100 / 100.0, p.pressPa / 100.0, altB, p.battMv / 1000.0, extra.battMa,
                   (unsigned long)p.steps, p.heading10 / 10.0, src);
  File c = SD.open(g_trackCsv, FILE_APPEND);
  if (!c) { g_sd = false; return false; }
  ok = ok && c.println(line) == (size_t)n + 2;
  c.close();
  return ok;
}

uint32_t pointCount() { return g_sd ? g_sdPointCount : g_ramPointCount; }

bool readPoint(uint32_t i, proto::Point& out) {
  uint8_t pkt[proto::POINT_SIZE];
  if (g_sd) {
    File b = SD.open(g_trackBin, FILE_READ);
    if (!b) return false;
    bool ok = b.seek(i * proto::POINT_SIZE) && b.read(pkt, sizeof(pkt)) == sizeof(pkt);
    b.close();
    return ok && proto::decodePoint(pkt, sizeof(pkt), out);
  }
  // PSRAM: доступны только последние RAM_POINTS записей
  if (!g_ramPoints || i >= g_ramPointCount || g_ramPointCount - i > RAM_POINTS) return false;
  return proto::decodePoint(g_ramPoints + (i % RAM_POINTS) * proto::POINT_SIZE, proto::POINT_SIZE, out);
}

uint32_t firstPointSince(uint32_t utc) {
  uint32_t n = pointCount();
  uint32_t lo = (!g_sd && g_ramPointCount > RAM_POINTS) ? g_ramPointCount - RAM_POINTS : 0, hi = n;
  proto::Point p;
  // Записи идут по времени — двоичный поиск
  while (lo < hi) {
    uint32_t mid = (lo + hi) / 2;
    if (!readPoint(mid, p)) return n;
    if (p.time < utc) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

uint32_t lastPointTime() {
  proto::Point p;
  uint32_t n = pointCount();
  return n && readPoint(n - 1, p) ? p.time : 0;
}

void forEachPoint(void (*visit)(const proto::Point&)) {
  if (g_sd) {
    File f=SD.open(g_trackBin,FILE_READ); if (!f) return;
    uint8_t bytes[proto::POINT_SIZE]; proto::Point p{};
    while (f.read(bytes,sizeof(bytes))==sizeof(bytes)) { if (proto::decodePoint(bytes,sizeof(bytes),p)) visit(p); }
    f.close();
  } else {
    uint32_t first=g_ramPointCount>RAM_POINTS?g_ramPointCount-RAM_POINTS:0;
    for (uint32_t i=first;i<g_ramPointCount;i++) { proto::Point p{}; if (readPoint(i,p)) visit(p); }
  }
}

bool appendMark(const proto::Mark& m) {
  uint8_t rec[MARK_REC] = {0};
  size_t len = proto::encodeMark(m, rec + 1, sizeof(rec) - 1);
  rec[0] = static_cast<uint8_t>(len);
  if (g_ramMarks) memcpy(g_ramMarks + (g_ramMarkCount % RAM_MARKS) * MARK_REC, rec, MARK_REC);
  g_ramMarkCount++;
  if (m.id > g_markId) g_markId = m.id;
  if (!g_sd) return false;

  File b = SD.open(g_markBin, FILE_APPEND);
  if (!b) { g_sd = false; return false; }
  bool ok = b.write(rec, MARK_REC) == MARK_REC;
  b.close();
  if (ok) g_sdMarkCount++;

  char ts[24], line[160];
  isoTime(m.time, ts, sizeof(ts));
  // Текст в кавычках, кавычки внутри удваиваем (CSV)
  char text[2 * proto::MAX_TEXT + 1];
  size_t j = 0;
  for (size_t i = 0; i < m.textLen && j < sizeof(text) - 2; i++) {
    if (m.text[i] == '"') text[j++] = '"';
    text[j++] = m.text[i];
  }
  text[j] = 0;
  snprintf(line, sizeof(line), "%s,%u,%u,\"%s\",%.7f,%.7f", ts, m.id, m.type, text, m.lat7 / 1e7, m.lon7 / 1e7);
  File c = SD.open(g_markCsv, FILE_APPEND);
  if (!c) { g_sd = false; return false; }
  c.println(line);
  c.close();
  return ok;
}

uint32_t markCount() { return g_sd ? g_sdMarkCount : g_ramMarkCount; }

bool readMark(uint32_t i, proto::Mark& out) {
  uint8_t rec[MARK_REC];
  if (g_sd) {
    File b = SD.open(g_markBin, FILE_READ);
    if (!b) return false;
    bool ok = b.seek(i * MARK_REC) && b.read(rec, MARK_REC) == MARK_REC;
    b.close();
    if (!ok) return false;
  } else {
    if (!g_ramMarks || i >= g_ramMarkCount || g_ramMarkCount - i > RAM_MARKS) return false;
    memcpy(rec, g_ramMarks + (i % RAM_MARKS) * MARK_REC, MARK_REC);
  }
  return rec[0] <= MARK_REC - 1 && proto::decodeMark(rec + 1, rec[0], out);
}

uint32_t firstMarkSince(uint32_t utc) {
  uint32_t n = markCount();
  proto::Mark m;
  for (uint32_t i = 0; i < n; i++) {
    if (readMark(i, m) && m.time >= utc) return i;
  }
  return n;
}

uint16_t nextMarkId() { return ++g_markId; }

namespace {
// Сдвиг времени в одной записи точки (pkt — POINT_SIZE байт)
bool shiftPoint(uint8_t* pkt, int32_t d) {
  proto::Point p;
  if (!proto::decodePoint(pkt, proto::POINT_SIZE, p)) return false;
  p.time += (uint32_t)d;
  return proto::encodePoint(p, pkt, proto::POINT_SIZE) == proto::POINT_SIZE;
}

// Сдвиг времени в одной записи метки (rec — MARK_REC байт: [длина][пакет])
bool shiftMark(uint8_t* rec, int32_t d) {
  proto::Mark m;
  if (rec[0] > MARK_REC - 1 || !proto::decodeMark(rec + 1, rec[0], m)) return false;
  m.time += (uint32_t)d;
  rec[0] = static_cast<uint8_t>(proto::encodeMark(m, rec + 1, MARK_REC - 1));
  return true;
}

// Переписать записи [from, count) файла на SD через fix
void shiftFile(const char* path, size_t recSize, uint32_t from, uint32_t count, bool (*fix)(uint8_t*, int32_t), int32_t d) {
  if (from >= count) return;
  File f = SD.open(path, "r+");
  if (!f) return;
  uint8_t buf[MARK_REC > proto::POINT_SIZE ? MARK_REC : proto::POINT_SIZE];
  for (uint32_t i = from; i < count; i++) {
    if (!f.seek(i * recSize) || f.read(buf, recSize) != recSize) break;
    if (!fix(buf, d)) continue;
    f.seek(i * recSize);
    f.write(buf, recSize);
  }
  f.close();
}
}  // namespace

void shiftTimes(uint32_t fromPoint, uint32_t fromMark, int32_t d) {
  if (g_ramPoints) {
    uint32_t lo = g_ramPointCount > RAM_POINTS ? g_ramPointCount - RAM_POINTS : 0;
    for (uint32_t i = fromPoint > lo ? fromPoint : lo; i < g_ramPointCount; i++)
      shiftPoint(g_ramPoints + (i % RAM_POINTS) * proto::POINT_SIZE, d);
  }
  if (g_ramMarks) {
    uint32_t lo = g_ramMarkCount > RAM_MARKS ? g_ramMarkCount - RAM_MARKS : 0;
    for (uint32_t i = fromMark > lo ? fromMark : lo; i < g_ramMarkCount; i++)
      shiftMark(g_ramMarks + (i % RAM_MARKS) * MARK_REC, d);
  }
  if (!g_sd) return;
  shiftFile(g_trackBin, proto::POINT_SIZE, fromPoint, g_sdPointCount, shiftPoint, d);
  shiftFile(g_markBin, MARK_REC, fromMark, g_sdMarkCount, shiftMark, d);
}

void renameHike(uint32_t newStart) {
  char oldName[4][40];
  memcpy(oldName[0], g_trackCsv, 40);
  memcpy(oldName[1], g_trackBin, 40);
  memcpy(oldName[2], g_markCsv, 40);
  memcpy(oldName[3], g_markBin, 40);
  g_hike = newStart;
  fileNames(newStart);
  if (!g_sd) return;
  const char* newName[4] = {g_trackCsv, g_trackBin, g_markCsv, g_markBin};
  for (int i = 0; i < 4; i++) {
    if (SD.exists(oldName[i])) SD.rename(oldName[i], newName[i]);
  }
}

bool loadBlacklist(proto::BlEntry* out, uint8_t& n) {
  n = 0;
  if (!g_sd || !SD.exists("/blacklist.json")) return false;
  File f = SD.open("/blacklist.json", FILE_READ);
  if (!f) return false;
  // Простой формат: [[lat,lon,radius],...] — разбираем числа подряд
  String s = f.readString();
  f.close();
  return blacklistdefaults::parse(s.c_str(),out,n);
}

bool saveBlacklist(const proto::BlEntry* in, uint8_t n) {
  if (!g_sd) return false;
  File f = SD.open("/blacklist.json", FILE_WRITE);
  if (!f) return false;
  f.print("[");
  for (uint8_t i = 0; i < n; i++) {
    f.printf("%s\n  [%.7f,%.7f,%u]", i ? "," : "", in[i].lat7 / 1e7, in[i].lon7 / 1e7, in[i].radiusM);
  }
  f.println("\n]");
  f.close();
  return true;
}

}  // namespace storage
