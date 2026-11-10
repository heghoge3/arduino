#pragma once
#include <stdint.h>
namespace powergesture {
// Stable, simultaneous A+B hold; an interrupted gesture restarts from zero.
class Start {
 bool holding=false,accepted=false;
 uint32_t since=0;
 public:
 uint8_t tick(bool a,bool b,uint32_t ms) {
   if(accepted)return 4;
   if(!a||!b){holding=false;return 0;}
   if(!holding){holding=true;since=ms;}
   uint32_t elapsed=ms-since;
   if(elapsed>=4000){accepted=true;return 4;}
   return elapsed/1000;
 }
 bool done()const{return accepted;}
};
}
