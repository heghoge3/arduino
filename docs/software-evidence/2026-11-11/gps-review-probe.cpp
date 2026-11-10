#include "spoof.h"
#include <cstdio>
int main(){
 double la[]={60.0346,60.1161},lo[]={30.5693,31.3864};uint16_t r[]={5000,5000};
 spoof::Detector zone;zone.setBlacklist(la,lo,r,2);spoof::Input in{};in.now=2000;in.timeTrusted=true;in.lat=60.1161;in.lon=31.3864;
 auto z=zone.check(in);printf("zone_only: rejected=%d mask=0x%02x\n",z.spoof,z.mask);
 spoof::Detector road;in.lat=59.0;in.lon=29.0;road.check(in);
 unsigned blocked=0;for(unsigned dt=30;dt<=360;dt++){in.now=2000+dt;in.lon=29.0+(20.0*dt)/(111320.0*0.515038);auto q=road.check(in);if(dt>300&&q.spoof)blocked++;if(dt==30||dt==301||dt==360)printf("road_20mps_t%u: rejected=%d mask=0x%02x\n",dt,q.spoof,q.mask);}
 printf("road_rejected_after_anchor_expiry=%u/60\n",blocked);
 spoof::Detector advisory;advisory.setPhone(59,29,20,2000);in={};in.now=2000;in.timeTrusted=true;in.lat=60;in.lon=30;in.gpsTimeValid=true;in.gpsTime=1460;
 auto a=advisory.check(in);printf("phone_plus_540s_clock_mismatch: rejected=%d mask=0x%02x\n",a.spoof,a.mask);
 spoof::Detector weak;uint8_t snr[]={40,40,40,40,40};in={};in.now=2000;in.lat=59;in.lon=29;in.altValid=true;in.baroValid=true;in.altGps=150;in.altBaro=0;in.usedSnr=snr;in.usedCount=5;
 auto w=weak.check(in);printf("flat_snr_plus_baro_offset: rejected=%d mask=0x%02x\n",w.spoof,w.mask);
}
