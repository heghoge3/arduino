#include "logbuf.h"

#include <stdio.h>
#include <string.h>

namespace logbuf {

namespace {

// Гражданская дата из дней с 1970-01-01 (алгоритм Хиннанта)
void civil(long z, int& y, unsigned& m, unsigned& d) {
  z += 719468;
  long era = (z >= 0 ? z : z - 146096) / 146097;
  unsigned doe = (unsigned)(z - era * 146097);
  unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  unsigned mp = (5 * doy + 2) / 153;
  d = doy - (153 * mp + 2) / 5 + 1;
  m = mp < 10 ? mp + 3 : mp - 9;
  y = (int)(yoe + era * 400 + (m <= 2));
}

long daysFromCivil(int y, unsigned m, unsigned d) {
  y -= m <= 2;
  long era = (y >= 0 ? y : y - 399) / 400;
  unsigned yoe = (unsigned)(y - era * 400);
  unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + (long)doe - 719468;
}

}  // namespace

const char* levelChar(uint8_t level) {
  static const char* c[] = {"E", "W", "I", "D"};
  return c[level & LEVEL_MASK];
}

const char* moduleName(uint8_t module) {
  static const char* n[] = {"SYS", "PWR", "GPS", "SPOOF", "SENS", "SD", "RTC", "BLE", "UI", "CMD"};
  return module < M_COUNT ? n[module] : "?";
}

void Ring::init() {
  memset(this, 0, sizeof(*this));
  magic = RING_MAGIC;
  nextSeq = NO_SEQ;
  seal();
}

bool Ring::valid() const {
  return magic == RING_MAGIC && check == (magic ^ nextSeq ^ head ^ (flushed * 31u) ^ dropped) && head >= flushed &&
         head - flushed <= RING_N;
}

void Ring::seal() { check = magic ^ nextSeq ^ head ^ (flushed * 31u) ^ dropped; }

void Ring::push(uint32_t time, uint32_t uptime, uint8_t level, uint8_t module, const char* text, size_t len) {
  if (pending() >= RING_N) {
    // Буфер полон, а на SD не ушло: теряем самую старую строку, но считаем потери
    flushed++;
    dropped++;
  }
  proto::LogLine& l = recs[head % RING_N];
  l.seq = nextSeq == NO_SEQ ? NO_SEQ : nextSeq++;
  l.time = time;
  l.uptime = uptime;
  l.level = level;
  l.module = module;
  size_t n = proto::utf8Limit(text, len, proto::MAX_LOG_TEXT);
  memcpy(l.text, text, n);
  l.text[n] = 0;
  l.textLen = (uint8_t)n;
  head++;
  seal();
}

void Ring::markFlushed(uint32_t n) {
  if (n > pending()) n = pending();
  flushed += n;
  seal();
}

void Ring::assignSeq(uint32_t next) {
  nextSeq = next;
  for (uint32_t i = 0; i < pending(); i++) {
    proto::LogLine& l = pendingAt(i);
    if (l.seq == NO_SEQ) l.seq = nextSeq++;
  }
  seal();
}

size_t formatLine(const proto::LogLine& l, char* out, size_t cap) {
  char ts[24];
  if (l.time >= 1000000000u) {
    int y;
    unsigned m, d;
    civil((long)(l.time / 86400), y, m, d);
    uint32_t s = l.time % 86400;
    snprintf(ts, sizeof(ts), "%04d-%02u-%02u %02u:%02u:%02u%s", y, m, d, (unsigned)(s / 3600), (unsigned)(s / 60 % 60),
             (unsigned)(s % 60), (l.level & TIME_OK) ? "" : "?");
  } else {
    snprintf(ts, sizeof(ts), "----");
  }
  int n = snprintf(out, cap, "%s +%lu %s %s %s", ts, (unsigned long)l.uptime, levelChar(l.level), moduleName(l.module),
                   l.text);
  if (n < 0) return 0;
  return (size_t)n < cap ? (size_t)n : cap - 1;
}

void dayName(uint32_t utc, char* out, size_t cap) {
  int y;
  unsigned m, d;
  civil((long)(utc / 86400), y, m, d);
  snprintf(out, cap, "%04d%02u%02u", y, m, d);
}

long dayNumber(const char* s) {
  for (int i = 0; i < 8; i++) {
    if (s[i] < '0' || s[i] > '9') return -1;
  }
  int y = (s[0] - '0') * 1000 + (s[1] - '0') * 100 + (s[2] - '0') * 10 + (s[3] - '0');
  unsigned m = (s[4] - '0') * 10 + (s[5] - '0');
  unsigned d = (s[6] - '0') * 10 + (s[7] - '0');
  if (y < 2000 || m < 1 || m > 12 || d < 1 || d > 31) return -1;
  return daysFromCivil(y, m, d);
}

}  // namespace logbuf
