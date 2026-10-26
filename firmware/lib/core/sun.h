// Закат и восход офлайн (алгоритм NOAA, точность ~1 мин) — для экрана устройства.
// Тот же расчёт, что в приложении (astro/SunCalc.kt).
#pragma once
#include <stdint.h>

namespace sun {

// Время события в UTC (Unix-с) для дня, в который попадает dayUtc.
// rising — восход, иначе заход. altitudeDeg: −0,833 закат, −6 конец гражданских сумерек.
// false — события в этот день нет (полярный день/ночь).
bool event(uint32_t dayUtc, double lat, double lon, double altitudeDeg, bool rising, uint32_t& out);

inline bool sunset(uint32_t dayUtc, double lat, double lon, uint32_t& out) {
  return event(dayUtc, lat, lon, -0.833, false, out);
}

}  // namespace sun
