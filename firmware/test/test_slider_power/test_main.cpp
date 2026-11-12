#include <unity.h>
#include <cstring>
#include <vector>
#include "power_off.h"
#include "power_gesture.h"
#include "power_profile.h"
void setUp(){} void tearDown(){}
struct Store {
 hike::State live{},disk{};
 bool failAbort=false,failData=false,failWrite=false,corruptRead=false,failLog=false;
 bool inactiveAborted=false;
 std::vector<int> calls;
 Store(){live.id=100;live.phase=hike::ACTIVE;hike::seal(live);disk=live;}
 bool abortInactive(){calls.push_back(1);if(failAbort)return false;inactiveAborted=true;return true;}
 bool saveData(){calls.push_back(2);return !failData;}
 bool saveSession(){
   calls.push_back(3);hike::State next;
   return poweroff::sessionForOff(live,next) && hike::commit(live,next,
     [&](const hike::State& s){if(failWrite)return false;disk=s;return true;},
     [&](hike::State& s){s=disk;if(corruptRead)s.id++;return true;});
 }
 bool flushLogs(){calls.push_back(4);return !failLog;}
};
poweroff::Slider requested(){poweroff::Slider c;c.begin(true,0);c.observe(false,1);c.observe(false,51);return c;}
void primary_profile_is_slider_legacy_gesture_requires_explicit_selection(){
 TEST_ASSERT_EQUAL(1,int(POWER_PROFILE));TEST_ASSERT_TRUE(POWER_SLIDER);
 TEST_ASSERT_TRUE(POWER_SLIDER_BENCH);TEST_ASSERT_EQUAL(-1,PIN_POWER_SWITCH);
 TEST_ASSERT_TRUE(sliderPinAllowed(3));TEST_ASSERT_FALSE(sliderPinAllowed(0));
 powergesture::Start g;g.tick(true,true,1);TEST_ASSERT_FALSE(g.done());g.tick(true,true,4001);TEST_ASSERT_TRUE(g.done());
}
void off_bounce_and_timer_wrap(){
 poweroff::Slider c;c.begin(true,0xFFFFFFE0);c.observe(false,0xFFFFFFF0);c.observe(true,5);c.observe(false,10);
 c.observe(false,59);TEST_ASSERT_FALSE(c.busy());c.observe(false,60);TEST_ASSERT_TRUE(c.busy());
}
void boot_with_switch_off_cannot_start_work(){
 poweroff::Slider c;c.begin(false,0);TEST_ASSERT_TRUE(c.busy());Store s;
 TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::None,false,s));TEST_ASSERT_EQUAL(hike::RECOVERY,s.disk.phase);
}
void off_saves_same_hike_and_next_boot_asks_resume(){
 auto c=requested();Store s;TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::None,false,s));
 TEST_ASSERT_EQUAL(3,s.calls.size());TEST_ASSERT_EQUAL(2,s.calls[0]);TEST_ASSERT_EQUAL(3,s.calls[1]);TEST_ASSERT_EQUAL(4,s.calls[2]);
 TEST_ASSERT_EQUAL(hike::RECOVERY,s.disk.phase);TEST_ASSERT_EQUAL(100,s.disk.id);TEST_ASSERT_EQUAL(0,s.disk.endedAt);
 const auto after=hike::afterBoot(s.disk,true);TEST_ASSERT_EQUAL(hike::RECOVERY,after.phase);TEST_ASSERT_EQUAL(100,after.id);
 hike::State resumed;TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(after,hike::RESUME,after.id,after.revision,42,0,0,resumed));
 TEST_ASSERT_EQUAL(100,resumed.id);TEST_ASSERT_EQUAL(hike::ACTIVE,resumed.phase);
}
void finished_history_and_idle_are_not_reopened(){
 for(auto phase:{hike::IDLE,hike::ENDED,hike::RECOVERY}){
  Store s;s.live.phase=phase;s.live.id=phase==hike::IDLE?0:100;s.live.endedAt=phase==hike::ENDED?150:0;hike::seal(s.live);
  auto c=requested();TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::None,false,s));
  TEST_ASSERT_EQUAL(phase,s.disk.phase);TEST_ASSERT_EQUAL(s.live.id,s.disk.id);TEST_ASSERT_EQUAL(s.live.endedAt,s.disk.endedAt);
 }
}
void corrupt_session_or_revision_overflow_keeps_hold(){
 for(unsigned variant=0;variant<2;variant++){
  auto c=requested();Store s;if(variant==0)s.live.checksum++;else{s.live.revision=UINT32_MAX;hike::seal(s.live);}
  TEST_ASSERT_FALSE(c.save(poweroff::OtaPhase::None,false,s));TEST_ASSERT_EQUAL(poweroff::State::Failed,c.state());
 }
}
void every_save_failure_keeps_hold_and_stops_later_actions(){
 for(unsigned variant=0;variant<4;variant++){
  auto c=requested();Store s;s.failData=variant==0;s.failWrite=variant==1;s.corruptRead=variant==2;s.failLog=variant==3;
  TEST_ASSERT_FALSE(c.save(poweroff::OtaPhase::None,false,s));TEST_ASSERT_EQUAL(poweroff::State::Failed,c.state());
  TEST_ASSERT_EQUAL(variant==0?1:variant==3?3:2,s.calls.size());
  c.observe(true,100);c.observe(true,150);TEST_ASSERT_TRUE(c.busy()); // ON cannot discard a saving error.
 }
}
void receiving_must_abort_before_data_and_failure_never_releases(){
 auto c=requested();Store s;TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::Receiving,false,s));
 TEST_ASSERT_TRUE(s.inactiveAborted);TEST_ASSERT_EQUAL(1,s.calls.front());
 auto other=requested();Store failed;failed.failAbort=true;
 TEST_ASSERT_FALSE(other.save(poweroff::OtaPhase::Receiving,false,failed));TEST_ASSERT_EQUAL(1,failed.calls.size());
}
void flash_critical_section_defers_shutdown(){
 auto c=requested();Store s;
 TEST_ASSERT_FALSE(c.save(poweroff::OtaPhase::SlotSelected,true,s));TEST_ASSERT_TRUE(s.calls.empty());
 TEST_ASSERT_EQUAL(poweroff::State::Requested,c.state());
 TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::SlotSelected,false,s));
}
void selected_or_trial_image_is_not_aborted_or_confirmed(){
 for(auto phase:{poweroff::OtaPhase::SlotSelected,poweroff::OtaPhase::TrialBoot,poweroff::OtaPhase::Valid}){
  auto c=requested();Store s;TEST_ASSERT_TRUE(c.save(phase,false,s));
  TEST_ASSERT_FALSE(s.inactiveAborted);TEST_ASSERT_EQUAL(2,s.calls.front());
 }
}
void on_returning_during_shutdown_does_not_release_or_resume(){
 auto c=requested();c.observe(true,52);c.observe(true,102);Store s;
 TEST_ASSERT_FALSE(c.save(poweroff::OtaPhase::None,false,s));TEST_ASSERT_FALSE(c.busy());
 TEST_ASSERT_EQUAL(hike::RECOVERY,s.live.phase);
 c.observe(false,110);c.observe(false,160);TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::None,false,s));
 c.observe(true,161);c.observe(true,211);TEST_ASSERT_FALSE(c.busy());TEST_ASSERT_EQUAL(hike::RECOVERY,s.live.phase);
}
void repeated_off_does_not_write_or_cut_twice(){
 auto c=requested();Store s;TEST_ASSERT_TRUE(c.save(poweroff::OtaPhase::None,false,s));const auto calls=s.calls.size();
 TEST_ASSERT_FALSE(c.save(poweroff::OtaPhase::None,false,s));TEST_ASSERT_EQUAL(calls,s.calls.size());
}
int main(){UNITY_BEGIN();
 RUN_TEST(primary_profile_is_slider_legacy_gesture_requires_explicit_selection);RUN_TEST(off_bounce_and_timer_wrap);
 RUN_TEST(boot_with_switch_off_cannot_start_work);RUN_TEST(off_saves_same_hike_and_next_boot_asks_resume);
 RUN_TEST(finished_history_and_idle_are_not_reopened);RUN_TEST(corrupt_session_or_revision_overflow_keeps_hold);
 RUN_TEST(every_save_failure_keeps_hold_and_stops_later_actions);RUN_TEST(receiving_must_abort_before_data_and_failure_never_releases);
 RUN_TEST(flash_critical_section_defers_shutdown);RUN_TEST(selected_or_trial_image_is_not_aborted_or_confirmed);
 RUN_TEST(on_returning_during_shutdown_does_not_release_or_resume);RUN_TEST(repeated_off_does_not_write_or_cut_twice);
 return UNITY_END();}
