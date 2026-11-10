#include <unity.h>
#include "hike_state.h"
#include "hike_menu.h"

void setUp() {}
void tearDown() {}
using hike::State;
using screeninput::Button;
State idle() { State s; hike::seal(s); return s; }
State active() {
  auto s=idle(); State n;
  hike::transition(s,hike::START,0,1,11,100,50,n);
  return n;
}
void finish_is_separate_from_start_and_idempotent() {
  auto s=active(); State n;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(s,hike::FINISH,100,2,12,0,200,n));
  TEST_ASSERT_EQUAL(hike::ENDED,n.phase); TEST_ASSERT_EQUAL(100,n.id); TEST_ASSERT_EQUAL(200,n.endedAt);
  State retry;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(n,hike::FINISH,100,2,12,0,300,retry));
  TEST_ASSERT_EQUAL_MEMORY(&n,&retry,sizeof(n));
  TEST_ASSERT_EQUAL(proto::ACK_BUSY,hike::transition(n,hike::RESUME,100,3,13,0,300,retry));
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(n,hike::START,100,3,13,101,300,retry));
  TEST_ASSERT_EQUAL(101,retry.id); TEST_ASSERT_EQUAL(hike::ACTIVE,retry.phase);
}
void concurrent_start_and_stale_end_cannot_change_another_hike() {
  auto s=idle(); State n,duplicate;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(s,hike::START,0,1,11,100,50,n));
  TEST_ASSERT_EQUAL(proto::ACK_BUSY,hike::transition(n,hike::START,0,1,22,101,50,duplicate));
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(n,hike::START,0,1,11,102,60,duplicate));
  TEST_ASSERT_EQUAL(100,duplicate.id);
  TEST_ASSERT_EQUAL(proto::ACK_BUSY,hike::transition(n,hike::FINISH,99,2,23,0,60,duplicate));
}
void sleep_preserves_active_but_reset_requires_a_decision() {
  auto s=active(); auto warm=hike::afterBoot(s,false);
  TEST_ASSERT_EQUAL_MEMORY(&s,&warm,sizeof(s));
  auto cold=hike::afterBoot(s,true);
  TEST_ASSERT_EQUAL(hike::RECOVERY,cold.phase); TEST_ASSERT_EQUAL(s.id,cold.id);
  State resumed;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(cold,hike::RESUME,cold.id,cold.revision,90,0,400,resumed));
  TEST_ASSERT_EQUAL(hike::ACTIVE,resumed.phase); TEST_ASSERT_EQUAL(s.id,resumed.id);
  State ended;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(cold,hike::FINISH,cold.id,cold.revision,91,0,400,ended));
  TEST_ASSERT_EQUAL(hike::ENDED,hike::afterBoot(ended,true).phase);
}
void commit_failure_does_not_publish_and_reboot_uses_durable_record() {
  auto s=active(); State ended;
  hike::transition(s,hike::FINISH,s.id,s.revision,42,0,500,ended);
  State disk=s;
  auto read=[&](State& out){out=disk;return true;};
  TEST_ASSERT_FALSE(hike::commit(s,ended,[](const State&){return false;},read));
  TEST_ASSERT_EQUAL(hike::ACTIVE,s.phase);
  TEST_ASSERT_EQUAL(hike::RECOVERY,hike::afterBoot(disk,true).phase);
  // Commit reached NVS, but power/response was lost before the caller could publish.
  auto write=[&](const State& in){disk=in;return true;};
  TEST_ASSERT_FALSE(hike::commit(s,ended,write,[](State&){return false;}));
  TEST_ASSERT_EQUAL(hike::ACTIVE,s.phase);
  auto boot=hike::afterBoot(disk,true);
  TEST_ASSERT_EQUAL(hike::ENDED,boot.phase);
  State retried;
  TEST_ASSERT_EQUAL(proto::ACK_OK,hike::transition(boot,hike::FINISH,100,2,42,0,600,retried));
  TEST_ASSERT_EQUAL(500,retried.endedAt);
}
void corrupt_metadata_is_not_an_empty_hike() {
  auto s=active(); s.id++;
  TEST_ASSERT_FALSE(hike::valid(s));
  TEST_ASSERT_FALSE(hike::replay(s,s.action,s.expectedId,s.expectedRevision,s.operation));
  TEST_ASSERT_EQUAL(hike::ERROR,hike::afterBoot(s,true).phase);
  State n;
  TEST_ASSERT_EQUAL(proto::ACK_BAD_ARGS,hike::transition(s,hike::START,s.id,s.revision,9,200,0,n));
}
void menu_requires_a_new_hold_and_allows_cancel() {
  hike::Menu menu;
  TEST_ASSERT_EQUAL(Button::NONE,menu.input(Button::B_LONG,hike::ACTIVE));
  TEST_ASSERT_FALSE(menu.confirming);
  menu.input(Button::B_SHORT,hike::ACTIVE);
  TEST_ASSERT_TRUE(menu.confirming);
  menu.input(Button::A_SHORT,hike::ACTIVE);
  TEST_ASSERT_FALSE(menu.confirming);
  menu.input(Button::B_SHORT,hike::ACTIVE);
  TEST_ASSERT_EQUAL(Button::HIKE_FINISH,menu.input(Button::B_LONG,hike::ACTIVE));
  menu.input(Button::B_SHORT,hike::ACTIVE); menu.reset(); // screen timeout / revision changed
  TEST_ASSERT_EQUAL(Button::NONE,menu.input(Button::B_LONG,hike::ACTIVE));
}
void lifecycle_wire_vectors_and_reject_truncation() {
  const uint8_t command[]={2,0x1c,2,100,0,0,0,7,0,0,0,44,0,0,0};
  proto::Command c{};
  TEST_ASSERT_EQUAL(proto::ACK_OK,proto::decodeCommand(command,sizeof(command),c));
  TEST_ASSERT_EQUAL(2,c.flag); TEST_ASSERT_EQUAL(100,c.time); TEST_ASSERT_EQUAL(7,c.revision); TEST_ASSERT_EQUAL(44,c.operation);
  for (size_t n=2;n<sizeof(command);n++) TEST_ASSERT_NOT_EQUAL(proto::ACK_OK,proto::decodeCommand(command,n,c));
  uint8_t bytes[20];
  const uint8_t expected[]={2,0xb,3,255,8,0,0,0,100,0,0,0,200,0,0,0,44,0,0,0};
  TEST_ASSERT_EQUAL(20,proto::encodeHikeState(3,255,8,100,200,44,bytes,sizeof(bytes)));
  TEST_ASSERT_EQUAL_MEMORY(expected,bytes,20);
}
int main(int,char**) {
  UNITY_BEGIN();
  RUN_TEST(finish_is_separate_from_start_and_idempotent);
  RUN_TEST(concurrent_start_and_stale_end_cannot_change_another_hike);
  RUN_TEST(sleep_preserves_active_but_reset_requires_a_decision);
  RUN_TEST(commit_failure_does_not_publish_and_reboot_uses_durable_record);
  RUN_TEST(corrupt_metadata_is_not_an_empty_hike);
  RUN_TEST(menu_requires_a_new_hold_and_allows_cancel);
  RUN_TEST(lifecycle_wire_vectors_and_reject_truncation);
  return UNITY_END();
}
