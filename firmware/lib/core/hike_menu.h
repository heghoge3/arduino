#pragma once
#include "hike_state.h"
#include "screen_input.h"

namespace hike {
class Menu {
 public:
  uint8_t selected=0;
  bool confirming=false;
  void reset() { selected=0; confirming=false; }
  uint8_t count(Phase phase) const { return phase==RECOVERY ? 4 : (phase==ERROR ? 2 : 3); }
  // 0=data, 4=pairing, otherwise lifecycle Action.
  uint8_t action(Phase phase) const {
    if (selected==count(phase)-1) return 4;
    if (selected==count(phase)-2) return 0;
    if (phase==RECOVERY) return selected==0 ? RESUME : FINISH;
    return phase==ACTIVE ? FINISH : START;
  }
  screeninput::Button input(screeninput::Button e,Phase phase) {
    using screeninput::Button;
    if (confirming) {
      if (e==Button::A_SHORT || e==Button::A_LONG) confirming=false;
      else if (e==Button::B_LONG) {
        confirming=false;
        return action(phase)==START ? Button::HIKE_START :
            (action(phase)==FINISH ? Button::HIKE_FINISH : Button::HIKE_RESUME);
      }
    } else {
      if (e==Button::A_SHORT) selected=(selected+1)%count(phase);
      else if (e==Button::B_SHORT && action(phase)>=1 && action(phase)<=3) confirming=true;
    }
    return Button::NONE;
  }
};
} // namespace hike
