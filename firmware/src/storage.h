// Хранилище на SD (ТЗ, раздел 5):
//   /tracks/YYYYMMDD_HHMMSS.csv — трек для человека и ПК (колонки по ТЗ)
//   /tracks/YYYYMMDD_HHMMSS.bin — те же записи пакетами POINT (51 байт) — для быстрой синхронизации по BLE
//   /marks/YYYYMMDD_HHMMSS.csv и .bin — метки (в .bin записи по 80 байт)
//   /blacklist.json — чёрный список подмены
// Нет SD или она сломалась — записи держатся в PSRAM, чтобы телефон всё равно мог их забрать.
#pragma once
#include <stdint.h>

#include "proto.h"

namespace storage {

struct CsvExtra {
  float courseDeg;
  float battMa;
};

bool begin();
bool sdOk();
uint32_t totalMb();
uint32_t freeMb();

// Открыть (или продолжить) поход с этим временем старта
void openHike(uint32_t hikeStart);
uint32_t hikeStart();
// Часы переставили на d секунд: сдвинуть время у точек и меток с номеров fromPoint/fromMark
// (записаны по неверным часам) в памяти и в .bin на SD. CSV не трогаем — он для ПК, правка дорогая
void shiftTimes(uint32_t fromPoint, uint32_t fromMark, int32_t d);
// Поход начат по неверным часам: перенести начало (и имена файлов на SD) на newStart
void renameHike(uint32_t newStart);

// false — запись на SD не удалась (тревога «ошибка SD»); в PSRAM запись есть в любом случае
bool appendPoint(const proto::Point& p, const CsvExtra& extra);
uint32_t pointCount();
bool readPoint(uint32_t index, proto::Point& out);
uint32_t firstPointSince(uint32_t utc);
uint32_t lastPointTime();

bool appendMark(const proto::Mark& m);
uint32_t markCount();
bool readMark(uint32_t index, proto::Mark& out);
uint32_t firstMarkSince(uint32_t utc);
uint16_t nextMarkId();

bool loadBlacklist(proto::BlEntry* out, uint8_t& n);
bool saveBlacklist(const proto::BlEntry* in, uint8_t n);

}  // namespace storage
