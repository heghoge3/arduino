// Журнал гаджета (отладочный режим): события, ошибки, снимки состояния.
// Строки копятся в RTC-памяти (переживает сон и сбой), пачкой уходят на SD:
//   /logs/ГГГГММДД.log — текст для человека, /logs/all.bin — для выгрузки в приложение (SYNC_LOG).
// Писать можно из любого потока (в том числе из колбэков BLE).
#pragma once
#include <stdint.h>

#include "logbuf.h"
#include "proto.h"

namespace dlog {

// Сразу после включения/пробуждения, до всего остального
void begin();
// SD готова: узнать номер последней строки, раздать номера, удалить журналы старше 30 дней
void attachStorage();

void setDebug(bool on);
bool debug();

void log(uint8_t level, uint8_t module, const char* fmt, ...) __attribute__((format(printf, 3, 4)));

// Сбросить накопленное на SD. Вызывается из основного цикла
void flush();
uint32_t pending();
// Пора сбрасывать: есть ошибка или буфер заполнился наполовину
bool urgent();

// Перед глубоким сном: сколько будем спать (для времени с включения)
void beforeSleep(uint32_t sleepS);
uint32_t uptimeS();

// Выгрузка в приложение: строки с номером больше since
uint32_t syncStart(uint32_t since);
uint32_t syncEnd();
// Пакет LOG по глобальному индексу; 0 — не прочитали
size_t syncRead(uint32_t idx, uint8_t* out, size_t cap);

// Счётчики для снимка состояния (обнуляются при выключении питания)
struct Counters {
  uint32_t envFail, powerFail, accelFail;
  uint32_t sdFail;
  uint32_t bleConnects, bleRejects;
  uint32_t wakeTimer, wakeButton, wakeMotion;
  uint32_t dropped;
  uint32_t maxLoopMs;
};
Counters& counters();

}  // namespace dlog

#define LOGE(m, ...) dlog::log(logbuf::ERR, logbuf::m, __VA_ARGS__)
#define LOGW(m, ...) dlog::log(logbuf::WARN, logbuf::m, __VA_ARGS__)
#define LOGI(m, ...) dlog::log(logbuf::INFO, logbuf::m, __VA_ARGS__)
#define LOGD(m, ...) dlog::log(logbuf::DBG, logbuf::m, __VA_ARGS__)
