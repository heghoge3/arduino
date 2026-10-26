#include "sun.h"

#include <math.h>

namespace sun {

namespace {

constexpr double RAD = M_PI / 180.0;

double geomMeanLong(double t) {
  double l0 = fmod(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0);
  return l0 < 0 ? l0 + 360.0 : l0;
}
double geomMeanAnomaly(double t) { return 357.52911 + t * (35999.05029 - 0.0001537 * t); }
double eccentricity(double t) { return 0.016708634 - t * (0.000042037 + 0.0000001267 * t); }

double eqOfCenter(double t) {
  double m = geomMeanAnomaly(t) * RAD;
  return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) +
         sin(3 * m) * 0.000289;
}

double obliquity(double t) {
  double sec = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813));
  double e0 = 23.0 + (26.0 + sec / 60.0) / 60.0;
  return e0 + 0.00256 * cos((125.04 - 1934.136 * t) * RAD);
}

double declination(double t) {
  double lambda = geomMeanLong(t) + eqOfCenter(t) - 0.00569 - 0.00478 * sin((125.04 - 1934.136 * t) * RAD);
  return asin(sin(obliquity(t) * RAD) * sin(lambda * RAD)) / RAD;
}

double equationOfTime(double t) {
  double eps = obliquity(t) * RAD, l0 = geomMeanLong(t) * RAD, e = eccentricity(t), m = geomMeanAnomaly(t) * RAD;
  double y = tan(eps / 2);
  y *= y;
  double eq = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) - 0.5 * y * y * sin(4 * l0) -
              1.25 * e * e * sin(2 * m);
  return eq / RAD * 4.0;
}

}  // namespace

bool event(uint32_t dayUtc, double lat, double lon, double altitudeDeg, bool rising, uint32_t& out) {
  uint32_t dayStart = dayUtc - dayUtc % 86400;
  double jd0 = dayStart / 86400.0 + 2440587.5;  // юлианская дата полуночи UTC
  double minutes = 720.0;
  for (int i = 0; i < 3; i++) {
    double t = (jd0 + minutes / 1440.0 - 2451545.0) / 36525.0;
    double decl = declination(t) * RAD;
    double cosHa = (sin(altitudeDeg * RAD) - sin(lat * RAD) * sin(decl)) / (cos(lat * RAD) * cos(decl));
    if (cosHa < -1 || cosHa > 1) return false;
    double ha = acos(cosHa) / RAD;
    minutes = 720.0 - 4.0 * (lon + (rising ? ha : -ha)) - equationOfTime(t);
  }
  out = dayStart + static_cast<uint32_t>(lround(minutes * 60.0));
  return true;
}

}  // namespace sun
