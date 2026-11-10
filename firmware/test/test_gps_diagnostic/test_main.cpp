#include <unity.h>
#include "gps_diagnostic.h"
#include "nmea.h"
void setUp(){}void tearDown(){}
void replay_preserves_blacklist_and_phone_time(){
 spoof::Detector d;double lat[]={60.1161},lon[]={31.3864};uint16_t radius[]={5000};d.setBlacklist(lat,lon,radius,1);
 d.setPhone(59,29,20,2000);spoof::Input i{};i.now=2000;i.lat=lat[0];i.lon=lon[0];i.timeTrusted=true;i.gpsTimeValid=true;i.gpsTime=1460;
 uint8_t snr[]={40,40,40,40,40};i.usedSnr=snr;i.usedCount=5;
 auto before=d;auto result=d.check(i);spoof::Diagnostic::Meta m;m.evaluated=true;
 uint8_t bytes[1200];size_t n=spoof::Diagnostic::encode(before,i,m,result,bytes,sizeof(bytes));TEST_ASSERT_GREATER_THAN(0,n);
 spoof::Diagnostic::Record r;TEST_ASSERT_TRUE(spoof::Diagnostic::decode(bytes,n,r));
 auto actual=r.before.check(r.input);TEST_ASSERT_EQUAL(result.spoof,actual.spoof);TEST_ASSERT_EQUAL(result.mask,actual.mask);
 TEST_ASSERT_EQUAL(5,r.input.usedCount);TEST_ASSERT_EQUAL(40,r.input.usedSnr[4]);
 for(size_t size=0;size<n;size++)TEST_ASSERT_FALSE(spoof::Diagnostic::decode(bytes,size,r));
 bytes[n-1]^=1;TEST_ASSERT_FALSE(spoof::Diagnostic::decode(bytes,n,r));
}
void replay_preserves_recovery_progress(){
 spoof::Detector d;spoof::Input i{};i.now=2000;i.lat=59;i.lon=29;d.check(i);
 i.now=2030;i.lon=29.1;d.check(i);i.now=2301;d.check(i);i.now=2305;
 auto before=d;auto expected=d.check(i);uint8_t b[1200];spoof::Diagnostic::Meta m;m.evaluated=true;
 auto n=spoof::Diagnostic::encode(before,i,m,expected,b,sizeof(b));spoof::Diagnostic::Record r;
 TEST_ASSERT_TRUE(spoof::Diagnostic::decode(b,n,r));auto actual=r.before.check(r.input);
 TEST_ASSERT_EQUAL(expected.mask,actual.mask);TEST_ASSERT_EQUAL(expected.spoof,actual.spoof);
}
void nmea_receive_age_does_not_use_utc(){
 nmea::Parser p;char line[150];TEST_ASSERT_EQUAL(UINT32_MAX,p.messageAge(0,10));
 nmea::buildCommand("GNRMC,120000.000,A,5900.0000,N,02900.0000,E,0,0,111126,,,A",line,sizeof(line));
 p.parseLine(line,0xfffffff0);TEST_ASSERT_EQUAL(32,p.messageAge(0,16));TEST_ASSERT_EQUAL(UINT32_MAX,p.messageAge(1,16));
 line[10]^=1;p.parseLine(line,50);TEST_ASSERT_EQUAL(66,p.messageAge(0,50));
 p.reset();TEST_ASSERT_EQUAL(UINT32_MAX,p.messageAge(0,60));
}
void parse(nmea::Parser& p,const char* body,uint32_t ms){char line[150];nmea::buildCommand(body,line,sizeof(line));p.parseLine(line,ms);}
void stale_and_unmatched_quality_is_not_current(){
 nmea::Parser p;
 parse(p,"GNRMC,120000.000,A,5900.0000,N,02900.0000,E,0,0,111126,,,A",100);
 parse(p,"GNGGA,120000.000,5900.0000,N,02900.0000,E,1,08,0.8,70,M,0,M,,",100);
 TEST_ASSERT_TRUE(p.qualityFresh(200));TEST_ASSERT_FALSE(p.qualityFresh(2601));
 parse(p,"GNRMC,120005.000,A,5900.0000,N,02900.0000,E,0,0,111126,,,A",5000);
 TEST_ASSERT_FALSE(p.qualityFresh(5000));
 parse(p,"GNGGA,120000.000,5900.0000,N,02900.0000,E,1,08,0.8,70,M,0,M,,",5000);
 TEST_ASSERT_FALSE(p.qualityFresh(5000));
 parse(p,"GNGGA,120005.000,5900.0000,N,02900.0000,E,1,08,0.8,70,M,0,M,,",5000);
 TEST_ASSERT_TRUE(p.qualityFresh(5000));parse(p,"GNGGA,120005",5001);TEST_ASSERT_FALSE(p.qualityFresh(5001));
 parse(p,"GNRMC,000000.000,A,5900.0000,N,02900.0000,E,0,0,121126,,,A",6000);
 parse(p,"GNGGA,235959.000,5900.0000,N,02900.0000,E,1,08,0.8,70,M,0,M,,",6000);
 TEST_ASSERT_TRUE(p.qualityFresh(6000));
}
void snr_requires_fresh_same_system_and_complete_group(){
 nmea::Parser p;
 const char* gsa="GPGSA,A,3,03,06,09,12,15,18,,,,,,,1.6,0.8,1.3,1";
 const char* first="GPGSV,2,1,06,03,40,100,41,06,40,100,42,09,40,100,41,12,40,100,42,0";
 const char* last="GPGSV,2,2,06,15,40,100,41,18,40,100,42,0";
 parse(p,gsa,0);parse(p,first,10);TEST_ASSERT_FALSE(p.snrFresh(0,10));
 parse(p,last,11);TEST_ASSERT_TRUE(p.snrFresh(0,12));
 parse(p,"BDGSA,A,3,03,06,09,12,15,18,,,,,,,1.6,0.8,1.3,4",3001);
 TEST_ASSERT_FALSE(p.snrFresh(0,3001));parse(p,gsa,3001);TEST_ASSERT_TRUE(p.snrFresh(0,3001));
 parse(p,last,3002);TEST_ASSERT_FALSE(p.snrFresh(0,3002));
 parse(p,first,3003);parse(p,last,3004);TEST_ASSERT_TRUE(p.snrFresh(0,3004));
 TEST_ASSERT_FALSE(p.snrFresh(0,14005));
}
int main(){UNITY_BEGIN();RUN_TEST(replay_preserves_blacklist_and_phone_time);RUN_TEST(replay_preserves_recovery_progress);RUN_TEST(nmea_receive_age_does_not_use_utc);RUN_TEST(stale_and_unmatched_quality_is_not_current);RUN_TEST(snr_requires_fresh_same_system_and_complete_group);return UNITY_END();}
