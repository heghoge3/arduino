#include <unity.h>
#include <string>
#include "lcd_power.h"
struct Board {
 bool on=false,gateOk=true,initOk=true,unsafe=false;
 unsigned address=0x3e; std::string events;
 bool powered(){return on;}
 bool powerOn(){events+='P';on=gateOk;return gateOk;}
 void settle(){if(!on)unsafe=true;events+='W';}
 uint8_t probe(){if(!on)unsafe=true;events+='Q';return address;}
 bool initialize(uint8_t){if(!on)unsafe=true;events+='I';return initOk;}
 void powerOff(){events+='O';on=false;}
};
void setUp(){} void tearDown(){}
void init_always_follows_power_settle_and_probe(){Board b;lcdpower::Controller<Board> d(b);TEST_ASSERT_TRUE(d.ensureOn());TEST_ASSERT_EQUAL_STRING("PWQI",b.events.c_str());TEST_ASSERT_FALSE(b.unsafe);TEST_ASSERT_TRUE(d.ensureOn());TEST_ASSERT_EQUAL_STRING("PWQI",b.events.c_str());}
void idle_off_does_not_issue_i2c_and_wake_reinitializes(){Board b;lcdpower::Controller<Board>d(b);d.ensureOn();b.events.clear();d.off();TEST_ASSERT_EQUAL_STRING("O",b.events.c_str());TEST_ASSERT_FALSE(b.on);d.ensureOn();TEST_ASSERT_EQUAL_STRING("OPWQI",b.events.c_str());}
void external_off_or_reset_invalidates_initialization(){Board b;lcdpower::Controller<Board>d(b);d.ensureOn();b.on=false;b.events.clear();d.ensureOn();TEST_ASSERT_EQUAL_STRING("PWQI",b.events.c_str());}
void missing_or_ambiguous_lcd_is_powered_down_and_can_retry(){Board b;b.address=0;lcdpower::Controller<Board>d(b);TEST_ASSERT_FALSE(d.ensureOn());TEST_ASSERT_EQUAL_STRING("PWQO",b.events.c_str());TEST_ASSERT_FALSE(b.on);b.address=0x3f;TEST_ASSERT_TRUE(d.ensureOn());TEST_ASSERT_FALSE(b.unsafe);}
void failed_gate_never_probes_or_initializes(){Board b;b.gateOk=false;lcdpower::Controller<Board>d(b);TEST_ASSERT_FALSE(d.ensureOn());TEST_ASSERT_EQUAL_STRING("PO",b.events.c_str());TEST_ASSERT_FALSE(b.unsafe);}
void failed_init_shuts_supply_down(){Board b;b.initOk=false;lcdpower::Controller<Board>d(b);TEST_ASSERT_FALSE(d.ensureOn());TEST_ASSERT_EQUAL_STRING("PWQIO",b.events.c_str());TEST_ASSERT_FALSE(b.on);b.initOk=true;TEST_ASSERT_TRUE(d.ensureOn());}
int main(){UNITY_BEGIN();RUN_TEST(init_always_follows_power_settle_and_probe);RUN_TEST(idle_off_does_not_issue_i2c_and_wake_reinitializes);RUN_TEST(external_off_or_reset_invalidates_initialization);RUN_TEST(missing_or_ambiguous_lcd_is_powered_down_and_can_retry);RUN_TEST(failed_gate_never_probes_or_initializes);RUN_TEST(failed_init_shuts_supply_down);return UNITY_END();}
