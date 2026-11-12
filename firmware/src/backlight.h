#pragma once

// GPIO14 -> active-HIGH 3.3 V input of an external LED-only switch.
// External OFF pull-down required during ROM boot/reset. LCD VCC stays powered.
namespace backlight {
bool begin();
bool set(bool on);
bool sleep(); // LOW retained by RTC IO, independent of GPIO7 HOLD.
}
