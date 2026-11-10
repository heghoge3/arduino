#pragma once
#include <stdint.h>
#include "nmea.h"
namespace ds3231time {
inline bool bcd(uint8_t x) { return (x&15)<10 && (x>>4)<10; }
inline uint8_t bin(uint8_t x) { return (x>>4)*10+(x&15); }
inline uint8_t packed(uint8_t x) { return ((x/10)<<4)|(x%10); }
// DS3231 00h..06h, UTC, 2000..2099. Reject impossible dates and reserved bits.
inline bool decode(const uint8_t* r,uint32_t& utc) {
  if ((r[0]&0x80)||(r[1]&0x80)||(r[2]&0x80)||(r[3]&0xF8)||
      (r[4]&0xC0)||(r[5]&0xE0)||!bcd(r[0])||!bcd(r[1])||
      !bcd(r[2]&0x1F)||!bcd(r[4])||!bcd(r[5])||!bcd(r[6])) return false;
  unsigned hour;
  if (r[2]&0x40) {
    unsigned h=bin(r[2]&0x1F); if(h<1||h>12)return false;
    hour=h%12+((r[2]&0x20)?12:0);
  } else { if(!bcd(r[2]&0x3F))return false; hour=bin(r[2]&0x3F); }
  const unsigned sec=bin(r[0]),min=bin(r[1]),day=bin(r[4]),mon=bin(r[5]),year=2000+bin(r[6]);
  if(sec>59||min>59||hour>23||r[3]<1||r[3]>7||mon<1||mon>12||day<1)return false;
  static const uint8_t days[]={31,28,31,30,31,30,31,31,30,31,30,31};
  if(day>days[mon-1]+(mon==2&&year%4==0))return false;
  utc=nmea::toUnix(year,mon,day,hour,min,sec);return true;
}
}
