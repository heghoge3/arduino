#include <unity.h>
#include "spoof.h"
#include "proto.h"
#include "gps_diagnostic.h"
#include "nmea.h"
#include "walk_distance.h"
#include <cstdio>
void setUp(){} void tearDown(){}
spoof::Input fix(uint32_t t,double lat=59,double lon=29){spoof::Input in{};in.now=t;in.utcNow=1800000000+t;in.lat=lat;in.lon=lon;return in;}
void real_zone_is_not_rejected(){
 spoof::Detector d;double la[]={60.1161},lo[]={31.3864};uint16_t rad[]={5000};d.setBlacklist(la,lo,rad,1);
 for(unsigned t=1;t<100;t++){auto r=d.check(fix(t,la[0],lo[0]));TEST_ASSERT_FALSE(r.spoof);TEST_ASSERT_BITS_HIGH(spoof::R_BLACKLIST,r.mask);}
 d.setPhone(la[0],lo[0],20,100);TEST_ASSERT_FALSE(d.check(fix(101,la[0],lo[0])).spoof);
}
void transit_gap_is_segment_not_spoof(){
 spoof::Detector d;d.check(fix(1));auto r=d.check(fix(31,59,29+600.0/57334));
 TEST_ASSERT_FALSE(r.spoof);TEST_ASSERT_TRUE(r.segmentStart);
 for(unsigned t=32;t<400;t++)TEST_ASSERT_FALSE(d.check(fix(t,59,29+20.0*(t-1)/57334)).spoof);
}
void stable_jump_never_becomes_verified_by_timeout(){
 spoof::Detector d;d.check(fix(1));for(unsigned t=2;t<500;t++){
 auto r=d.check(fix(t,60,30));TEST_ASSERT_TRUE(r.uncertain);TEST_ASSERT_FALSE(r.detected());}
}
void return_to_known_position_recovers(){
 spoof::Detector d;d.check(fix(1));d.check(fix(2,60,30));
 spoof::Result r;for(unsigned t=3;t<=14;t++)r=d.check(fix(t));
 TEST_ASSERT_FALSE(r.spoof);TEST_ASSERT_TRUE(d.hasLastGood());
}
void weak_pairs_and_clock_shift_are_context(){
 spoof::Detector d;auto in=fix(1);uint8_t snr[]={40,40,40,40,40};
 in.usedSnr=snr;in.usedCount=5;in.altValid=in.baroValid=true;in.altGps=150;in.altBaro=0;
 TEST_ASSERT_FALSE(d.check(in).spoof);d.shiftTime(540);in=fix(2);in.utcNow+=540;
 TEST_ASSERT_FALSE(d.check(in).spoof);
}
void fresh_network_corroborates_but_old_or_gps_does_not(){
 for(unsigned provider=1;provider<=2;provider++){
 spoof::Detector d;d.check(fix(1));d.check(fix(2,60,30));d.setPhone(60,30,20,3);d.setPhoneEvidence(0,provider);
 spoof::Result r;for(unsigned t=3;t<=14;t++)r=d.check(fix(t,60,30));
 TEST_ASSERT_EQUAL(provider!=1,r.uncertain);
 }
 spoof::Detector d;d.check(fix(1));d.check(fix(2,60,30));d.setPhone(60,30,20,3);d.setPhoneEvidence(30000,1);
 TEST_ASSERT_TRUE(d.check(fix(20,60,30)).uncertain);
}
void new_wire_flags_preserve_policy_and_boundaries(){
 proto::Point p{};p.policy2=true;p.breakBefore=true;p.uncertain=true;uint8_t bytes[proto::POINT_SIZE];
 TEST_ASSERT_EQUAL(proto::POINT_SIZE,proto::encodePoint(p,bytes,sizeof(bytes)));proto::Point out{};
 TEST_ASSERT_TRUE(proto::decodePoint(bytes,sizeof(bytes),out));TEST_ASSERT_TRUE(out.policy2&&out.breakBefore&&out.uncertain);
}
void old_diagnostics_replay_under_original_policy(){
 FILE* f=fopen("test/test_gps_policy/legacy-trace.bin","rb");TEST_ASSERT_NOT_NULL(f);
 uint8_t bytes[1200];TEST_ASSERT_EQUAL(6,fread(bytes,1,6,f));size_t n=bytes[4]|(bytes[5]<<8);
 TEST_ASSERT_LESS_OR_EQUAL(1200,n);TEST_ASSERT_EQUAL(n-6,fread(bytes+6,1,n-6,f));fclose(f);
 spoof::Diagnostic::Record record;TEST_ASSERT_TRUE(spoof::Diagnostic::decode(bytes,n,record));
 auto actual=record.before.check(record.input);TEST_ASSERT_TRUE(actual.spoof);TEST_ASSERT_EQUAL(record.expected.mask,actual.mask);
}
void malformed_nmea_does_not_reuse_old_time_or_coordinates(){
 nmea::Parser p;char line[150];
 const char* good="GNRMC,120000.000,A,5900.0000,N,02900.0000,E,0,0,111126,,,A";
 nmea::buildCommand(good,line,sizeof(line));p.parseLine(line,100);TEST_ASSERT_TRUE(p.fix().timeValid);
 nmea::buildCommand("GNRMC,120000.000,A,5900.0000,N,02900.0000,E,0,0,311126,,,A",line,sizeof(line));p.parseLine(line,200);
 TEST_ASSERT_FALSE(p.fix().timeValid);
 nmea::buildCommand("GNRMC,120000.000,A,5961.0000,N,02900.0000,E,0,0,111126,,,A",line,sizeof(line));p.parseLine(line,300);
 TEST_ASSERT_FALSE(p.fix().valid);
}
void explicit_manual_reference_resolves_pending_without_geometry_jump(){
 spoof::Detector d;d.check(fix(1));TEST_ASSERT_TRUE(d.check(fix(2,60,30)).uncertain);
 d.setManualReference(60,30,3);auto r=d.check(fix(4,60,30));TEST_ASSERT_FALSE(r.uncertain);TEST_ASSERT_FALSE(r.spoof);
}
void segment_boundary_preserves_step_distance_without_chord(){
 walking::Distance d;d.reset();d.update(1,0,true,true,59,29,1,.75);
 d.breakGeometry();d.update(2,10,true,false,0,0,1,.75);
 d.breakGeometry();d.update(3,20,true,true,60,30,1,.75);
 TEST_ASSERT_DOUBLE_WITHIN(.001,15,d.meters());
}
void repeated_fixes_in_one_second_do_not_restart_recovery_or_inflate_evidence(){
 spoof::Detector d;d.check(fix(1));d.check(fix(2,60,30));
 d.setPhone(60,30,20,3);d.setPhoneEvidence(0,1);
 for(unsigned n=0;n<100;n++)TEST_ASSERT_TRUE(d.check(fix(3,60,30)).uncertain);
 spoof::Result r;for(unsigned t=4;t<=15;t++)for(unsigned n=0;n<5;n++)r=d.check(fix(t,60,30));
 TEST_ASSERT_FALSE(r.uncertain);TEST_ASSERT_FALSE(r.spoof);
}
int main(){UNITY_BEGIN();RUN_TEST(repeated_fixes_in_one_second_do_not_restart_recovery_or_inflate_evidence);RUN_TEST(segment_boundary_preserves_step_distance_without_chord);RUN_TEST(old_diagnostics_replay_under_original_policy);RUN_TEST(malformed_nmea_does_not_reuse_old_time_or_coordinates);RUN_TEST(explicit_manual_reference_resolves_pending_without_geometry_jump);RUN_TEST(real_zone_is_not_rejected);RUN_TEST(transit_gap_is_segment_not_spoof);RUN_TEST(stable_jump_never_becomes_verified_by_timeout);RUN_TEST(return_to_known_position_recovers);RUN_TEST(weak_pairs_and_clock_shift_are_context);RUN_TEST(fresh_network_corroborates_but_old_or_gps_does_not);RUN_TEST(new_wire_flags_preserve_policy_and_boundaries);return UNITY_END();}
