#pragma once
#include "proto.h"
namespace blacklistdefaults {
bool parse(const char* json,proto::BlEntry* out,uint8_t& count);
inline bool migrate(proto::BlEntry& point) {
  bool known=(point.lat7==600346000 && point.lon7==305693000) ||
             (point.lat7==601161000 && point.lon7==313864000);
  if (known && point.radiusM==1000) { point.radiusM=5000; return true; }
  return false;
}
}
