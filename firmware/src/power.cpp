#include "power.h"
#include <Arduino.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>
#include "pins.h"
#include "config.h"
#include "ui.h"
#include "power_gesture.h"
namespace powergate {
bool available(){return PIN_POWER_HOLD==7 &&
 rtc_gpio_is_valid_gpio((gpio_num_t)PIN_POWER_HOLD) && PIN_POWER_HOLD!=PIN_BTN_A &&
 PIN_POWER_HOLD!=PIN_BTN_B && PIN_POWER_HOLD!=PIN_MPU_INT && PIN_POWER_HOLD!=PIN_SDA &&
 PIN_POWER_HOLD!=PIN_SCL && PIN_POWER_HOLD!=PIN_BUZZER && PIN_POWER_HOLD!=PIN_GPS_EN &&
 PIN_POWER_HOLD!=PIN_SD_CS && PIN_POWER_HOLD!=PIN_SD_MOSI && PIN_POWER_HOLD!=PIN_SD_SCK &&
 PIN_POWER_HOLD!=PIN_SD_MISO && PIN_POWER_HOLD!=PIN_RGB;}
void level(bool high){
 rtc_gpio_init((gpio_num_t)PIN_POWER_HOLD);
 rtc_gpio_set_direction((gpio_num_t)PIN_POWER_HOLD,RTC_GPIO_MODE_OUTPUT_ONLY);
 rtc_gpio_set_level((gpio_num_t)PIN_POWER_HOLD,high?1:0);
}
void begin(){
 if(!available())return;
 if(esp_sleep_get_wakeup_cause()!=ESP_SLEEP_WAKEUP_UNDEFINED){
   // Restore output before releasing the retained level.
   level(true);rtc_gpio_hold_dis((gpio_num_t)PIN_POWER_HOLD);return;
 }
 level(false);
 pinMode(PIN_BTN_A,INPUT_PULLUP);pinMode(PIN_BTN_B,INPUT_PULLUP);
 ledcSetup(0,1000,8);ledcAttachPin(PIN_BUZZER,0);
 powergesture::Start gesture;
 uint8_t last=0;uint32_t released=0;bool releasing=false;
 while(!gesture.done()){
   bool a=digitalRead(PIN_BTN_A)==LOW,b=digitalRead(PIN_BTN_B)==LOW;
   uint32_t ms=millis();
   if(!a){if(!releasing){released=ms;releasing=true;}if(ms-released>=100)cut();}
   else releasing=false;
   uint8_t step=gesture.tick(a,b,ms);
   if(step!=last){
     if(step==4)level(true);
     ledcWriteTone(0,step?900+step*350:0);last=step;
     if(step)delay(70);
     ledcWriteTone(0,0);
   }
   delay(5);
 }
 level(true); // Hold before user releases A; no hike is started.
 while(digitalRead(PIN_BTN_A)==LOW||digitalRead(PIN_BTN_B)==LOW)delay(10);
 delay(40);ledcDetachPin(PIN_BUZZER);
}
bool retainForSleep(){
 if(!available())return true; // USB bench configuration, no power gate
 return rtc_gpio_set_level((gpio_num_t)PIN_POWER_HOLD,1)==ESP_OK &&
        rtc_gpio_hold_en((gpio_num_t)PIN_POWER_HOLD)==ESP_OK;
}
[[noreturn]] void cut(){
 if(available()){
   rtc_gpio_hold_dis((gpio_num_t)PIN_POWER_HOLD);level(false);
 }
 // USB/backfeed may keep ESP alive. Never restart a hike or call this deep sleep.
 delay(500);ui::splash("Питание не снято");
 for(;;)delay(1000);
}
}
