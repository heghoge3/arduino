#pragma once
#include <stdint.h>

namespace inertial {
// Приблизительное направление ходьбы, а не «нос» корпуса. Без магнитометра:
// ориентация MPU — гироскоп + гравитация; ось ходьбы — горизонтальные ускорения.
// Азимут этой системы координат привязывается только к проверенному движению GPS.
class Navigator {
 public:
  void reset();
  bool copyBias(float out[3]) const;
  void restoreBias(const float bias[3]);
  void feed(float ax, float ay, float az, float gxDps, float gyDps, float gzDps,
            uint32_t steps, uint32_t ms);
  bool offerGps(double lat, double lon, uint32_t steps, uint32_t ms);
  float heading(uint32_t ms) const;
  bool aligned() const { return aligned_; }
  bool gyroReady() const { return biasReady_; }
  bool keepAwake(uint32_t ms) const;
  uint8_t state(uint32_t ms) const; // INFO: 0 готов; 14 нужен GPS; 15 покой; 16 нет оси; 17 дрейф
 private:
  static constexpr unsigned WINDOW = 80;
  struct Sample { float x, y; uint32_t steps; } samples_[WINDOW]{};
  unsigned size_ = 0, at_ = 0;
  float q_[4] = {1, 0, 0, 0}, bias_[3]{}, quietSum_[3]{}, quietSq_[3]{}, previousA_[3]{};
  float smoothA_[3]{}, axis_ = 0, offset_ = 0;
  uint32_t lastMs_ = 0, quietSince_ = 0, quietN_ = 0, axisAt_ = 0;
  uint32_t lastSteps_ = 0, lastStepAt_ = 0, lastAxisCalc_ = 0, alignedAt_ = 0;
  bool init_ = false, biasReady_ = false, haveAxis_ = false, aligned_ = false;
  bool haveGps_ = false;
  double gpsLat_ = 0, gpsLon_ = 0;
  uint32_t gpsSteps_ = 0, gpsAt_ = 0;
  void calculateAxis(uint32_t ms);
  void loseOrientation();
};
}
