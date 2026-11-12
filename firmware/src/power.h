#pragma once
#include <stdint.h>
namespace powergate {
bool available();
void begin(); // Before peripherals; on a configured EN board validates A+B at cold power-on.
bool retainForSleep();
[[noreturn]] void cut(); // Caller must save data and wait for both buttons to be released.
bool sliderProfile();
bool switchOn(); // Slider interface: OFF=LOW, ON=HIGH. Actual contact wiring is unverified.
uint64_t sliderWakeMask();
bool releaseSliderHold(); // True means a release was issued; actual power removal remains unverified.
void restoreSliderHold();
bool benchSwitch(bool on); // USB-only test input, refused in every other profile.
}
