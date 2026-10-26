// Время: часы DS1302 с батарейкой + доверие к источнику (ТЗ, раздел 5).
// Порядок доверия: телефон → DS1302 → GPS без подмены.
#pragma once
#include <stdint.h>

namespace clockx {

enum Source : uint8_t { SRC_NONE = 0, SRC_RTC = 1, SRC_PHONE = 2, SRC_GPS = 3 };

// Запустить DS1302: отключить подзаряд, проверить, шли ли часы. true — модуль отвечает.
bool begin();

// Текущее время, Unix-с UTC (по millis() от последнего чтения DS1302)
uint32_t now();

// Время проверено: часы не стояли и их ставили с телефона или GPS
bool trusted();
Source source();
bool rtcPresent();

// Поставить время (и записать в DS1302)
void setFromPhone(uint32_t utc);
// Время GPS: ставим только если часы не проверены, и только без подозрения на подмену
void offerGps(uint32_t utc, bool spoofSuspected);

// Перечитать DS1302 (раз в 10 мин, после сна)
void resync();

}  // namespace clockx
