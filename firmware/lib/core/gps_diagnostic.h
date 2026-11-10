#pragma once
#include "spoof.h"
#include <string.h>
#include <math.h>
namespace spoof {
// Version 1, little-endian fields; no native pointers, object padding or timestamps in logs.
class Diagnostic {
 public:
  static constexpr size_t CAP=1200;
  struct Meta {
    uint32_t bootCount=0,hikeId=0,receiveMs=0,age[4]={UINT32_MAX,UINT32_MAX,UINT32_MAX,UINT32_MAX};
    float hdop=0;
    uint8_t sats=0,fixType=0,quality=0,settle=0,clockSource=0,firmwarePatch=25;
    uint8_t freshSnrSystems=0;
    bool qualityCurrent=false,evaluated=false;
  };
  struct Record { Detector before; Input input{}; uint8_t snr[32]{}; Meta meta; Result expected{}; };
 private:
  class Stream {
   public:
    uint8_t* data;size_t cap,at=0;bool reading,ok=true,modern=true;
    Stream(uint8_t* d,size_t n,bool r):data(d),cap(n),reading(r){}
    void field(uint8_t& v){if(at>=cap){ok=false;return;}if(reading)v=data[at++];else data[at++]=v;}
    void field(bool& v){uint8_t n=reading?0:(v?1:0);field(n);if(n>1)ok=false;if(reading)v=n!=0;}
    void field(uint16_t& v){uint32_t n=reading?0:v;word(n,2);if(reading)v=n;}
    void field(uint32_t& v){word(v,4);}
    void word(uint32_t& v,unsigned bytes){uint32_t n=0;for(unsigned i=0;i<bytes;i++){uint8_t b=reading?0:(v>>(8*i))&255;field(b);n|=uint32_t(b)<<(8*i);}if(reading)v=n;}
    void field(float& v){uint32_t n=0;if(!reading)memcpy(&n,&v,4);field(n);if(reading)memcpy(&v,&n,4);}
    void field(double& v){uint64_t n=0;if(!reading)memcpy(&n,&v,8);uint32_t lo=n,hi=n>>32;field(lo);field(hi);if(reading){n=uint64_t(lo)|(uint64_t(hi)<<32);memcpy(&v,&n,8);}}
  };
  static void config(Stream& s,Config& c){
    if(s.modern){s.field(c.policyVersion);s.field(c.transportSpeedMs);}else c.policyVersion=1;
    s.field(c.maxSpeedMs);s.field(c.jumpSlackM);s.field(c.phoneMinM);s.field(c.phoneFreshS);s.field(c.phoneMaxAccuracyM);
    s.field(c.snrSpreadDb);s.field(c.snrMinSats);s.field(c.baroDiffM);s.field(c.timeDiffS);
    s.field(c.accelWindowS);s.field(c.accelStillM);s.field(c.stepsWhileStill);s.field(c.lastGoodMaxAgeS);s.field(c.recoveryS);
  }
  static void state(Stream& s,Detector& d){
    config(s,d.cfg_);uint8_t n=d.blN_;s.field(n);if(n>MAX_BLACKLIST){s.ok=false;return;}if(s.reading)d.blN_=n;
    for(unsigned i=0;i<n;i++){s.field(d.blLat_[i]);s.field(d.blLon_[i]);s.field(d.blR_[i]);}
    s.field(d.phoneValid_);s.field(d.phoneLat_);s.field(d.phoneLon_);s.field(d.phoneAcc_);s.field(d.phoneTime_);
    s.field(d.lastGoodValid_);s.field(d.lastGoodLat_);s.field(d.lastGoodLon_);s.field(d.lastGoodTime_);
    s.field(d.stillValid_);s.field(d.stillLat_);s.field(d.stillLon_);s.field(d.stillTime_);s.field(d.stillSteps_);
    if(s.modern){s.field(d.phoneAgeMs_);s.field(d.phoneSource_);s.field(d.untrusted_);}
    s.field(d.episode_);s.field(d.cleanSince_);s.field(d.cleanLast_);s.field(d.cleanCount_);s.field(d.cleanLat_);s.field(d.cleanLon_);
  }
  static void input(Stream& s,Record& r){
    auto& i=r.input;if(s.modern)s.field(i.utcNow);s.field(i.now);s.field(i.timeTrusted);s.field(i.lat);s.field(i.lon);s.field(i.altValid);s.field(i.altGps);
    s.field(i.baroValid);s.field(i.altBaro);s.field(i.gpsTimeValid);s.field(i.gpsTime);s.field(i.steps);
    uint8_t count=i.usedCount;s.field(count);if(count>32){s.ok=false;return;}
    i.usedCount=count;for(unsigned n=0;n<count;n++)s.field(r.snr[n]);i.usedSnr=r.snr;
    s.field(r.meta.bootCount);s.field(r.meta.hikeId);s.field(r.meta.receiveMs);for(auto& age:r.meta.age)s.field(age);s.field(r.meta.hdop);
    s.field(r.meta.sats);s.field(r.meta.fixType);s.field(r.meta.quality);s.field(r.meta.settle);
    s.field(r.meta.clockSource);s.field(r.meta.firmwarePatch);s.field(r.meta.freshSnrSystems);s.field(r.meta.qualityCurrent);s.field(r.meta.evaluated);
    s.field(r.expected.spoof);s.field(r.expected.mask);if(s.modern){s.field(r.expected.uncertain);s.field(r.expected.segmentStart);}
  }
  static uint32_t hash(const uint8_t* p,size_t n){uint32_t v=2166136261u;for(size_t i=0;i<n;i++)v=(v^p[i])*16777619u;return v;}
 public:
  static size_t encode(const Detector& before,const Input& in,const Meta& meta,Result expected,uint8_t* out,size_t cap){
    if(in.usedCount>32 || (in.usedCount&&!in.usedSnr))return 0;
    Record r;r.before=before;r.input=in;r.meta=meta;r.expected=expected;
    if(in.usedCount)memcpy(r.snr,in.usedSnr,in.usedCount);
    Stream s(out,cap,false);uint32_t magic=0x32535047;uint16_t length=0;s.field(magic);s.field(length);
    state(s,r.before);input(s,r);if(!s.ok||s.at+4>cap)return 0;
    length=s.at+4;out[4]=length&255;out[5]=length>>8;
    uint32_t check=hash(out,s.at);s.field(check);return s.ok?s.at:0;
  }
  static bool decode(const uint8_t* data,size_t len,Record& r){
    if(len<10||len>CAP)return false;
    uint8_t copy[CAP];memcpy(copy,data,len);Stream s(copy,len,true);
    uint32_t magic;uint16_t length;s.field(magic);s.field(length);
    if((magic!=0x31535047&&magic!=0x32535047)||length!=len)return false;
    s.modern=magic==0x32535047;
    state(s,r.before);input(s,r);uint32_t check=0;s.field(check);
    return s.ok&&s.at==len&&check==hash(data,len-4);
  }
};
}
