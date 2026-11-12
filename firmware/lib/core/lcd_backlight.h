#pragma once

namespace lcdbacklight {

// AO3401 high-side LED switch: gate LOW = ON, gate HIGH = OFF (3.3 V source).
constexpr bool levelFor(bool on) { return !on; }
// Output accepts logical ON/OFF, never LED power itself.
// prepareOff must preload HIGH before enabling output, including while RTC-held.
template<class Output> class Driver {
 public:
  explicit Driver(Output& output) : output_(output) {}
  bool begin() {
    ready_ = false;
    on_ = false;
    held_ = false;
    if (!output_.prepareOff() || !output_.releaseHold()) return fail();
    ready_ = true;
    return true;
  }
  bool set(bool on) {
    if (!ready_) return false;
    if (held_) {
      if (!on) return true; // Keep OFF retained until a deliberate wake.
      if (!output_.prepareOff() || !output_.releaseHold()) return fail();
      held_ = false;
    }
    if (on != on_ && !output_.write(on)) return fail();
    on_ = on;
    return true;
  }
  bool sleep() {
    if (!ready_ || !set(false)) return false;
    if (!output_.holdOff()) return fail();
    held_ = true;
    return true;
  }
  bool on() const { return on_; }
 private:
  bool fail() {
    output_.write(false); // Best effort; gate-to-source pull-up is still required.
    ready_ = on_ = false;
    return false;
  }
  Output& output_;
  bool ready_ = false, on_ = false, held_ = false;
};

}  // namespace lcdbacklight
