#pragma once
#include <stdint.h>
#include "hike_state.h"

namespace poweroff {
enum class Profile : uint8_t { Gesture=0, Slider=1 };
// Future OTA adapter contract, not an OTA receiver or a claim about flash contents.
enum class OtaPhase : uint8_t { None, Receiving, SlotSelected, TrialBoot, Valid };
enum class State : uint8_t { Running, Requested, Failed, Released };

inline bool sessionForOff(const hike::State& current,hike::State& saved) {
  saved=hike::afterBoot(current,true); // ACTIVE -> RECOVERY, never FINISH or a new ID.
  return hike::valid(saved);
}

// Once OFF is accepted, finish saving even if ON returns. A failed save never releases HOLD.
// All callbacks run on the main task. Never invoke this from a BLE/interrupt callback.
class Slider {
 public:
  static constexpr uint32_t DEBOUNCE_MS=50;
  void begin(bool on,uint32_t now) {
    stableOn_=rawOn_=on;changedAt_=now;
    state_=on?State::Running:State::Requested;
  }
  void observe(bool on,uint32_t now) {
    if(on!=rawOn_){rawOn_=on;changedAt_=now;}
    if(rawOn_==stableOn_ || uint32_t(now-changedAt_)<DEBOUNCE_MS)return;
    stableOn_=rawOn_;
    if(!stableOn_ && state_==State::Running)state_=State::Requested;
    else if(stableOn_ && state_==State::Released)state_=State::Running;
  }
  State state()const{return state_;}
  bool busy()const{return state_!=State::Running;}
  bool on()const{return stableOn_;}

  // Actions: abortInactive(), saveData(), saveSession(), flushLogs().
  // SlotSelected/TrialBoot must preserve selection; this code has no mark-valid operation.
  template<class Actions>
  bool save(OtaPhase ota,bool flashBusy,Actions& actions) {
    if(state_!=State::Requested || flashBusy)return false;
    if(ota==OtaPhase::Receiving && !actions.abortInactive())return fail();
    if(!actions.saveData() || !actions.saveSession() || !actions.flushLogs())return fail();
    // ON returning during saving must not cause a cut or restart recording.
    state_=stableOn_?State::Running:State::Released;
    return state_==State::Released;
  }
 private:
  bool fail(){state_=State::Failed;return false;}
  bool stableOn_=true,rawOn_=true;
  uint32_t changedAt_=0;
  State state_=State::Running;
};
}
