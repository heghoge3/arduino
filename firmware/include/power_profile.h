#pragma once
#include "power_off.h"
#include "pins.h"

#ifndef V2_POWER_PROFILE
#define V2_POWER_PROFILE 0
#endif
#ifndef V2_SLIDER_BENCH
#define V2_SLIDER_BENCH 0
#endif
#ifndef V2_POWER_SWITCH_PIN
#define V2_POWER_SWITCH_PIN -1
#endif

constexpr poweroff::Profile POWER_PROFILE=static_cast<poweroff::Profile>(V2_POWER_PROFILE);
constexpr bool POWER_SLIDER=POWER_PROFILE==poweroff::Profile::Slider;
constexpr bool POWER_SLIDER_BENCH=V2_SLIDER_BENCH!=0;
constexpr int PIN_POWER_SWITCH=V2_POWER_SWITCH_PIN;
static_assert(V2_POWER_PROFILE==0 || V2_POWER_PROFILE==1,"Unknown V2 power profile");
static_assert(!POWER_SLIDER_BENCH || POWER_SLIDER,"Slider bench needs slider profile");
static_assert(!POWER_SLIDER || POWER_SLIDER_BENCH || PIN_POWER_SWITCH>=0,
              "Slider GPIO is not assigned; use s3_slider_bench for USB-only evaluation");
// Native USB, strapping pins and already assigned signals are not implicit spare pins.
constexpr bool sliderPinAllowed(int p) {
  return p>=0 && p<=21 && p!=0 && p!=3 && p!=19 && p!=20 && !v2ForbiddenPin(p) &&
    p!=PIN_SDA && p!=PIN_SCL && p!=PIN_GPS_RX && p!=PIN_GPS_TX && p!=PIN_GPS_EN &&
    p!=PIN_SD_CS && p!=PIN_SD_MOSI && p!=PIN_SD_SCK && p!=PIN_SD_MISO &&
    p!=PIN_BTN_A && p!=PIN_BTN_B && p!=PIN_MPU_INT && p!=PIN_BUZZER &&
    p!=PIN_POWER_HOLD && p!=PIN_RGB;
}
static_assert(!POWER_SLIDER || POWER_SLIDER_BENCH || sliderPinAllowed(PIN_POWER_SWITCH),
              "Slider GPIO needs an explicit reviewed pin allocation; no safe spare assigned");
static_assert(!POWER_SLIDER_BENCH || PIN_POWER_SWITCH==-1,"Bench profile must not read a real switch GPIO");
