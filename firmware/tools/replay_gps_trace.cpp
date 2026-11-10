#include "gps_diagnostic.h"
#include <cstdio>
#include <vector>
int main(int argc,char** argv){
 if(argc!=2){fprintf(stderr,"usage: gps-replay gnss-trace.bin\n");return 2;}
 FILE* file=fopen(argv[1],"rb");if(!file)return 2;
 unsigned count=0,skipped=0,mismatch=0;
 for(;;){
  uint8_t header[6];size_t n=fread(header,1,6,file);
  if(!n){if(ferror(file)){fclose(file);return 2;}break;}
  if(n!=6){fprintf(stderr,"truncated header after record %u\n",count);fclose(file);return 2;}
  size_t len=header[4]|(size_t(header[5])<<8);
  if(len<10||len>spoof::Diagnostic::CAP){fclose(file);return 2;}
  std::vector<uint8_t> data(len);for(unsigned i=0;i<6;i++)data[i]=header[i];
  if(fread(data.data()+6,1,len-6,file)!=len-6){fprintf(stderr,"truncated record %u\n",count+1);fclose(file);return 2;}
  spoof::Diagnostic::Record record;
  if(!spoof::Diagnostic::decode(data.data(),data.size(),record)){fprintf(stderr,"invalid record %u\n",count+1);fclose(file);return 2;}
  count++;
  if(!record.meta.evaluated){skipped++;continue;}
  auto actual=record.before.check(record.input);
  if(actual.spoof!=record.expected.spoof||actual.mask!=record.expected.mask||actual.uncertain!=record.expected.uncertain||actual.segmentStart!=record.expected.segmentStart)mismatch++;
 }
 fclose(file);printf("records=%u quality_skipped=%u decision_mismatches=%u\n",count,skipped,mismatch);
 return mismatch?1:0;
}
