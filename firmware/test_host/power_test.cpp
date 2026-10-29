#include "sensors.h"
#include "logic.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

int main() {
  sensors::Power p{true, 0.0f, 0.0f, 0};
  logic::PowerLogic policy;
  assert(!p.batteryKnown());
  assert(policy.update(1, p.controlPercent(), 0) == logic::CAMP);
  assert(policy.update(2, p.controlPercent(), 12) == logic::MOVING);
  assert(policy.gpsContinuous(logic::GPS_ALWAYS));
  assert(!policy.low());
  puts("PASS: zero battery input does not disable movement/GPS or trigger battery alerts");

  p.voltage = 3.3f;
  assert(p.batteryKnown());
  assert(policy.update(3, p.controlPercent(), 12) == logic::CRITICAL);
  assert(!policy.gpsContinuous(logic::GPS_ALWAYS));
  puts("PASS: a real depleted battery still triggers protection");

  p.voltage = NAN;
  assert(!p.batteryKnown());
  assert(policy.update(4, p.controlPercent(), 12) == logic::MOVING);
  p.voltage = 4.0f;
  p.ok = false;
  assert(!p.batteryKnown());
  puts("PASS: unavailable measurements are not treated as low charge");
}
