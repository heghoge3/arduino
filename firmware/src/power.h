#pragma once
namespace powergate {
bool available();
void begin(); // Before peripherals; on a configured EN board validates A+B at cold power-on.
bool retainForSleep();
[[noreturn]] void cut(); // Caller must save data and wait for both buttons to be released.
}
