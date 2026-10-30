#pragma once
#include <stdint.h>

namespace pdr {
// Только геометрия счисления. Начало и направление должны поступать из проверенных источников.
class Tracker {
 public:
  void reset() { valid_ = false; distance_ = 0; }
  bool anchor(double lat, double lon, uint32_t steps);
  bool advance(uint32_t steps, float headingDeg, float strideM = 0.75f);
  bool valid() const { return valid_; }
  double lat() const { return lat_; }
  double lon() const { return lon_; }
  float distance() const { return distance_; }
 private:
  bool valid_ = false;
  double lat_ = 0, lon_ = 0;
  uint32_t steps_ = 0;
  float distance_ = 0;
};
}
