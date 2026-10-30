#pragma once
#include <stdint.h>

namespace hikefiles {
void pathsForHike(uint32_t t, char* trackCsv, char* trackBin, char* markCsv, char* markBin);
bool removeArchived(uint32_t target, uint32_t current, bool sdReady,
                    bool (*exists)(const char*), bool (*remove)(const char*));
}
