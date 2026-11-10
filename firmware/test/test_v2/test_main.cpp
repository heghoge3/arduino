#include <unity.h>
#include <string.h>
#include "ds3231_time.h"
#include "power_gesture.h"
void setUp(){}
void tearDown(){}
void rtc_calendar_and_12h(){
 uint8_t r[]={0x59,0x58,0x23,1,0x29,0x02,0x24};uint32_t t;
 TEST_ASSERT_TRUE(ds3231time::decode(r,t));
 TEST_ASSERT_EQUAL(nmea::toUnix(2024,2,29,23,58,59),t);
 r[6]=0x25;TEST_ASSERT_FALSE(ds3231time::decode(r,t));
 r[4]=0x28;r[2]=0x52;TEST_ASSERT_TRUE(ds3231time::decode(r,t));
 TEST_ASSERT_EQUAL(nmea::toUnix(2025,2,28,0,58,59),t);
 r[2]=0x72;TEST_ASSERT_TRUE(ds3231time::decode(r,t));
 TEST_ASSERT_EQUAL(nmea::toUnix(2025,2,28,12,58,59),t);
 r[2]=0x60;TEST_ASSERT_FALSE(ds3231time::decode(r,t));
}
void rtc_rejects_invalid_and_reserved(){
 const uint8_t good[]={0,0,0x20,7,0x31,0x12,0x26};uint32_t t;
 TEST_ASSERT_TRUE(ds3231time::decode(good,t));
 const uint8_t invalid[]={0x60,0x6A,0x24,0,0x32,0x81,0xFA};
 for(int i=0;i<7;i++){uint8_t r[7];memcpy(r,good,7);r[i]=invalid[i];TEST_ASSERT_FALSE(ds3231time::decode(r,t));}
}
void power_requires_continuous_both_and_wraps(){
 powergesture::Start g;
 TEST_ASSERT_EQUAL(0,g.tick(true,false,0));
 TEST_ASSERT_EQUAL(0,g.tick(true,true,200));
 TEST_ASSERT_EQUAL(3,g.tick(true,true,3900));
 TEST_ASSERT_EQUAL(0,g.tick(false,true,4000));
 TEST_ASSERT_FALSE(g.done());
 g.tick(true,true,0xFFFFFF00u);
 TEST_ASSERT_EQUAL(3,g.tick(true,true,0xFFFFFF00u+3999u));
 TEST_ASSERT_EQUAL(4,g.tick(true,true,0xFFFFFF00u+4000u));
 TEST_ASSERT_TRUE(g.done());
}
int main(){UNITY_BEGIN();RUN_TEST(rtc_calendar_and_12h);RUN_TEST(rtc_rejects_invalid_and_reserved);RUN_TEST(power_requires_continuous_both_and_wraps);return UNITY_END();}
