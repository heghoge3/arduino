// Журнал гаджета: кольцевой буфер строк и форматирование (ТЗ: отладочный режим).
// Буфер живёт в RTC-памяти, которая переживает глубокий сон и перезагрузку после сбоя:
// строки копятся там и пачкой уходят на SD. Чистый C++ — тестируется на ПК.
#pragma once
#include <stddef.h>
#include <stdint.h>

#include "proto.h"

namespace logbuf {

enum Level : uint8_t { ERR = 0, WARN = 1, INFO = 2, DBG = 3 };
constexpr uint8_t LEVEL_MASK = 0x03;
constexpr uint8_t TIME_OK = 0x80;  // бит в level: время проверено

enum Module : uint8_t { M_SYS = 0, M_PWR, M_GPS, M_SPOOF, M_SENS, M_SD, M_RTC, M_BLE, M_UI, M_CMD, M_COUNT };

const char* levelChar(uint8_t level);
const char* moduleName(uint8_t module);

constexpr uint32_t NO_SEQ = 0xFFFFFFFFu;
constexpr uint32_t RING_MAGIC = 0x564C4F47;  // «VLOG»
constexpr uint8_t RING_N = 24;

struct Ring {
  uint32_t magic;
  uint32_t nextSeq;   // NO_SEQ — номер ещё не известен (после включения, до чтения SD)
  uint32_t head;      // сколько строк добавлено всего
  uint32_t flushed;   // до какой строки (по head) уже на SD
  uint32_t dropped;   // потеряно, пока SD была недоступна и буфер переполнился
  uint32_t check;
  proto::LogLine recs[RING_N];

  void init();
  // Целостность заголовка: после включения питания RTC-память содержит мусор
  bool valid() const;
  void seal();

  void push(uint32_t time, uint32_t uptime, uint8_t level, uint8_t module, const char* text, size_t len);
  uint32_t pending() const { return head - flushed; }
  // i-я ещё не сброшенная строка, 0 — самая старая
  proto::LogLine& pendingAt(uint32_t i) { return recs[(flushed + i) % RING_N]; }
  void markFlushed(uint32_t n);
  // Номер известен (прочитали с SD): раздать его строкам, которые ждали
  void assignSeq(uint32_t next);
};

// «2026-10-31 14:03:22 +12345 I GPS текст»; время не проверено — «?» после даты, нет — «----»
size_t formatLine(const proto::LogLine& l, char* out, size_t cap);

// «20261031» для имени файла дня
void dayName(uint32_t utc, char* out, size_t cap);
// Дней с 1970-01-01 по имени файла «ГГГГММДД»; -1 — не похоже на дату
long dayNumber(const char* name8);

}  // namespace logbuf
