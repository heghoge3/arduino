#include "hike_files.h"
#include <stdio.h>

namespace hikefiles {
void pathsForHike(uint32_t t, char* trackCsv, char* trackBin, char* markCsv, char* markBin) {
  // Unix → YYYYMMDD_HHMMSS
  long z = t / 86400 + 719468, era = z / 146097;
  unsigned doe = z - era * 146097, yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100), mp = (5 * doy + 2) / 153;
  unsigned d = doy - (153 * mp + 2) / 5 + 1, m = mp < 10 ? mp + 3 : mp - 9;
  int y = yoe + era * 400 + (m <= 2);
  uint32_t s = t % 86400;
  char base[24];
  snprintf(base, sizeof(base), "%04d%02u%02u_%02u%02u%02u", y, m, d,
           (unsigned)(s / 3600), (unsigned)((s / 60) % 60), (unsigned)(s % 60));
  snprintf(trackCsv, 40, "/tracks/%s.csv", base);
  snprintf(trackBin, 40, "/tracks/%s.bin", base);
  snprintf(markCsv, 40, "/marks/%s.csv", base);
  snprintf(markBin, 40, "/marks/%s.bin", base);
}

bool removeArchived(uint32_t target, uint32_t current, bool sdReady,
                    bool (*exists)(const char*), bool (*remove)(const char*)) {
  if (!sdReady || !target || target == current) return false;
  char paths[4][40];
  pathsForHike(target, paths[0], paths[1], paths[2], paths[3]);
  bool ok = true;
  for (auto& path : paths) if (exists(path) && !remove(path)) ok = false;
  return ok;
}
}
