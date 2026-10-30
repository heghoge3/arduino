#pragma once
#include <stdint.h>
namespace walking {
// GPS — геометрия; между принятыми позициями — шаги, без повторного прибавления хорды.
class Distance {
 public:
  void reset(uint32_t steps=0, double meters=0);
  void update(uint32_t now, uint32_t steps, bool recording, bool gps, double lat, double lon, float hdop, float stride);
  double meters() const { return meters_; }
  uint32_t steps() const { return steps_; }
 private:
  double meters_=0, lat_=0, lon_=0;
  uint32_t steps_=0, time_=0;
  bool haveGps_=false, gap_=true;
};
}
