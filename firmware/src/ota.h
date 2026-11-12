#pragma once
#include <stddef.h>
#include <stdint.h>
#include "power_off.h"
namespace ota {
struct Conditions {bool idle=false,storage=false,powerKnown=false,on=false;float voltage=0;uint8_t percent=0;};
void earlyBoot();
void begin();
void poll(const Conditions& c,bool healthy);
bool handle(const uint8_t* p,size_t n,const Conditions& c);
bool exclusive();
bool abortForOff();
poweroff::OtaPhase offPhase();
bool provision(); // USB console only, explicit physical V2 commissioning.
}
