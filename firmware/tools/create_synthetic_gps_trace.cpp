// Synthetic known-zone fixture only; contains no field recording.
#include "gps_diagnostic.h"
#include <cstdio>
int main(int argc,char** argv){if(argc!=2)return 2;FILE* f=fopen(argv[1],"wb");if(!f)return 2;
 spoof::Detector d;double la[]={60.1161},lo[]={31.3864};uint16_t radius[]={5000};d.setBlacklist(la,lo,radius,1);
 for(unsigned n=0;n<3;n++){spoof::Input in{};in.now=1800000000+n;in.lat=la[0];in.lon=lo[0];auto before=d;auto result=d.check(in);spoof::Diagnostic::Meta m;m.evaluated=true;m.qualityCurrent=true;m.quality=1;m.sats=8;m.hdop=1;uint8_t data[1200];auto size=spoof::Diagnostic::encode(before,in,m,result,data,sizeof(data));if(!size||fwrite(data,1,size,f)!=size)return 2;}
 fclose(f);return 0;}
