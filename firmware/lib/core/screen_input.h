#pragma once
#include <stdint.h>

namespace screeninput {

enum class Button : uint8_t { NONE, A_SHORT, A_LONG, B_SHORT, B_LONG, HIKE_START, HIKE_FINISH, HIKE_RESUME, PAIR };

// No hardware dependencies: debounce, wake-only presses and inactivity use millis().
class Controls {
 public:
  Controls(uint32_t timeout, uint32_t debounce, uint32_t longPress)
      : timeout_(timeout), debounce_(debounce), longPress_(longPress) {}

  void begin(uint32_t now, bool display, Button wake = Button::NONE) {
    a_ = Key{}; b_ = Key{};
    display_ = display;
    active_ = false;
    suppress_ = wake != Button::NONE && display;
    releasedAt_ = now;
    releasing_ = true;
    if (suppress_) show(now);
    if (!display && wake != Button::NONE) {
      Key& key = wake == Button::A_SHORT ? a_ : b_;
      key.raw = key.down = true;
      key.changedAt = key.downAt = now;
    }
  }

  void show(uint32_t now) { active_ = display_; lastActivity_ = now; }
  void hide() { active_ = false; }
  bool active() const { return active_; }
  bool held() const { return a_.raw || b_.raw || a_.down || b_.down || suppress_; }

  Button poll(bool a, bool b, uint32_t now) {
    const Button ea = update(a_, a, now, Button::A_SHORT, Button::A_LONG);
    const Button eb = update(b_, b, now, Button::B_SHORT, Button::B_LONG);
    if (suppress_) {
      if (a || b) releasing_ = false;
      else if (!releasing_) { releasing_ = true; releasedAt_ = now; }
      else if (now - releasedAt_ >= debounce_) {
        suppress_ = false;
        a_ = Key{}; b_ = Key{};
        lastActivity_ = now;
      }
      return Button::NONE;
    }
    // A hidden display consumes the whole gesture, including the other button.
    if (display_ && !active_ && (a_.down || b_.down)) {
      show(now);
      suppress_ = true;
      releasedAt_ = now;
      releasing_ = false;
      return Button::NONE;
    }
    if (active_ && !held() && now - lastActivity_ >= timeout_) hide();
    return ea != Button::NONE ? ea : eb;
  }

 private:
  struct Key {
    bool raw = false, down = false, longFired = false;
    uint32_t changedAt = 0, downAt = 0;
  };
  Button update(Key& key, bool raw, uint32_t now, Button shortEvent, Button longEvent) {
    if (raw != key.raw) { key.raw = raw; key.changedAt = now; }
    if (key.down != key.raw && now - key.changedAt >= debounce_) {
      key.down = key.raw;
      lastActivity_ = now;
      if (key.down) {
        key.downAt = now;
        key.longFired = false;
      } else if (!key.longFired) return shortEvent;
    }
    if (key.down && !key.longFired && now - key.downAt >= longPress_) {
      key.longFired = true;
      lastActivity_ = now;
      return longEvent;
    }
    return Button::NONE;
  }
  Key a_, b_;
  uint32_t timeout_, debounce_, longPress_;
  uint32_t lastActivity_ = 0, releasedAt_ = 0;
  bool display_ = false, active_ = false, suppress_ = false, releasing_ = false;
};

}  // namespace screeninput
