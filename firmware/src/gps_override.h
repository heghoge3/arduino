#pragma once
#include <stdint.h>

// Ручная проверка GPS из USB-консоли; через 10 минут возвращаем автоматику.
class GpsOverride {
 public:
  void set(bool on, uint32_t ms) { mode_ = on ? 1 : 0; until_ = ms + 600000; }
  void clear() { mode_ = -1; }
  bool wanted(bool automatic, bool critical, uint32_t ms) {
    if (mode_ >= 0 && static_cast<int32_t>(ms - until_) >= 0) clear();
    return !critical && (mode_ < 0 ? automatic : mode_ == 1);
  }
 private:
  int8_t mode_ = -1;
  uint32_t until_ = 0;
};
