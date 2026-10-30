// Проверки подмены GNSS (ТЗ, раздел 7). Чистый C++, тестируется на ПК.
// Подмену нельзя победить — только заметить и честно пометить точку.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace spoof {

// Биты причин — как в протоколе (spoof_mask)
enum Reason : uint8_t {
  R_BLACKLIST = 1 << 0,
  R_PHONE = 1 << 1,
  R_JUMP = 1 << 2,
  R_SNR_FLAT = 1 << 3,
  R_BARO = 1 << 4,
  R_ACCEL = 1 << 5,
  R_TIME = 1 << 6,
};

// Сильные признаки — подмена по одному. Слабые — только если их два и больше (SNR, барометр, шаги).
constexpr uint8_t STRONG = R_BLACKLIST | R_PHONE | R_JUMP | R_TIME;

constexpr size_t MAX_BLACKLIST = 24;

struct Config {
  float maxSpeedMs = 11.0f;        // ~40 км/ч: пешком и на велосипеде быстрее не бывает
  float jumpSlackM = 200.0f;       // запас на погрешность
  float phoneMinM = 3000.0f;       // расхождение с телефоном: max(3 км, 2×точность)
  uint32_t phoneFreshS = 600;      // позиция телефона годна 10 мин
  float snrSpreadDb = 3.0f;        // разброс SNR у ≥5 спутников меньше — подозрительно
  uint8_t snrMinSats = 5;
  float baroDiffM = 100.0f;        // GPS против барометра
  uint32_t timeDiffS = 300;        // время GPS против DS1302
  uint32_t accelWindowS = 120;     // идут шаги, а позиция стоит столько секунд
  float accelStillM = 15.0f;
  uint32_t stepsWhileStill = 60;
  uint32_t lastGoodMaxAgeS = 300;  // GPS молчал дольше (пауза, стоянка, переезд на машине) — скачок не проверяем
};

struct Input {
  uint32_t now;            // доверенное время (DS1302/телефон)
  bool timeTrusted;
  double lat, lon;         // позиция от GPS
  bool altValid;
  float altGps;
  bool baroValid;          // барометр откалиброван
  float altBaro;
  bool gpsTimeValid;
  uint32_t gpsTime;
  const uint8_t* usedSnr;  // SNR спутников, участвующих в решении
  size_t usedCount;
  uint32_t steps;          // счётчик шагов (нарастающий)
};

struct Result {
  bool spoof;
  uint8_t mask;
};

// Расстояние по поверхности, м
double distanceM(double lat1, double lon1, double lat2, double lon2);

class Detector {
 public:
  explicit Detector(const Config& cfg = Config()) : cfg_(cfg) {}

  void setBlacklist(const double* lat, const double* lon, const uint16_t* radiusM, size_t n);
  void setPhone(double lat, double lon, float accuracyM, uint32_t time);
  void clearPhone() { phoneValid_ = false; }
  // Забыть последнюю достоверную точку (эмуляция перенесла трек — иначе это «невозможный скачок»)
  void forgetLastGood() { lastGoodValid_ = false; stillValid_ = false; }
  // Часы переставили на d секунд: сдвинуть запомненные моменты (иначе «возраст» точек врёт)
  void shiftTime(int32_t d) {
    if (phoneTime_) phoneTime_ += (uint32_t)d;
    if (lastGoodTime_) lastGoodTime_ += (uint32_t)d;
    if (stillTime_) stillTime_ += (uint32_t)d;
  }

  // Проверить очередной фикс. Чистые фиксы запоминаются как «последняя достоверная точка».
  Result check(const Input& in);

  bool hasLastGood() const { return lastGoodValid_; }
  double lastGoodLat() const { return lastGoodLat_; }
  double lastGoodLon() const { return lastGoodLon_; }
  bool inEpisode() const { return episode_; }

 private:
  Config cfg_;
  size_t blN_ = 0;
  double blLat_[MAX_BLACKLIST], blLon_[MAX_BLACKLIST];
  uint16_t blR_[MAX_BLACKLIST];

  bool phoneValid_ = false;
  double phoneLat_ = 0, phoneLon_ = 0;
  float phoneAcc_ = 0;
  uint32_t phoneTime_ = 0;

  bool lastGoodValid_ = false;
  double lastGoodLat_ = 0, lastGoodLon_ = 0;
  uint32_t lastGoodTime_ = 0;

  // Окно «шаги против позиции»
  bool stillValid_ = false;
  double stillLat_ = 0, stillLon_ = 0;
  uint32_t stillTime_ = 0, stillSteps_ = 0;

  bool episode_ = false;
};

}  // namespace spoof
