// Логика состояний устройства (docs/gadget-logic.md): питание, GPS-импульсы, тревоги.
// Без железа и Arduino — только решения по входным данным. Тестируется на ПК.
#pragma once
#include <stdint.h>

namespace logic {

enum Power : uint8_t { CAMP = 0, MOVING = 1, ECONOMY = 2, CRITICAL = 3 };
enum GpsMode : uint8_t { GPS_ALWAYS = 0, GPS_PULSE = 1 };

struct PowerConfig {
  uint32_t campAfterS = 300;   // нет шагов столько — стоянка
  uint32_t stepsToMove = 10;   // столько шагов…
  uint32_t moveWindowS = 30;   // …за столько секунд — «в пути»
  uint8_t lowPct = 15, lowExitPct = 18;
  uint8_t critPct = 5, critExitPct = 8;
  uint16_t logMovingS = 10;
  uint16_t logCampS = 60;
  uint16_t logEconomyS = 60;
  uint16_t logCriticalS = 300;
};

class PowerLogic {
 public:
  explicit PowerLogic(const PowerConfig& cfg = PowerConfig()) : cfg_(cfg) {}

  // Вызывать при каждом пробуждении/итерации. steps — нарастающий счётчик шагов.
  Power update(uint32_t now, uint8_t battPct, uint32_t steps);

  Power state() const { return state_; }
  bool moving() const { return moving_; }
  bool low() const { return low_; }
  bool critical() const { return crit_; }

  // Интервал записи для текущего состояния, с
  uint16_t logIntervalS() const;
  // Период маячка, с (0 — не выходить в эфир). beaconMovingS — настройка «в пути» (0 — невидимка).
  uint16_t beaconIntervalS(uint16_t beaconMovingS) const;
  // Нужен ли GPS прямо сейчас в режиме «всегда» (импульсы решает GpsPulse)
  bool gpsContinuous(GpsMode mode) const;
  // Нужны ли импульсы GPS
  bool gpsPulsed(GpsMode mode) const;

  PowerConfig& config() { return cfg_; }

 private:
  PowerConfig cfg_;
  Power state_ = CAMP;
  bool moving_ = false, low_ = false, crit_ = false;
  bool init_ = false;
  uint32_t burstStart_ = 0, burstSteps_ = 0;
  uint32_t lastSteps_ = 0, lastStepTime_ = 0;
};

// Импульсный GPS: включиться раз в periodS, ждать фикс до timeoutS, взять pointsWanted точек, выключиться
class GpsPulse {
 public:
  void configure(uint16_t periodS, uint16_t timeoutS = 20, uint8_t pointsWanted = 3) {
    period_ = periodS; timeout_ = timeoutS; want_ = pointsWanted;
  }
  // true — GPS должен быть включён сейчас
  bool update(uint32_t now, bool hasFix, bool newPoint);
  void stop() { on_ = false; }

 private:
  uint16_t period_ = 30, timeout_ = 20;
  uint8_t want_ = 3, got_ = 0;
  bool on_ = false;
  uint32_t onSince_ = 0, nextStart_ = 0;
};

// Тревоги: окно при появлении, повтор раз в repeatS (до maxRepeats), пока не подтвердили
class Alerts {
 public:
  struct Decision {
    bool openWindow;  // открыть окно рекламы с флагом тревоги, пискнуть
    uint8_t fresh;    // какие тревоги новые (для ALERT и сигнала)
  };

  Decision update(uint32_t now, uint8_t activeMask);
  void ack(uint8_t mask) { acked_ |= mask & active_; }
  uint8_t active() const { return active_; }
  uint8_t unacked() const { return active_ & ~acked_; }

  uint32_t repeatS = 600;
  uint8_t maxRepeats = 3;

 private:
  uint8_t active_ = 0, acked_ = 0;
  uint8_t repeats_ = 0;
  uint32_t lastWindow_ = 0;
};

}  // namespace logic
