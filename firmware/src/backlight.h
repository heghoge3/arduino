#pragma once

// GPIO14 -> AO3401 gate via resistor: LOW=ON, HIGH=OFF, source on SYS_3V3.
// External gate-to-source pull-up required at boot/reset. The entire LCD VCC is switched; SDA/SCL off-state tolerance requires hardware verification.
namespace backlight {
bool begin();
bool set(bool on);
bool on(); // Logical driver state, not a measurement of VCC.
bool sleep(); // HIGH retained by RTC IO, independent of GPIO7 HOLD.
}
