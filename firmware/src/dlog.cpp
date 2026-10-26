#include "dlog.h"

#include <Arduino.h>
#include <SD.h>
#include <esp_attr.h>
#include <stdarg.h>
#include <string.h>

#include "clock.h"
#include "storage.h"

namespace dlog {

namespace {

constexpr size_t REC = 100;                       // запись all.bin: [длина][пакет LOG ≤ 97], добито нулями
constexpr uint32_t ROTATE_BYTES = 2u * 1024 * 1024;  // ~20 тыс. строк, потом all.bin → old.bin
constexpr long KEEP_DAYS = 30;
constexpr uint32_t FIRST_SYNC = 500;              // первая выгрузка в приложение — последние строки
constexpr uint32_t UP_MAGIC = 0x55505431;
const char* LOG_BIN = "/logs/all.bin";
const char* LOG_OLD = "/logs/old.bin";

// Переживают сон и перезагрузку после сбоя (не выключение питания) — след перед падением не теряется
RTC_NOINIT_ATTR logbuf::Ring g_ring;
RTC_NOINIT_ATTR uint32_t g_upBase;
RTC_NOINIT_ATTR uint32_t g_upMagic;
// Переживают сон
RTC_DATA_ATTR Counters g_cnt;
RTC_DATA_ATTR long g_cleanedDay = 0;

portMUX_TYPE g_mux = portMUX_INITIALIZER_UNLOCKED;
volatile bool g_debug = true;
volatile bool g_errPending = false;
bool g_attached = false;
uint32_t g_nOld = 0, g_nAll = 0;

uint32_t records(const char* path) {
  if (!SD.exists(path)) return 0;
  File f = SD.open(path, FILE_READ);
  if (!f) return 0;
  uint32_t n = f.size() / REC;
  f.close();
  return n;
}

bool readRec(const char* path, uint32_t i, uint8_t* rec) {
  File f = SD.open(path, FILE_READ);
  if (!f) return false;
  bool ok = f.seek((uint64_t)i * REC) && f.read(rec, REC) == (int)REC;
  f.close();
  return ok && rec[0] > 0 && rec[0] < REC;
}

// Номер строки по глобальному индексу (old.bin, затем all.bin); NO_SEQ — не прочитали
uint32_t seqAt(uint32_t idx) {
  uint8_t rec[REC];
  bool ok = idx < g_nOld ? readRec(LOG_OLD, idx, rec) : readRec(LOG_BIN, idx - g_nOld, rec);
  proto::LogLine l;
  if (!ok || !proto::decodeLog(rec + 1, rec[0], l)) return logbuf::NO_SEQ;
  return l.seq;
}

// Удалить дневные журналы старше 30 дней — раз в сутки, только при проверенном времени
void cleanup() {
  if (!clockx::trusted()) return;
  long today = (long)(clockx::now() / 86400);
  if (today == g_cleanedDay) return;
  g_cleanedDay = today;
  File dir = SD.open("/logs");
  if (!dir) return;
  char victims[8][24];
  int nv = 0;
  for (File f = dir.openNextFile(); f && nv < 8; f = dir.openNextFile()) {
    const char* name = f.name();
    const char* base = strrchr(name, '/');
    base = base ? base + 1 : name;
    long day = strlen(base) == 12 && !strcmp(base + 8, ".log") ? logbuf::dayNumber(base) : -1;
    if (day >= 0 && today - day > KEEP_DAYS) snprintf(victims[nv++], sizeof(victims[0]), "/logs/%s", base);
    f.close();
  }
  dir.close();
  for (int i = 0; i < nv; i++) SD.remove(victims[i]);
  if (nv) log(logbuf::INFO, logbuf::M_SD, "удалено старых журналов: %d", nv);
}

}  // namespace

void begin() {
  if (!g_ring.valid()) g_ring.init();
  if (g_upMagic != UP_MAGIC || esp_reset_reason() == ESP_RST_POWERON) {
    g_upMagic = UP_MAGIC;
    g_upBase = 0;
  }
  g_cnt.dropped = g_ring.dropped;
}

void attachStorage() {
  if (g_attached || !storage::sdOk()) return;
  if (!SD.exists("/logs")) SD.mkdir("/logs");
  g_nOld = records(LOG_OLD);
  g_nAll = records(LOG_BIN);
  if (g_ring.nextSeq == logbuf::NO_SEQ) {
    // После включения номер берём с SD: продолжаем нумерацию, приложение не получит дублей
    uint32_t last = 0;
    uint32_t total = g_nOld + g_nAll;
    if (total) {
      uint32_t s = seqAt(total - 1);
      if (s != logbuf::NO_SEQ) last = s;
    }
    portENTER_CRITICAL(&g_mux);
    g_ring.assignSeq(last + 1);
    portEXIT_CRITICAL(&g_mux);
  }
  g_attached = true;
  cleanup();
}

void setDebug(bool on) { g_debug = on; }
bool debug() { return g_debug; }

uint32_t uptimeS() { return g_upBase + millis() / 1000; }

void beforeSleep(uint32_t sleepS) {
  g_upBase += millis() / 1000 + sleepS;
}

void log(uint8_t level, uint8_t module, const char* fmt, ...) {
  if ((level & logbuf::LEVEL_MASK) == logbuf::DBG && !g_debug) return;
  char text[160];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(text, sizeof(text), fmt, ap);
  va_end(ap);
  uint32_t t = clockx::now();
  if (t < 1600000000u) t = 0;
  uint8_t lv = level | (clockx::trusted() ? logbuf::TIME_OK : 0);
  uint32_t up = uptimeS();
  portENTER_CRITICAL(&g_mux);
  g_ring.push(t, up, lv, module, text, strlen(text));
  g_cnt.dropped = g_ring.dropped;
  portEXIT_CRITICAL(&g_mux);
  if ((level & logbuf::LEVEL_MASK) == logbuf::ERR) g_errPending = true;
  // Только если монитор порта открыт: без хоста запись в USB ждёт таймаут и тормозит цикл на батарее
  if (Serial) Serial.printf("[%s %s] %s\n", logbuf::levelChar(level), logbuf::moduleName(module), text);
}

uint32_t pending() { return g_ring.pending(); }

bool urgent() { return g_errPending || g_ring.pending() >= logbuf::RING_N / 2; }

void flush() {
  if (!g_attached || !storage::sdOk()) return;
  portENTER_CRITICAL(&g_mux);
  uint32_t start = g_ring.flushed, n = g_ring.pending();
  portEXIT_CRITICAL(&g_mux);
  if (!n) return;
  File bin = SD.open(LOG_BIN, FILE_APPEND);
  if (!bin) {
    g_cnt.sdFail++;
    return;
  }
  File txt;
  char curDay[12] = "";
  uint32_t done = 0;
  for (uint32_t i = 0; i < n; i++) {
    proto::LogLine l;
    bool lost;
    portENTER_CRITICAL(&g_mux);
    lost = g_ring.flushed > start + i;  // пока писали, буфер переполнился и строку вытеснило
    l = g_ring.recs[(start + i) % logbuf::RING_N];
    portEXIT_CRITICAL(&g_mux);
    if (!lost) {
      if (l.seq == logbuf::NO_SEQ) break;
      uint8_t rec[REC] = {0};
      size_t len = proto::encodeLog(l, rec + 1, REC - 1);
      rec[0] = (uint8_t)len;
      if (!len || bin.write(rec, REC) != REC) break;
      g_nAll++;
      char day[12];
      if (l.time) logbuf::dayName(l.time, day, sizeof(day));
      else strcpy(day, "nodate");
      if (strcmp(day, curDay) != 0) {
        if (txt) txt.close();
        char path[32];
        snprintf(path, sizeof(path), "/logs/%s.log", day);
        txt = SD.open(path, FILE_APPEND);
        strcpy(curDay, day);
      }
      if (txt) {
        char line[200];
        size_t k = logbuf::formatLine(l, line, sizeof(line) - 1);
        line[k++] = '\n';
        txt.write((const uint8_t*)line, k);
      }
    }
    done++;
  }
  bin.close();
  if (txt) txt.close();
  portENTER_CRITICAL(&g_mux);
  if (start + done > g_ring.flushed) g_ring.markFlushed(start + done - g_ring.flushed);
  portEXIT_CRITICAL(&g_mux);
  if (done < n) g_cnt.sdFail++;
  else g_errPending = false;
  if (g_nAll * REC > ROTATE_BYTES) {
    SD.remove(LOG_OLD);
    SD.rename(LOG_BIN, LOG_OLD);
    g_nOld = g_nAll;
    g_nAll = 0;
  }
}

uint32_t syncEnd() { return g_attached ? g_nOld + g_nAll : 0; }

uint32_t syncStart(uint32_t since) {
  uint32_t total = syncEnd();
  if (since == 0) return total > FIRST_SYNC ? total - FIRST_SYNC : 0;
  // Номера растут — ищем делением пополам первую строку новее since
  uint32_t lo = 0, hi = total;
  while (lo < hi) {
    uint32_t mid = (lo + hi) / 2;
    uint32_t s = seqAt(mid);
    if (s != logbuf::NO_SEQ && s <= since) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

size_t syncRead(uint32_t idx, uint8_t* out, size_t cap) {
  uint8_t rec[REC];
  bool ok = idx < g_nOld ? readRec(LOG_OLD, idx, rec) : readRec(LOG_BIN, idx - g_nOld, rec);
  if (!ok || rec[0] > cap) return 0;
  memcpy(out, rec + 1, rec[0]);
  return rec[0];
}

Counters& counters() { return g_cnt; }

}  // namespace dlog
