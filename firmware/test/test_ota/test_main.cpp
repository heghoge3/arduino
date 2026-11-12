#include <unity.h>
#include <vector>
#include <cstring>
#include "ota_transfer.h"
using namespace otawire;
void setUp(){}void tearDown(){}
Manifest candidate(){Manifest m;memcpy(m.bytes,"VBO1",4);m.bytes[4]=m.bytes[5]=m.bytes[6]=m.bytes[7]=1;put32(m.bytes+8,1);
 m.bytes[12]=m.bytes[13]=2;m.bytes[14]=1;put32(m.bytes+16,0x11e);m.bytes[21]=1;m.bytes[22]=30;put32(m.bytes+24,6);put32(m.bytes+28,0x11d);return m;}
struct Flash:Backend {
 std::vector<uint8_t> inactive,active{9,8,7};bool signatureOk=true,finishOk=true,writeOk=true,activateOk=true,abortOk=true,selected=false;
 unsigned starts=0,writes=0,aborts=0;
 bool signature(const Manifest&)override{return signatureOk;}
 bool start(const Manifest&)override{starts++;inactive.clear();return true;}
 bool write(const uint8_t* p,size_t n)override{writes++;if(!writeOk)return false;inactive.insert(inactive.end(),p,p+n);return true;}
 bool same(uint32_t at,const uint8_t* p,size_t n)override{return at+n<=inactive.size()&&!memcmp(inactive.data()+at,p,n);}
 bool finish(const Manifest&)override{return finishOk;}
 bool activate(uint32_t,const Manifest&)override{if(activateOk)selected=true;return activateOk;}
 bool abort()override{aborts++;return abortOk;}
};
void begin(Transfer& t,Flash& f){auto m=candidate();TEST_ASSERT_EQUAL(OK,t.begin(10,m.bytes,MANIFEST,0x11d,0x11d,true,1,f));}
void signed_manifest_checked_before_erasing(){
 for(unsigned kind=0;kind<6;kind++){Transfer t;Flash f;auto m=candidate();if(kind==0)m.bytes[5]=2;if(kind==1)m.bytes[7]=0;if(kind==2)put32(m.bytes+24,LIMIT+1);if(kind==3)f.signatureOk=false;
  auto r=t.begin(10,m.bytes,MANIFEST,kind==4?m.release():0x11d,0x11d,kind!=5,1,f);
  TEST_ASSERT_NOT_EQUAL(OK,r);TEST_ASSERT_EQUAL(0,f.starts);TEST_ASSERT_FALSE(f.selected);
 }
}
void chunks_are_acknowledged_once_duplicates_are_checked(){
 Transfer t;Flash f;begin(t,f);uint8_t b[]={1,2,3};auto crc=crc32(b,3);
 TEST_ASSERT_EQUAL(OK,t.data(10,0,crc,b,3,2,f));TEST_ASSERT_EQUAL(3,t.offset);
 TEST_ASSERT_EQUAL(OK,t.data(10,0,crc,b,3,3,f));TEST_ASSERT_EQUAL(1,f.writes);
 b[0]=9;TEST_ASSERT_EQUAL(OFFSET,t.data(10,0,crc32(b,3),b,3,4,f));TEST_ASSERT_EQUAL(1,f.writes);
 TEST_ASSERT_EQUAL(SESSION,t.data(11,3,crc32(b,3),b,3,5,f));TEST_ASSERT_EQUAL(BAD,t.data(10,3,0,b,3,6,f));
}
void disconnect_timeout_and_off_do_not_select_partial_image(){
 for(int kind=0;kind<3;kind++){Transfer t;Flash f;begin(t,f);uint8_t b[]={1,2,3};t.data(10,0,crc32(b,3),b,3,2,f);
  if(kind==0)t.tick(3,false,f);else if(kind==1)t.tick(30003,true,f);else t.cancel(f);
  TEST_ASSERT_FALSE(f.selected);TEST_ASSERT_EQUAL(1,f.aborts);TEST_ASSERT_EQUAL(IDLE,t.phase);TEST_ASSERT_EQUAL(3,f.active.size());TEST_ASSERT_EQUAL(9,f.active[0]);
  auto m=candidate();TEST_ASSERT_EQUAL(OK,t.begin(11,m.bytes,MANIFEST,0x11d,0x11d,true,40000,f));TEST_ASSERT_EQUAL(0,t.offset);
 }
}
void activation_requires_complete_verified_image_and_is_idempotent(){
 Transfer t;Flash f;begin(t,f);TEST_ASSERT_EQUAL(OFFSET,t.finish(10,f));uint8_t b[]={1,2,3,4,5,6};
 t.data(10,0,crc32(b,6),b,6,2,f);TEST_ASSERT_EQUAL(OK,t.finish(10,f));TEST_ASSERT_TRUE(f.selected);
 TEST_ASSERT_EQUAL(OK,t.finish(10,f));TEST_ASSERT_FALSE(t.cancel(f));TEST_ASSERT_EQUAL(SELECTED,t.phase);TEST_ASSERT_EQUAL(0,f.aborts);
}
void corrupt_image_or_flash_write_never_activates(){
 for(int kind=0;kind<3;kind++){Transfer t;Flash f;begin(t,f);uint8_t b[]={1,2,3,4,5,6};
  f.writeOk=kind!=0;f.finishOk=kind!=1;f.activateOk=kind!=2;
  auto r=t.data(10,0,crc32(b,6),b,6,2,f);if(r==OK)r=t.finish(10,f);
  TEST_ASSERT_NOT_EQUAL(OK,r);TEST_ASSERT_FALSE(f.selected);TEST_ASSERT_EQUAL(FAILED,t.phase);
 }
}
void counters_replay_and_begin_retries_are_bound(){
 Transfer t;Flash f;begin(t,f);auto m=candidate();TEST_ASSERT_EQUAL(OK,t.begin(10,m.bytes,MANIFEST,0x11d,0x11d,true,2,f));TEST_ASSERT_EQUAL(1,f.starts);
 TEST_ASSERT_EQUAL(BUSY,t.begin(11,m.bytes,MANIFEST,0x11d,0x11d,true,2,f));t.cancel(f);
 TEST_ASSERT_EQUAL(COMPAT,t.begin(11,m.bytes,MANIFEST,0x11d,m.release(),true,2,f));
}
void image_length_includes_checksum_padding_and_sha(){
 std::vector<uint8_t> image(80);image[0]=0xe9;image[1]=1;image[23]=1;put32(image.data()+28,1);uint32_t n=0;
 auto read=[&](uint32_t at,uint8_t* p,size_t count){if(at+count>image.size())return false;memcpy(p,image.data()+at,count);return true;};
 TEST_ASSERT_TRUE(imageSize(read,image.size(),n));TEST_ASSERT_EQUAL(80,n);
 put32(image.data()+28,UINT32_MAX);TEST_ASSERT_FALSE(imageSize(read,image.size(),n));
}
void timer_wrap_and_stale_session_cannot_commit(){
 Transfer t;Flash f;auto m=candidate();TEST_ASSERT_EQUAL(OK,t.begin(5,m.bytes,MANIFEST,0x11d,0x11d,true,0xfffffff0u,f));
 t.tick(100,true,f);TEST_ASSERT_EQUAL(RECEIVING,t.phase);t.tick(0xfffffff0u+30001u,true,f);TEST_ASSERT_EQUAL(IDLE,t.phase);
 TEST_ASSERT_EQUAL(SESSION,t.finish(5,f));TEST_ASSERT_FALSE(f.selected);
}
int main(){UNITY_BEGIN();RUN_TEST(timer_wrap_and_stale_session_cannot_commit);RUN_TEST(signed_manifest_checked_before_erasing);RUN_TEST(chunks_are_acknowledged_once_duplicates_are_checked);RUN_TEST(disconnect_timeout_and_off_do_not_select_partial_image);RUN_TEST(activation_requires_complete_verified_image_and_is_idempotent);RUN_TEST(corrupt_image_or_flash_write_never_activates);RUN_TEST(counters_replay_and_begin_retries_are_bound);RUN_TEST(image_length_includes_checksum_padding_and_sha);return UNITY_END();}
