#include <unity.h>
#include <initializer_list>
#include "screen_input.h"

using screeninput::Button;
using screeninput::Controls;

void setUp() {}
void tearDown() {}

void either_button_wakes_without_action() {
  for (bool a : {false, true}) {
    Controls c(15000, 30, 1500);
    c.begin(0, true);
    c.poll(a, !a, 100);
    TEST_ASSERT_EQUAL(Button::NONE, c.poll(a, !a, 130));
    TEST_ASSERT_TRUE(c.active());
    TEST_ASSERT_EQUAL(Button::NONE, c.poll(a, !a, 5000)); // long wake is not a command
    c.poll(false, false, 5010);
    TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, false, 5040));
    c.poll(a, !a, 5100); c.poll(a, !a, 5130);
    c.poll(false, false, 5200);
    TEST_ASSERT_EQUAL(a ? Button::A_SHORT : Button::B_SHORT, c.poll(false, false, 5230));
  }
}

void inactivity_and_rollover() {
  Controls c(15000, 30, 1500);
  const uint32_t start = UINT32_MAX - 500;
  c.begin(start, true); c.show(start);
  c.poll(false, false, start + 14999);
  TEST_ASSERT_TRUE(c.active());
  c.poll(false, false, start + 15000);
  TEST_ASSERT_FALSE(c.active());
  c.poll(true, false, start + 16000); c.poll(true, false, start + 16030);
  c.poll(false, false, start + 16100); c.poll(false, false, start + 16130);
  c.poll(false, false, start + 31129);
  TEST_ASSERT_TRUE(c.active());
  c.poll(false, false, start + 31130);
  TEST_ASSERT_FALSE(c.active());
}

void deep_sleep_wake_and_both_buttons_are_consumed() {
  Controls c(15000, 30, 1500);
  c.begin(4000, true, Button::B_SHORT);
  TEST_ASSERT_TRUE(c.active());
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(true, true, 5000));
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(true, true, 9000));
  c.poll(false, true, 9010);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, true, 12000));
  c.poll(false, false, 12010);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, false, 12040));
  TEST_ASSERT_FALSE(c.held());
  // The button may have been released before setup finished.
  c.begin(4000, true, Button::A_SHORT);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, false, 4030));
  TEST_ASSERT_TRUE(c.active());
  TEST_ASSERT_FALSE(c.held());
}

void bounce_and_long_press_on_visible_screen() {
  Controls c(15000, 30, 1500);
  c.begin(0, true); c.show(0);
  c.poll(true, false, 100); c.poll(false, false, 110);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, false, 140));
  c.poll(false, true, 200); c.poll(false, true, 230);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, true, 1729));
  TEST_ASSERT_EQUAL(Button::B_LONG, c.poll(false, true, 1730));
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, true, 3000));
  c.poll(false, false, 3010);
  TEST_ASSERT_EQUAL(Button::NONE, c.poll(false, false, 3040));
  c.poll(false, false, 18039); TEST_ASSERT_TRUE(c.active());
  c.poll(false, false, 18040); TEST_ASSERT_FALSE(c.active());
}

void headless_controls_keep_actions() {
  Controls c(15000, 30, 1500);
  c.begin(0, false);
  c.poll(true, false, 100); c.poll(true, false, 130);
  c.poll(false, false, 200);
  TEST_ASSERT_EQUAL(Button::A_SHORT, c.poll(false, false, 230));
  TEST_ASSERT_FALSE(c.active());
  c.begin(4000, false, Button::B_SHORT);
  c.poll(false, false, 4000);
  TEST_ASSERT_EQUAL(Button::B_SHORT, c.poll(false, false, 4030));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(either_button_wakes_without_action);
  RUN_TEST(inactivity_and_rollover);
  RUN_TEST(deep_sleep_wake_and_both_buttons_are_consumed);
  RUN_TEST(bounce_and_long_press_on_visible_screen);
  RUN_TEST(headless_controls_keep_actions);
  return UNITY_END();
}
