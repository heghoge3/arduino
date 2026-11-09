#include "pdr.h"
#include <math.h>

namespace pdr {
bool Tracker::anchor(double lat, double lon, uint32_t steps) {
  if (!isfinite(lat) || !isfinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return false;
  lat_ = lat; lon_ = lon; steps_ = steps; distance_ = 0; valid_ = true;
  return true;
}

bool Tracker::advance(uint32_t steps, float heading, float stride) {
  if (!valid_) return false;
  if (steps == steps_) return true;
  if (steps < steps_ || steps - steps_ > 20 || !isfinite(heading) || !isfinite(stride) || stride <= 0 || stride > 2) {
    // Нет достоверного направления для этих шагов: не дорисовываем их задним числом.
    valid_ = false;
    return false;
  }
  const double rad = M_PI / 180.0;
  const double d = (steps - steps_) * stride;
  const double arc = d / 6371000.0;
  const double a = lat_ * rad, b = lon_ * rad, h = heading * rad;
  double sinLat = sin(a) * cos(arc) + cos(a) * sin(arc) * cos(h);
  sinLat = fmax(-1.0, fmin(1.0, sinLat));
  double next = asin(sinLat);
  double longitude = b + atan2(sin(h) * sin(arc) * cos(a), cos(arc) - sin(a) * sin(next));
  lat_ = next / rad;
  lon_ = fmod(longitude / rad + 540.0, 360.0) - 180.0;
  distance_ += d;
  steps_ = steps;
  return true;
}
}
