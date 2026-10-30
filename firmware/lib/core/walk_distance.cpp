#include "walk_distance.h"
#include "spoof.h"
#include <math.h>
namespace walking {
void Distance::reset(uint32_t steps,double meters) { *this=Distance(); steps_=steps; meters_=isfinite(meters)&&meters>=0?meters:0; }
void Distance::update(uint32_t now,uint32_t steps,bool recording,bool gps,double lat,double lon,float hdop,float stride) {
  uint32_t count=steps>=steps_ ? steps-steps_ : 0;
  steps_=steps;
  if (!recording) { haveGps_=false; gap_=true; time_=now; return; }
  if (!isfinite(stride)||stride<.3f||stride>1.5f) stride=.75f;
  bool validGps=gps&&isfinite(lat)&&isfinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;
  if (!validGps || gap_) meters_+=count*stride;
  if (validGps) {
    if (haveGps_&&!gap_) {
      double d=spoof::distanceM(lat_,lon_,lat,lon);
      if (d>fmaxf(5,isfinite(hdop)?hdop*3:5)) {
        if (!time_ || now<time_ || now-time_<=300 || d<=200) meters_+=d;
        lat_=lat; lon_=lon;
      }
    } else { lat_=lat; lon_=lon; }
    haveGps_=true; gap_=false; time_=now;
  } else gap_=true;
}
}
