// GPS ATGM336H: питание через AO3401 (LOW = вкл), NMEA на Serial1, разбор — nmea::Parser.
// В сборке FAKE_SENSORS фиксы эмулируются (с эпизодами подмены — проверить всю цепочку).
#pragma once
#include <stdint.h>

#include "nmea.h"

namespace gps {

void begin();
void power(bool on);
bool isOn();

// Читать UART. Вызывать часто (каждую итерацию цикла)
void poll();

const nmea::Parser& parser();
// Новый фикс с прошлого вызова (по RMC). Сбрасывается при чтении.
bool takeNewFix();
// Идёт ли NMEA (последнее предложение не старше 3 с)
bool alive();
bool fixFresh();
// Секунд с включения
uint32_t onSeconds();

// Команда модулю: тело без $ и *CS, например "PCAS04,3" (сверить с даташитом)
void sendCommand(const char* body);
// Печатать сырой NMEA в USB (отладка)
void setEcho(bool on);
#ifdef FAKE_SENSORS
// Эмуляция: вести трек от позиции телефона (первый раз после включения). true — перенесли сейчас
bool fakeAnchor(double lat, double lon);
#endif

}  // namespace gps
