#include "spoof.h"

#include <math.h>

namespace spoof {

double distanceM(double lat1, double lon1, double lat2, double lon2) {
  const double R = 6371000.0;
  const double k = M_PI / 180.0;
  double dLat = (lat2 - lat1) * k;
  double dLon = (lon2 - lon1) * k;
  double a = sin(dLat / 2) * sin(dLat / 2) +
             cos(lat1 * k) * cos(lat2 * k) * sin(dLon / 2) * sin(dLon / 2);
  a=fmax(0,fmin(1,a));
  return 2 * R * atan2(sqrt(a), sqrt(1 - a));
}

void Detector::setBlacklist(const double* lat, const double* lon, const uint16_t* radiusM, size_t n) {
  blN_ = n > MAX_BLACKLIST ? MAX_BLACKLIST : n;
  for (size_t i = 0; i < blN_; i++) {
    blLat_[i] = lat[i];
    blLon_[i] = lon[i];
    blR_[i] = radiusM[i];
  }
  if (lastGoodValid_ && blacklisted(lastGoodLat_,lastGoodLon_)) {
    lastGoodValid_=false;stillValid_=false;episode_=true;cleanCount_=0;
  }
}

void Detector::setPhone(double lat, double lon, float accuracyM, uint32_t time) {
  if (!isfinite(lat)||!isfinite(lon)||lat < -90||lat>90||lon < -180||lon>180||!isfinite(accuracyM)||accuracyM<=0||blacklisted(lat,lon)) { phoneValid_=false; return; }
  phoneValid_ = true;
  phoneLat_ = lat;
  phoneLon_ = lon;
  phoneAcc_ = accuracyM;
  phoneTime_ = time;
}

static int bits(uint8_t v) {
  int n = 0;
  while (v) { n += v & 1; v >>= 1; }
  return n;
}

bool Detector::blacklisted(double lat, double lon) const {
  for (size_t i = 0; i < blN_; i++) if (distanceM(lat, lon, blLat_[i], blLon_[i]) <= blR_[i]) return true;
  return false;
}

Result Detector::check(const Input& in) {
  uint8_t mask = 0;
  if (!isfinite(in.lat)||!isfinite(in.lon)||in.lat < -90||in.lat>90||in.lon < -180||in.lon>180) {
    episode_=true; cleanCount_=0; return {true,R_JUMP};
  }

  // 1. Чёрный список
  for (size_t i = 0; i < blN_; i++) {
    if (distanceM(in.lat, in.lon, blLat_[i], blLon_[i]) <= blR_[i]) { mask |= R_BLACKLIST; break; }
  }

  // 2. Сверка с телефоном
  if (phoneValid_ && in.timeTrusted && in.now >= phoneTime_ && in.now - phoneTime_ <= cfg_.phoneFreshS) {
    float limit = phoneAcc_ * 2 > cfg_.phoneMinM ? phoneAcc_ * 2 : cfg_.phoneMinM;
    if (distanceM(in.lat, in.lon, phoneLat_, phoneLon_) > limit) mask |= R_PHONE;
  }

  // 3. Невозможный скачок от последней достоверной точки
  if (lastGoodValid_ && in.now >= lastGoodTime_ && in.now - lastGoodTime_ <= cfg_.lastGoodMaxAgeS) {
    double dt = in.now - lastGoodTime_;
    double d = distanceM(in.lat, in.lon, lastGoodLat_, lastGoodLon_);
    if (d > cfg_.jumpSlackM + dt * cfg_.maxSpeedMs) mask |= R_JUMP;
  }

  // 4. Одинаковый SNR у многих спутников
  if (in.usedCount >= cfg_.snrMinSats) {
    uint8_t mn = 255, mx = 0;
    size_t heard = 0;
    for (size_t i = 0; i < in.usedCount; i++) {
      uint8_t s = in.usedSnr[i];
      if (!s) continue;
      heard++;
      if (s < mn) mn = s;
      if (s > mx) mx = s;
    }
    if (heard >= cfg_.snrMinSats && (mx - mn) <= cfg_.snrSpreadDb) mask |= R_SNR_FLAT;
  }

  // 5. Барометр против GPS-высоты
  if (in.altValid && in.baroValid && fabsf(in.altGps - in.altBaro) > cfg_.baroDiffM) mask |= R_BARO;

  // 6. Шаги идут, а позиция стоит (подменщик держит одну точку)
  if (!stillValid_ || distanceM(in.lat, in.lon, stillLat_, stillLon_) > cfg_.accelStillM) {
    stillValid_ = true;
    stillLat_ = in.lat;
    stillLon_ = in.lon;
    stillTime_ = in.now;
    stillSteps_ = in.steps;
  } else if (in.now >= stillTime_ && in.steps >= stillSteps_ && in.now - stillTime_ >= cfg_.accelWindowS && in.steps - stillSteps_ >= cfg_.stepsWhileStill) {
    mask |= R_ACCEL;
  }

  // 7. Время GPS против доверенных часов
  if (in.gpsTimeValid && in.timeTrusted) {
    uint32_t d = in.gpsTime > in.now ? in.gpsTime - in.now : in.now - in.gpsTime;
    if (d > cfg_.timeDiffS) mask |= R_TIME;
  }

  const uint8_t weak = mask & ~STRONG;
  bool spoofed = (mask & STRONG) != 0 || bits(weak) >= 2;

  if (spoofed) {
    cleanCount_ = 0;
  } else if (episode_) {
    // Один чистый фикс между ложными не должен попадать в трек и становиться опорной точкой.
    // После долгого перерыва подтверждение начинаем заново, а не считаем время без сигнала чистым.
    if (!cleanCount_ || in.now < cleanLast_ || in.now - cleanLast_ > 3 ||
        distanceM(cleanLat_,cleanLon_,in.lat,in.lon)>30+3.5*(in.now-cleanSince_)) {
      cleanLat_=in.lat; cleanLon_=in.lon;
      cleanSince_ = in.now;
      cleanCount_ = 1;
    } else if (in.now > cleanLast_ && cleanCount_ < 255) {
      cleanCount_++;
    }
    cleanLast_ = in.now;
    if (in.now - cleanSince_ < cfg_.recoveryS || cleanCount_ < 3) {
      spoofed = true;
      mask |= R_RECOVERY;
    }
  }

  if (!spoofed) {
    lastGoodValid_ = true;
    lastGoodLat_ = in.lat;
    lastGoodLon_ = in.lon;
    lastGoodTime_ = in.now;
  }
  episode_ = spoofed;
  return Result{spoofed, mask};
}

}  // namespace spoof
