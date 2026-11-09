#include "blacklist_defaults.h"
#include <stdlib.h>
#include <ctype.h>
#include <math.h>
namespace blacklistdefaults {
static void spaces(const char*& p) { while(isspace((unsigned char)*p)) p++; }
bool parse(const char* p,proto::BlEntry* out,uint8_t& count) {
  count=0;
  if(!p||!out) return false;
  spaces(p); if(*p++!='[') return false;
  spaces(p); if(*p==']') {p++;spaces(p);return !*p;}
  while(count<proto::MAX_BLACKLIST) {
    spaces(p);if(*p++!='[') return false;
    char* end;
    double lat=strtod(p,&end);if(end==p) return false;p=end;
    spaces(p);if(*p++!=',') return false;
    double lon=strtod(p,&end);if(end==p) return false;p=end;
    spaces(p);if(*p++!=',') return false;
    long radius=strtol(p,&end,10);if(end==p) return false;p=end;
    if(!isfinite(lat)||!isfinite(lon)||lat < -90||lat>90||lon < -180||lon>180||radius<100||radius>50000) return false;
    spaces(p);if(*p++!=']') return false;
    out[count++]={(int32_t)lround(lat*1e7),(int32_t)lround(lon*1e7),(uint16_t)radius};
    spaces(p);
    if(*p==']') {p++;spaces(p);return !*p;}
    if(*p++!=',') return false;
  }
  return false;
}
}
