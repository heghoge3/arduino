#include <unity.h>
#include "lcd_backlight.h"

// Model the output latch separately from the retained physical pin. A release
// with a HIGH latch would flash the LED and is a test failure.
struct Pin {
  bool latch = true, physical = false, held = true, initialized = false;
  bool fault = false, flash = false;
  int highWrites = 0;
  bool prepareOff() {
    if (fault) return false;
    latch = false;
    initialized = true;
    if (!held) physical = latch;
    return true;
  }
  bool releaseHold() {
    if (fault) return false;
    flash |= latch;
    held = false;
    physical = latch;
    return true;
  }
  bool write(bool on) {
    if (fault) return false;
    if (on) ++highWrites;
    latch = on;
    if (!held) physical = latch;
    return true;
  }
  bool holdOff() {
    if (fault) return false;
    held = true;
    return !physical;
  }
};
using Driver = lcdbacklight::Driver<Pin>;
void setUp() {}
void tearDown() {}

void boot_is_dark_even_with_stale_high_latch() {
  Pin p; Driver d(p);
  TEST_ASSERT_TRUE(d.begin());
  TEST_ASSERT_TRUE(p.initialized);
  TEST_ASSERT_FALSE(p.physical);
  TEST_ASSERT_FALSE(p.flash);
  TEST_ASSERT_EQUAL(0, p.highWrites); // Missing LCD never requests ON.
  TEST_ASSERT_TRUE(d.set(true));
  TEST_ASSERT_TRUE(p.physical);
  TEST_ASSERT_TRUE(d.set(false));
  TEST_ASSERT_FALSE(p.physical);
}
void sleep_and_in_process_wake_preserve_off_before_release() {
  Pin p; Driver d(p); d.begin(); d.set(true);
  TEST_ASSERT_TRUE(d.sleep());
  TEST_ASSERT_TRUE(p.held);
  TEST_ASSERT_FALSE(p.physical);
  TEST_ASSERT_TRUE(d.set(false));
  TEST_ASSERT_TRUE(p.held);
  p.latch = true; // Lost latch must not flash when retained LOW is released.
  TEST_ASSERT_TRUE(d.set(true));
  TEST_ASSERT_FALSE(p.flash);
  TEST_ASSERT_TRUE(p.physical);
  TEST_ASSERT_FALSE(p.held);
}
void deep_sleep_reset_restores_off_without_automatic_light() {
  Pin p;
  { Driver before(p); before.begin(); before.set(true); before.sleep(); }
  p.latch = true;
  Driver after(p);
  TEST_ASSERT_TRUE(after.begin());
  TEST_ASSERT_FALSE(p.physical);
  TEST_ASSERT_FALSE(p.flash);
  TEST_ASSERT_FALSE(after.on());
  TEST_ASSERT_EQUAL(1, p.highWrites);
}
void failed_io_disables_light_until_reinitialization() {
  Pin p; Driver d(p);
  p.fault = true;
  TEST_ASSERT_FALSE(d.begin());
  TEST_ASSERT_FALSE(d.set(true));
  TEST_ASSERT_EQUAL(0, p.highWrites);
  p.fault = false;
  TEST_ASSERT_TRUE(d.begin());
  TEST_ASSERT_TRUE(d.set(true));
  p.fault = true;
  TEST_ASSERT_FALSE(d.sleep());
  p.fault = false;
  TEST_ASSERT_FALSE(d.set(true));
  TEST_ASSERT_TRUE(d.begin());
  TEST_ASSERT_FALSE(p.physical);
}
int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(boot_is_dark_even_with_stale_high_latch);
  RUN_TEST(sleep_and_in_process_wake_preserve_off_before_release);
  RUN_TEST(deep_sleep_reset_restores_off_without_automatic_light);
  RUN_TEST(failed_io_disables_light_until_reinitialization);
  return UNITY_END();
}
