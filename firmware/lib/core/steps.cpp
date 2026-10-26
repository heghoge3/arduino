#include "steps.h"

#include <math.h>

namespace steps {

bool Detector::feed(float ax, float ay, float az, uint32_t tMs) {
  float mag = sqrtf(ax * ax + ay * ay + az * az);
  if (!init_) {
    init_ = true;
    base_ = mag;
    smooth_ = 0;
    lastStep_ = tMs;
    return false;
  }
  base_ += cfg_.baseAlpha * (mag - base_);
  float hp = mag - base_;
  smooth_ += cfg_.smoothAlpha * (hp - smooth_);

  if (!armed_) {
    if (smooth_ < cfg_.release) armed_ = true;
    return false;
  }
  if (smooth_ < cfg_.threshold || tMs - lastStep_ < cfg_.refractoryMs) return false;

  armed_ = false;
  uint32_t gap = tMs - lastStep_;
  lastStep_ = tMs;
  if (!haveStep_ || gap > cfg_.maxGapMs) {
    haveStep_ = true;
    // Начало новой серии: ждём подтверждения следующим шагом
    pending_ = true;
    return false;
  }
  if (pending_) {
    pending_ = false;
    count_ += 2;  // засчитываем и первый шаг серии
  } else {
    count_ += 1;
  }
  return true;
}

}  // namespace steps
