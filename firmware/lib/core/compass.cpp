#include "compass.h"
#include <math.h>

namespace compass {
constexpr uint32_t MAGIC = 0x434D5031;
bool valid(const Calibration& c) {
  if (c.magic != MAGIC || !isfinite(c.field) || c.field < 500 || c.field > 30000) return false;
  float g = 0;
  for (int i = 0; i < 3; i++) {
    if (!isfinite(c.offset[i]) || fabsf(c.offset[i]) > 32768 || !isfinite(c.scale[i]) ||
        c.scale[i] < 0.2f || c.scale[i] > 5 || !isfinite(c.gravity[i])) return false;
    g += c.gravity[i] * c.gravity[i];
  }
  return g >= 0.64f && g <= 1.44f;
}

float heading(const Calibration& c, const float mag[3], const float gravity[3], float offset, bool inverted) {
  if (!valid(c) || !isfinite(offset)) return NAN;
  float g = 0, reference = 0, dot = 0, field = 0, m[3];
  for (int i = 0; i < 3; i++) {
    if (!isfinite(mag[i]) || !isfinite(gravity[i])) return NAN;
    g += gravity[i] * gravity[i]; reference += c.gravity[i] * c.gravity[i];
    dot += gravity[i] * c.gravity[i];
    m[i] = (mag[i] - c.offset[i]) * c.scale[i];
    field += m[i] * m[i];
  }
  // Сильный наклон, толчок или изменение поля рядом с металлом — не доверяем направлению.
  if (g < 0.64f || g > 1.44f || dot / sqrtf(g * reference) < 0.866f ||
      field < c.field * c.field * 0.25f || field > c.field * c.field * 2.25f ||
      m[0] * m[0] + m[1] * m[1] < c.field * c.field * 0.01f) return NAN;
  float angle = atan2f(m[1], m[0]) * 180 / M_PI;
  return fmodf((inverted ? -angle : angle) + offset + 720, 360);
}

void Recorder::reset() { samples_ = 0; }
void Recorder::add(const float m[3]) {
  for (int i = 0; i < 3; i++) if (!isfinite(m[i]) || fabsf(m[i]) >= 32760) return;
  for (int i = 0; i < 3; i++) {
    if (!samples_) lo_[i] = hi_[i] = m[i];
    else { lo_[i] = fminf(lo_[i], m[i]); hi_[i] = fmaxf(hi_[i], m[i]); }
  }
  samples_++;
}
bool Recorder::finish(const float gravity[3], Calibration& out) const {
  if (samples_ < 100) return false;
  float radius[3], sum = 0, low = 1e9f, high = 0;
  for (int i = 0; i < 3; i++) {
    radius[i] = (hi_[i] - lo_[i]) / 2;
    low = fminf(low, radius[i]); high = fmaxf(high, radius[i]); sum += radius[i];
  }
  if (low < 500 || high / low > 3.5f) return false;
  Calibration candidate{};
  candidate.magic = MAGIC; candidate.field = sum / 3;
  for (int i = 0; i < 3; i++) {
    candidate.offset[i] = (hi_[i] + lo_[i]) / 2;
    candidate.scale[i] = candidate.field / radius[i];
    candidate.gravity[i] = gravity[i];
  }
  if (!valid(candidate)) return false;
  out = candidate;
  return true;
}
}
