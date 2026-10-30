#pragma once
#include <stdint.h>

namespace compass {
struct Calibration {
  uint32_t magic = 0;
  float offset[3]{}, scale[3]{}, gravity[3]{};
  float field = 0;
};
bool valid(const Calibration& c);
// Плоский компас: требуется горизонтальное положение платы компаса.
// Гравитация MPU используется для отклонения сильного наклона от положения при сохранении.
float heading(const Calibration& c, const float mag[3], const float gravity[3], float offsetDeg, bool inverted = false);
class Recorder {
 public:
  void reset();
  void add(const float mag[3]);
  bool finish(const float gravity[3], Calibration& out) const;
  uint32_t samples() const { return samples_; }
 private:
  uint32_t samples_ = 0;
  float lo_[3]{}, hi_[3]{};
};
}
