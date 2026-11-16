#pragma once
#include <stdint.h>
namespace lcdpower {
// Every power interruption invalidates initialization. Backend owns GPIO/I2C.
template<class Backend> class Controller {
 public:
  explicit Controller(Backend& b) : b_(b) {}
  bool ensureOn() {
    if (ready_ && b_.powered()) return true;
    ready_ = false;
    if (!b_.powerOn()) { b_.powerOff(); return false; }
    b_.settle();
    const uint8_t address = b_.probe();
    if ((address!=0x3e && address!=0x3f) || !b_.initialize(address)) {
      b_.powerOff(); return false;
    }
    ready_=true; return true;
  }
  void off() { ready_=false; b_.powerOff(); }
 private:
  Backend& b_;
  bool ready_=false;
};
}
