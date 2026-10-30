#include "sensors.h"
#include "logic.h"
#include "gps_override.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

int main() {
  GpsOverride gps;
  assert(!gps.wanted(false, false, 0));
  gps.set(true, 100);
  assert(gps.wanted(false, false, 101));  // на стоянке не выключается следующим тиком
  assert(!gps.wanted(false, true, 102));  // защита аккумулятора важнее консоли
  assert(!gps.wanted(false, false, 600100));
  gps.set(false, 700000);
  assert(!gps.wanted(true, false, 700001));
  gps.clear();
  assert(gps.wanted(true, false, 700002));
  gps.set(true, UINT32_MAX - 100);
  assert(gps.wanted(false, false, 100));
  assert(!gps.wanted(false, false, 600000));
  puts("PASS: manual GPS survives camp policy, expires across millis wrap and respects critical battery");
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
