#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
namespace otawire {
constexpr uint8_t QUERY=0x30,BEGIN=0x31,DATA=0x32,FINISH=0x33,ABORT=0x34,REBOOT=0x35;
constexpr size_t BODY=72,MANIFEST=136,CHUNK=224,STATUS=143;
constexpr uint32_t LAYOUT=1,LIMIT=0x140000;
// Revision 1 used the former active-HIGH backlight contract. AO3401 is active-LOW.
constexpr uint8_t WIRING_REVISION=2;
enum Result:uint8_t {OK=0,BAD=1,BUSY=2,SIGNATURE=3,COMPAT=4,IO=5,POWER=6,SESSION=7,OFFSET=8,HASH=9,TIMEOUT=10,CANCELLED=11};
enum Phase:uint8_t {IDLE=0,RECEIVING=1,SELECTED=2,TRIAL=3,VALID=4,FAILED=5};
inline uint32_t get32(const uint8_t* p){return uint32_t(p[0])|uint32_t(p[1])<<8|uint32_t(p[2])<<16|uint32_t(p[3])<<24;}
inline void put32(uint8_t* p,uint32_t n){for(int i=0;i<4;i++)p[i]=n>>(i*8);}
inline uint32_t crc32(const uint8_t* p,size_t n){uint32_t c=~0u;for(size_t i=0;i<n;i++){c^=p[i];for(int j=0;j<8;j++)c=(c>>1)^((c&1)?0xedb88320u:0);}return ~c;}
template<class Read> bool imageSize(Read read,uint32_t limit,uint32_t& size){
 uint8_t header[24];if(limit<24||!read(0,header,24)||header[0]!=0xe9||!header[1]||header[1]>16||header[23]>1)return false;
 uint32_t at=24;for(unsigned i=0;i<header[1];i++){
  uint8_t segment[8];if(at>limit||limit-at<8||!read(at,segment,8))return false;
  uint32_t n=get32(segment+4);at+=8;if(n>limit-at)return false;at+=n;
 }
 if(at>limit||limit-at<16)return false;
 uint32_t end=(at+16)&~15u;
 if(header[23]){if(end>limit||limit-end<32)return false;end+=32;}
 if(end>limit)return false;size=end;return true;
}
struct Manifest {
 uint8_t bytes[MANIFEST]{};
 uint32_t release()const{return get32(bytes+16);}
 uint32_t size()const{return get32(bytes+24);}
 const uint8_t* hash()const{return bytes+32;}
 bool parse(const uint8_t* p,size_t n){
  if(n!=MANIFEST)return false;memcpy(bytes,p,n);
  return !memcmp(p,"VBO1",4)&&p[4]==1&&p[5]==1&&p[6]==WIRING_REVISION&&p[7]==1&&get32(p+8)==LAYOUT&&
    p[12]==2&&p[13]==2&&p[14]==1&&!p[15]&&!p[23]&&size()>0&&size()<=LIMIT&&
    release()==(uint32_t(p[20])<<16|uint32_t(p[21])<<8|p[22])&&get32(p+28)<=release();
 }
 void metadata(uint8_t* p)const{
  memcpy(p,"VBMETA1",8);p[8]=bytes[5];p[9]=bytes[6];p[10]=bytes[7];p[11]=2;
  memcpy(p+12,bytes+8,4);memcpy(p+16,bytes+16,4);memcpy(p+20,bytes+20,3);p[23]=0;
 }
};
struct Backend {
 virtual ~Backend()=default;
 virtual bool signature(const Manifest&)=0;
 virtual bool start(const Manifest&)=0;
 virtual bool write(const uint8_t*,size_t)=0;
 virtual bool same(uint32_t,const uint8_t*,size_t)=0;
 virtual bool finish(const Manifest&)=0;
 virtual bool activate(uint32_t,const Manifest&)=0;
 virtual bool abort()=0;
};
class Transfer {
 public:
 Phase phase=IDLE;Result result=OK;uint32_t operation=0,offset=0,lastAt=0;
 Manifest manifest;
 Result begin(uint32_t op,const uint8_t* p,size_t n,uint32_t current,uint32_t highest,bool eligible,uint32_t now,Backend& b){
  if(phase==SELECTED)return result=BUSY;
  if(phase==RECEIVING){if(op==operation&&n==MANIFEST&&!memcmp(manifest.bytes,p,n)){lastAt=now;return result=OK;}return result=BUSY;}
  Manifest candidate;if(!op||!candidate.parse(p,n))return result=BAD;
  if(!b.signature(candidate))return result=SIGNATURE;
  if(candidate.release()<=current||candidate.release()<=highest||get32(candidate.bytes+28)>current)return result=COMPAT;
  if(!eligible)return result=BUSY;
  if(!b.start(candidate))return result=IO;
  manifest=candidate;operation=op;offset=lastSize_=0;lastAt=now;phase=RECEIVING;return result=OK;
 }
 Result data(uint32_t op,uint32_t at,uint32_t crc,const uint8_t* p,size_t n,uint32_t now,Backend& b){
  if(phase!=RECEIVING||op!=operation)return result=SESSION;
  if(!n||n>CHUNK||at>manifest.size()||n>manifest.size()-at||crc32(p,n)!=crc)return result=BAD;
  if(at!=offset){
   if(lastSize_&&at==offset-lastSize_&&n==lastSize_&&b.same(at,p,n)){lastAt=now;return result=OK;}
   return result=OFFSET;
  }
  if(!b.write(p,n)){b.abort();phase=FAILED;return result=IO;}
  offset+=n;lastSize_=n;lastAt=now;return result=OK;
 }
 Result finish(uint32_t op,Backend& b){
  if(op==operation&&phase==SELECTED)return result=OK;
  if(phase!=RECEIVING||op!=operation)return result=SESSION;
  if(offset!=manifest.size())return result=OFFSET;
  if(!b.finish(manifest)){b.abort();phase=FAILED;return result=HASH;}
  if(!b.activate(operation,manifest)){phase=FAILED;return result=IO;}
  phase=SELECTED;return result=OK;
 }
 bool cancel(Backend& b,Result why=CANCELLED){
  if(phase==SELECTED){result=BUSY;return false;}
  if(phase==RECEIVING&&!b.abort()){phase=FAILED;result=IO;return false;}
  phase=IDLE;offset=lastSize_=0;result=why;return true;
 }
 void tick(uint32_t now,bool connected,Backend& b){if(phase==RECEIVING&&(!connected||uint32_t(now-lastAt)>30000))cancel(b,connected?TIMEOUT:CANCELLED);}
 private:uint32_t lastSize_=0;
};
}
