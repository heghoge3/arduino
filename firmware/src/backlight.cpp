#include "backlight.h"
#include "lcd_backlight.h"
#include "pins.h"
#include <driver/rtc_io.h>

namespace backlight {
namespace {
constexpr gpio_num_t pin = static_cast<gpio_num_t>(PIN_LCD_BACKLIGHT);
struct Output {
  bool prepareOff() {
    // Preload LOW before output enable / release of the retained sleep level.
    return rtc_gpio_is_valid_gpio(pin) && rtc_gpio_init(pin) == ESP_OK &&
           rtc_gpio_set_level(pin, 0) == ESP_OK &&
           rtc_gpio_set_direction(pin, RTC_GPIO_MODE_OUTPUT_ONLY) == ESP_OK;
  }
  bool releaseHold() { return rtc_gpio_hold_dis(pin) == ESP_OK; }
  bool write(bool on) { return rtc_gpio_set_level(pin, on ? 1 : 0) == ESP_OK; }
  bool holdOff() { return rtc_gpio_hold_en(pin) == ESP_OK; }
} output;
lcdbacklight::Driver<Output> driver(output);
}
bool begin() { return driver.begin(); }
bool set(bool on) { return driver.set(on); }
bool sleep() { return driver.sleep(); }
}
