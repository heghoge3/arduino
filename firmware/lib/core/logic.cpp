#include "logic.h"

namespace logic {

Power PowerLogic::update(uint32_t now, uint8_t battPct, uint32_t steps) {
  if (!init_) {
    init_ = true;
    burstStart_ = now;
    burstSteps_ = steps;
    lastSteps_ = steps;
    lastStepTime_ = now;
  }

  // Заряд с гистерезисом, чтобы не дёргаться на границе
  if (!crit_ && battPct < cfg_.critPct) crit_ = true;
  else if (crit_ && battPct > cfg_.critExitPct) crit_ = false;
  if (!low_ && battPct < cfg_.lowPct) low_ = true;
  else if (low_ && battPct > cfg_.lowExitPct) low_ = false;

  // Движение по шагам
  if (steps != lastSteps_) {
    lastSteps_ = steps;
    lastStepTime_ = now;
  }
  if (!moving_) {
    if (now - burstStart_ > cfg_.moveWindowS) {
      burstStart_ = now;
      burstSteps_ = steps;
    } else if (steps - burstSteps_ >= cfg_.stepsToMove) {
      moving_ = true;
    }
  } else if (now - lastStepTime_ >= cfg_.campAfterS) {
    moving_ = false;
    burstStart_ = now;
    burstSteps_ = steps;
  }

  if (crit_) state_ = CRITICAL;
  else if (low_) state_ = ECONOMY;
  else state_ = moving_ ? MOVING : CAMP;
  return state_;
}

uint16_t PowerLogic::logIntervalS() const {
  switch (state_) {
    case MOVING: return cfg_.logMovingS;
    case ECONOMY: return cfg_.logEconomyS;
    case CRITICAL: return cfg_.logCriticalS;
    default: return cfg_.logCampS;
  }
}

uint16_t PowerLogic::beaconIntervalS(uint16_t beaconMovingS) const {
  if (beaconMovingS == 0) return 0;  // невидимка
  switch (state_) {
    case MOVING: return beaconMovingS;
    case CAMP: return beaconMovingS * 2;
    case ECONOMY: return beaconMovingS * 4;
    default: return 0;  // критический заряд — радио выключено
  }
}

bool PowerLogic::gpsContinuous(GpsMode mode) const {
  return state_ == MOVING && mode == GPS_ALWAYS;
}

bool PowerLogic::gpsPulsed(GpsMode mode) const {
  if (state_ == MOVING) return mode == GPS_PULSE;
  return state_ == ECONOMY && moving_;  // экономия: только импульсами и только в движении
}

bool GpsPulse::update(uint32_t now, bool hasFix, bool newPoint) {
  if (!on_) {
    if (now >= nextStart_) {
      on_ = true;
      onSince_ = now;
      got_ = 0;
    }
    return on_;
  }
  if (hasFix && newPoint) got_++;
  bool enough = got_ >= want_;
  bool timeout = now - onSince_ >= timeout_;
  if (enough || timeout) {
    on_ = false;
    nextStart_ = onSince_ + period_;
    if (nextStart_ <= now) nextStart_ = now + 1;
  }
  return on_;
}

Alerts::Decision Alerts::update(uint32_t now, uint8_t activeMask) {
  Decision d{false, 0};
  uint8_t rising = activeMask & ~active_;
  // Ушедшие тревоги забываем вместе с подтверждением: при повторном появлении снова сигналим
  acked_ &= activeMask;
  active_ = activeMask;
  if (rising) {
    d.openWindow = true;
    d.fresh = rising;
    repeats_ = 0;
    lastWindow_ = now;
    return d;
  }
  if (unacked() && repeats_ < maxRepeats && now - lastWindow_ >= repeatS) {
    d.openWindow = true;
    repeats_++;
    lastWindow_ = now;
  }
  return d;
}

}  // namespace logic
