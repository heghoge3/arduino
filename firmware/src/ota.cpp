#include "ota.h"
#include "backlight.h"
#include "ota_transfer.h"
#include "../ota/public-key.h"
#include "config.h"
#include "power_profile.h"
#include "radio.h"
#include <Arduino.h>
#include <Preferences.h>
#include <esp_ota_ops.h>
#include <esp_image_format.h>
#include <esp_task_wdt.h>
#include <esp_sleep.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/sha256.h>

// Arduino 2.0.17 otherwise marks the image VALID before setup().
extern "C" bool verifyRollbackLater(){return true;}
namespace ota {
using namespace otawire;
namespace {
constexpr uint32_t release=(uint32_t(FW_MAJOR)<<16)|(uint32_t(FW_MINOR)<<8)|FW_PATCH;
#if defined(FAKE_SENSORS)
constexpr uint8_t buildFlags=1;
#else
constexpr uint8_t buildFlags=POWER_SLIDER_BENCH?2:0;
#endif
// Volatile prevents compiler folding/removing the product metadata from the image.
static const volatile uint8_t buildMeta[24]={ 'V','B','M','E','T','A','1',0,1,WIRING_REVISION,uint8_t(POWER_PROFILE),2,
  1,0,0,0,uint8_t(release),uint8_t(release>>8),uint8_t(release>>16),uint8_t(release>>24),FW_MAJOR,FW_MINOR,FW_PATCH,buildFlags };
Preferences prefs;
Transfer transfer;
Conditions conditions;
const esp_partition_t* target=nullptr;
esp_ota_handle_t handle=0;
bool opened=false,trial=false,healthyBoot=false,commissioned=false,layout=false,wdtAdded=false,bootFault=false;
uint32_t bootAt=0,rebootAt=0,currentSize=0;
uint8_t currentHash[32]{};
struct Transaction {uint32_t op=0,address=0;uint8_t manifest[MANIFEST]{};uint32_t crc=0;};
Transaction transaction;
struct Cache {uint32_t magic=0,address=0,release=0,size=0;uint8_t hash[32]{};uint32_t crc=0;};
RTC_DATA_ATTR Cache cache;
void cacheImage(){cache.magic=0x4f544131;cache.address=esp_ota_get_running_partition()->address;
 cache.release=release;cache.size=currentSize;memcpy(cache.hash,currentHash,32);
 cache.crc=crc32(reinterpret_cast<uint8_t*>(&cache),sizeof(cache)-4);}

void metadata(uint8_t* out){for(unsigned i=0;i<24;i++)out[i]=buildMeta[i];}
bool verifySignature(const Manifest& m){
 if(memcmp(m.bytes+64,otakey::ID,8))return false;
 uint8_t digest[32];mbedtls_sha256_ret(m.bytes,BODY,digest,0);
 mbedtls_ecdsa_context ctx;mbedtls_ecdsa_init(&ctx);mbedtls_mpi r,s;mbedtls_mpi_init(&r);mbedtls_mpi_init(&s);
 bool ok=mbedtls_ecp_group_load(&ctx.grp,MBEDTLS_ECP_DP_SECP256R1)==0 &&
  mbedtls_ecp_point_read_binary(&ctx.grp,&ctx.Q,otakey::PUBLIC_KEY,65)==0 &&
  mbedtls_mpi_read_binary(&r,m.bytes+BODY,32)==0 && mbedtls_mpi_read_binary(&s,m.bytes+BODY+32,32)==0 &&
  mbedtls_ecdsa_verify(&ctx.grp,digest,32,&ctx.Q,&r,&s)==0;
 mbedtls_mpi_free(&r);mbedtls_mpi_free(&s);mbedtls_ecdsa_free(&ctx);return ok;
}
bool hashPartition(const esp_partition_t* p,uint32_t n,uint8_t* digest){
 if(!p||!n||n>p->size)return false;
 mbedtls_sha256_context ctx;mbedtls_sha256_init(&ctx);mbedtls_sha256_starts_ret(&ctx,0);
 uint8_t bytes[2048];bool ok=true;
 for(uint32_t at=0;at<n;){size_t count=min(size_t(n-at),sizeof(bytes));
  if(esp_partition_read(p,at,bytes,count)!=ESP_OK){ok=false;break;}
  mbedtls_sha256_update_ret(&ctx,bytes,count);at+=count;delay(1);
 }
 if(ok)ok=mbedtls_sha256_finish_ret(&ctx,digest)==0;mbedtls_sha256_free(&ctx);return ok;
}
bool imageInfo(){
 auto p=esp_ota_get_running_partition();esp_partition_pos_t pos{p->address,p->size};esp_image_metadata_t info{};
 if(esp_image_verify(ESP_IMAGE_VERIFY_SILENT,&pos,&info)!=ESP_OK)return false;
 if(!otawire::imageSize([&](uint32_t at,uint8_t* b,size_t n){return esp_partition_read(p,at,b,n)==ESP_OK;},p->size,currentSize))return false;
 return hashPartition(p,currentSize,currentHash);
}
bool checkLayout(){
 auto a=esp_partition_find_first(ESP_PARTITION_TYPE_APP,ESP_PARTITION_SUBTYPE_APP_OTA_0,nullptr);
 auto b=esp_partition_find_first(ESP_PARTITION_TYPE_APP,ESP_PARTITION_SUBTYPE_APP_OTA_1,nullptr);
 auto n=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_NVS,"nvs");
 auto o=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_OTA,nullptr);
 auto fs=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_SPIFFS,"spiffs");
 auto dump=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_COREDUMP,"coredump");
 return ESP.getFlashChipSize()==0x400000&&a&&b&&n&&o&&a->address==0x10000&&a->size==0x180000&&
  b->address==0x190000&&b->size==0x180000&&n->address==0x9000&&n->size==0x5000&&o->address==0xe000&&o->size==0x2000&&
  fs&&fs->address==0x310000&&fs->size==0xe0000&&dump&&dump->address==0x3f0000&&dump->size==0x10000;
}
bool production(){return !buildFlags&&POWER_SLIDER&&PIN_POWER_SWITCH==3&&PIN_POWER_HOLD==7;}
bool eligible(){return layout&&production()&&commissioned&&healthyBoot&&!bootFault&&conditions.idle&&
 conditions.storage&&conditions.on&&conditions.powerKnown&&conditions.voltage>=3.8f&&conditions.percent>=50;}
bool saveTransaction(uint32_t op,const Manifest& m){
 Transaction t{};t.op=op;t.address=target->address;memcpy(t.manifest,m.bytes,MANIFEST);
 t.crc=crc32(reinterpret_cast<uint8_t*>(&t),sizeof(t)-4);Transaction check{};
 if(prefs.putBytes("tx",&t,sizeof(t))!=sizeof(t)||prefs.getBytes("tx",&check,sizeof(check))!=sizeof(check)||memcmp(&t,&check,sizeof(t)))return false;
 transaction=t;return true;
}
bool metadataMatches(const Manifest& m){
 uint8_t expected[24];m.metadata(expected);uint8_t chunk[2048+23];size_t carry=0;unsigned found=0;
 for(uint32_t at=0;at<m.size();){size_t n=min(size_t(m.size()-at),size_t(2048));
  if(esp_partition_read(target,at,chunk+carry,n)!=ESP_OK)return false;
  size_t total=carry+n;
  for(size_t i=0;i+24<=total;i++)if(!memcmp(chunk+i,expected,24))found++;
  carry=min(size_t(23),total);memmove(chunk,chunk+total-carry,carry);at+=n;delay(1);
 }
 return found==1;
}
class Flash:public Backend {
 bool signature(const Manifest& m)override{return verifySignature(m);}
 bool start(const Manifest& m)override{
  target=esp_ota_get_next_update_partition(nullptr);
  if(!target||target==esp_ota_get_running_partition()||m.size()>target->size)return false;
  opened=esp_ota_begin(target,m.size(),&handle)==ESP_OK;return opened;
 }
 bool write(const uint8_t* p,size_t n)override{return opened&&esp_ota_write(handle,p,n)==ESP_OK;}
 bool same(uint32_t at,const uint8_t* p,size_t n)override{
  uint8_t check[CHUNK];return target&&n<=CHUNK&&esp_partition_read(target,at,check,n)==ESP_OK&&!memcmp(check,p,n);
 }
 bool finish(const Manifest& m)override{
  if(!opened)return false;opened=false;if(esp_ota_end(handle)!=ESP_OK)return false;
  uint8_t hash[32];return hashPartition(target,m.size(),hash)&&!memcmp(hash,m.hash(),32)&&metadataMatches(m)&&verifySignature(m);
 }
 bool activate(uint32_t op,const Manifest& m)override{
  // Write intent before changing boot selection; never modify the running partition.
  return eligible()&&saveTransaction(op,m)&&esp_ota_set_boot_partition(target)==ESP_OK;
 }
 bool abort()override{if(!opened)return true;opened=false;return esp_ota_abort(handle)==ESP_OK;}
} flash;
uint8_t bootState(){esp_ota_img_states_t s;return esp_ota_get_state_partition(esp_ota_get_running_partition(),&s)==ESP_OK?uint8_t(s):255;}
void sendStatus(uint32_t request){
 uint8_t p[otawire::STATUS]{};p[0]=2;p[1]=QUERY;put32(p+2,request);put32(p+6,transfer.operation);
 p[10]=trial?TRIAL:transfer.phase;p[11]=transfer.result;
 uint16_t flags=(layout&&production()?1:0)|(commissioned?2:0)|(eligible()?4:0)|(conditions.powerKnown?8:0)|(conditions.on?16:0)|(healthyBoot&&!bootFault?32:0);
 p[12]=flags;p[13]=flags>>8;metadata(p+14);put32(p+38,layout?0x180000:0);put32(p+42,ESP.getFlashChipSize());
 put32(p+46,transfer.offset);put32(p+50,currentSize);memcpy(p+54,currentHash,32);
 if(transaction.op)memcpy(p+86,transaction.manifest+32,32);
 uint64_t id=ESP.getEfuseMac();memcpy(p+118,&id,8);memcpy(p+126,otakey::ID,8);
 p[134]=0xd8;p[135]=0x0e; // 3800mV
 uint16_t mv=conditions.powerKnown?uint16_t(conditions.voltage*1000):0;p[136]=mv;p[137]=mv>>8;
 put32(p+138,transfer.manifest.release());p[142]=healthyBoot?2:bootState();radio::sendEvent(p,sizeof(p));
}
}
void earlyBoot(){
 trial=bootState()==ESP_OTA_IMG_PENDING_VERIFY;bootAt=millis();
 if(trial){
  const bool timer=esp_task_wdt_init(30,true)==ESP_OK;
  wdtAdded=timer&&(esp_task_wdt_add(nullptr)==ESP_OK||esp_task_wdt_status(nullptr)==ESP_OK);
  if(!wdtAdded){bootFault=true;backlight::sleep();esp_ota_mark_app_invalid_rollback_and_reboot();}
 }
}
void begin(){
 // Isolate OTA intent/commissioning/high-release from the incompatible wiring-1
 // proposal. Other NVS namespaces and the old OTA records are left untouched.
 layout=checkLayout();bool ok=prefs.begin("ota-v2-r2",false);commissioned=ok&&prefs.getBool("model1",false);
 bool warm=esp_sleep_get_wakeup_cause()!=ESP_SLEEP_WAKEUP_UNDEFINED;
 bool cached=warm&&!trial&&cache.magic==0x4f544131&&cache.address==esp_ota_get_running_partition()->address&&
  cache.release==release&&cache.crc==crc32(reinterpret_cast<uint8_t*>(&cache),sizeof(cache)-4);
 if(cached){currentSize=cache.size;memcpy(currentHash,cache.hash,32);healthyBoot=true;transfer.phase=VALID;}
 bootFault=bootFault||!ok||(!cached&&!imageInfo());
 if(prefs.isKey("tx")&&prefs.getBytesLength("tx")!=sizeof(transaction))bootFault=true;
 if(prefs.getBytesLength("tx")==sizeof(transaction)){
  Transaction t{};Manifest m;
  if(prefs.getBytes("tx",&t,sizeof(t))==sizeof(t)&&t.crc==crc32(reinterpret_cast<uint8_t*>(&t),sizeof(t)-4)&&m.parse(t.manifest,MANIFEST)&&verifySignature(m)){
   transaction=t;transfer.operation=t.op;transfer.manifest=m;
   auto running=esp_ota_get_running_partition();auto boot=esp_ota_get_boot_partition();
   if(t.address==running->address){if(memcmp(m.hash(),currentHash,32)||m.size()!=currentSize)bootFault=true;}
   else if(boot&&boot->address==t.address){
    esp_ota_img_states_t s;const esp_partition_t* next=esp_ota_get_next_update_partition(nullptr);
    if(next&&esp_ota_get_state_partition(next,&s)==ESP_OK&&(s==ESP_OTA_IMG_ABORTED||s==ESP_OTA_IMG_INVALID)){transfer.phase=FAILED;transfer.result=COMPAT;}
    else transfer.phase=SELECTED;
   }else {transfer.phase=FAILED;transfer.result=COMPAT;}
  }else bootFault=true;
 }
}
void poll(const Conditions& c,bool healthy){
 conditions=c;
 if(!healthyBoot){
  if(trial&&wdtAdded)esp_task_wdt_reset();
  if(uint32_t(millis()-bootAt)>30000){if(trial){backlight::sleep();esp_ota_mark_app_invalid_rollback_and_reboot();}bootFault=true;}
  else if(uint32_t(millis()-bootAt)>=3000&&healthy&&c.on&&layout&&production()&&!bootFault){
   uint32_t marker=0x56424f31;bool nvsOk=prefs.putUInt("health",marker)==4&&prefs.getUInt("health",0)==marker;
   if(nvsOk&&esp_ota_mark_app_valid_cancel_rollback()==ESP_OK){
    healthyBoot=true;trial=false;cacheImage();
    uint32_t high=max(release,prefs.getUInt("high",0));
    if(prefs.putUInt("high",high)!=4||prefs.getUInt("high",0)!=high){bootFault=true;transfer.phase=FAILED;transfer.result=IO;}
    if(transfer.phase!=SELECTED&&transfer.phase!=FAILED)transfer.phase=VALID;
    if(wdtAdded){esp_task_wdt_delete(nullptr);wdtAdded=false;}
   }
  }
 }
 transfer.tick(millis(),radio::secured(),flash);
 if(rebootAt&&c.on&&int32_t(millis()-rebootAt)>=0){backlight::sleep();ESP.restart();}
}
bool handle(const uint8_t* p,size_t n,const Conditions& c){
 if(n<2||p[1]<QUERY||p[1]>REBOOT)return false;
 conditions=c;uint32_t op=n>=6?get32(p+2):0;
 if(n<6||p[0]!=2||!radio::secured()){transfer.result=BAD;sendStatus(op);return true;}
 switch(p[1]){
 case QUERY:transfer.result=n==6?otawire::OK:BAD;break;
 case BEGIN:transfer.begin(op,p+6,n-6,release,prefs.getUInt("high",release),eligible(),millis(),flash);if(transfer.phase==RECEIVING)radio::setLive(true);break;
 case DATA:if(n<=14)transfer.result=BAD;else if(!conditions.on||!conditions.powerKnown||conditions.voltage<3.6f){transfer.cancel(flash,POWER);}else transfer.data(op,get32(p+6),get32(p+10),p+14,n-14,millis(),flash);break;
 case FINISH:if(n!=6)transfer.result=BAD;else if(!eligible()){transfer.cancel(flash,POWER);}else transfer.finish(op,flash);break;
 case ABORT:if(n!=6||op!=transfer.operation)transfer.result=SESSION;else transfer.cancel(flash);break;
 case REBOOT:if(n!=6||op!=transfer.operation||transfer.phase!=SELECTED||!conditions.on)transfer.result=SESSION;else{transfer.result=otawire::OK;rebootAt=millis()+500;}break;
 }
 sendStatus(op);return true;
}
bool exclusive(){return transfer.phase==RECEIVING||transfer.phase==SELECTED||trial||rebootAt||(!healthyBoot&&!bootFault&&layout&&production());}
bool abortForOff(){return transfer.cancel(flash);}
poweroff::OtaPhase offPhase(){return trial?poweroff::OtaPhase::TrialBoot:transfer.phase==RECEIVING?poweroff::OtaPhase::Receiving:
 transfer.phase==SELECTED?poweroff::OtaPhase::SlotSelected:healthyBoot?poweroff::OtaPhase::Valid:poweroff::OtaPhase::None;}
bool provision(){
 if(!Serial||!layout||!production()||!conditions.on||exclusive())return false;
 commissioned=prefs.putBool("model1",true)==1&&prefs.getBool("model1",false);return commissioned;
}
}
