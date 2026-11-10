#include "clock.h"

#include <Arduino.h>
#include <Wire.h>
#include <Preferences.h>
#include "ds3231_time.h"
#include <string.h>
#include <sys/time.h>
#include <time.h>

#include "nmea.h"
#include "pins.h"

namespace clockx {

namespace {

constexpr uint8_t ADDR_RTC=0x68;
Preferences g_clockPrefs;
bool g_prefsReady=false;
bool g_savedTrust=false;

bool g_present = false;
int32_t g_rejectedResync = 0;
// Ход времени ведут внутренние часы ESP32 (системное время): они идут и в глубоком сне.
// Доверие и источник — в RTC-памяти, чтобы пережить сон, даже если DS3231 при пробуждении не ответил
RTC_DATA_ATTR bool g_trusted = false;
RTC_DATA_ATTR Source g_source = SRC_NONE;
RTC_DATA_ATTR bool g_sysValid = false;  // системное время уже ставили (до сна)
RTC_DATA_ATTR uint32_t g_bootUtc = 0;   // начало работы, сохраняется во сне и сдвигается вместе с часами

bool readBytes(uint8_t reg,uint8_t* out,uint8_t n) {
  Wire.beginTransmission(ADDR_RTC); Wire.write(reg);
  if(Wire.endTransmission(false)!=0 || Wire.requestFrom(ADDR_RTC,n)!=n)return false;
  for(uint8_t i=0;i<n;i++)out[i]=Wire.read();
  return true;
}
bool writeReg(uint8_t reg,uint8_t value) {
  Wire.beginTransmission(ADDR_RTC);Wire.write(reg);Wire.write(value);
  return Wire.endTransmission()==0;
}
bool readClock(uint32_t& utc,bool& halted) {
  uint8_t r[7],status;
  if(!readBytes(0,r,7)||!readBytes(0x0F,&status,1))return false;
  halted=(status&0x80)!=0; // OSF: oscillator stopped, time cannot be trusted
  return ds3231time::decode(r,utc);
}
uint8_t bin2bcd(uint8_t v) {return ds3231time::packed(v);}

bool writeClock(uint32_t utc) {
  // Unix → дата (обратный алгоритм Хиннанта)
  long z = utc / 86400 + 719468;
  long era = (z >= 0 ? z : z - 146096) / 146097;
  unsigned doe = static_cast<unsigned>(z - era * 146097);
  unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  int y = static_cast<int>(yoe) + era * 400;
  unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  unsigned mp = (5 * doy + 2) / 153;
  unsigned d = doy - (153 * mp + 2) / 5 + 1;
  unsigned m = mp < 10 ? mp + 3 : mp - 9;
  y += (m <= 2);
  uint32_t sod = utc % 86400;

  if(y<2000||y>2099)return false;
  // Invalidate durable trust before changing registers: interruption must not validate a partial write.
  g_savedTrust=false;
  if(!g_prefsReady||g_clockPrefs.putBool("trusted",false)!=1)return false;
  uint8_t control,status;
  if(!readBytes(0x0E,&control,1)||!readBytes(0x0F,&status,1))return false;
  Wire.beginTransmission(ADDR_RTC);Wire.write(0);
  const uint8_t values[]={bin2bcd(sod%60),bin2bcd((sod/60)%60),bin2bcd(sod/3600),
      1,bin2bcd(d),bin2bcd(m),bin2bcd(y-2000)};
  Wire.write(values,7);
  if(Wire.endTransmission()!=0 || !writeReg(0x0E,control&~0x80) ||
      !writeReg(0x0F,status&~0x80))return false;
  uint32_t check;bool halted;
  if(!readClock(check,halted)||halted||check<utc||check-utc>1)return false;
  g_savedTrust=g_clockPrefs.putBool("trusted",true)==1;
  return g_savedTrust;
}

void setBase(uint32_t utc) {
  if (g_bootUtc) g_bootUtc += utc - static_cast<uint32_t>(time(nullptr));
  else g_bootUtc = utc;
  timeval tv{static_cast<time_t>(utc), 0};
  settimeofday(&tv, nullptr);
  g_sysValid = true;
}

// Время сборки прошивки — нижняя граница для непроверенного времени (лучше, чем 1970 или 2000 год)
uint32_t buildTime() {
  static const char* months = "JanFebMarAprMayJunJulAugSepOctNovDec";
  char m[4] = {__DATE__[0], __DATE__[1], __DATE__[2], 0};
  int mon = (strstr(months, m) - months) / 3 + 1;
  int day = atoi(__DATE__ + 4), year = atoi(__DATE__ + 7);
  int hh = atoi(__TIME__), mm = atoi(__TIME__ + 3), ss = atoi(__TIME__ + 6);
  return nmea::toUnix(year, mon, day, hh, mm, ss);
}

}  // namespace

bool begin(bool cold) {
  if (cold) g_bootUtc = 0;
  if(!g_prefsReady)g_prefsReady=g_clockPrefs.begin("rtc-v2",false);
  g_savedTrust=g_prefsReady&&g_clockPrefs.getBool("trusted",false);
  uint8_t status;
  g_present=readBytes(0x0F,&status,1);
  // A misplaced MPU6050 at 0x68 must never receive RTC writes. Fail closed.
  uint8_t who=0;
  if(g_present && readBytes(0x75,&who,1) && (who&0x7E)==0x68)g_present=false;
  uint32_t utc=0;
  bool halted=true;
  if (!cold && g_sysValid) {
    // Внутренние часы идут во сне. Не заменяем их ни старым RTC, ни временем сборки:
    // __TIME__ может быть в местном часовом поясе компьютера, а здесь нужен UTC.
    return g_present;
  }
  if (!g_present) {
    g_trusted = false;
    g_source = SRC_NONE;
    setBase(buildTime());
    return false;
  }
  if (!readClock(utc, halted) || utc < 1700000000UL) {
    // Отбрасываем дату нового модуля, но не сравниваем UTC с локальным временем сборки.
    halted = true;
    utc = buildTime();
  }
  // Do not clear OSF or overwrite a newly connected clock with build time.
  g_trusted=!halted&&g_savedTrust;
  g_source = g_trusted ? SRC_RTC : SRC_NONE;
  setBase(utc);
  return true;
}

uint32_t now() { return static_cast<uint32_t>(time(nullptr)); }
uint32_t uptimeS() {
  uint32_t t = now();
  return g_bootUtc && t >= g_bootUtc ? t - g_bootUtc : 0;
}

bool trusted() { return g_trusted; }
Source source() { return g_source; }
bool rtcPresent() { return g_present; }

void setFromPhone(uint32_t utc) {
  setBase(utc);
  g_trusted = true;
  g_source = SRC_PHONE;
  if (g_present) writeClock(utc);
}

int32_t offerGps(uint32_t utc, bool spoofSuspected) {
  if (g_trusted || spoofSuspected || utc < 1700000000UL) return 0;
  int32_t delta = (int32_t)(utc - now());
  setBase(utc);
  g_trusted = true;
  g_source = SRC_GPS;
  if (g_present) writeClock(utc);
  return delta;
}

int32_t resync() {
  g_rejectedResync = 0;
  if (!g_present || !g_trusted || !g_savedTrust) return 0;
  uint32_t utc;
  bool halted;
  if (!readClock(utc, halted) || halted || utc < 1700000000UL) return 0;
  int32_t delta = static_cast<int32_t>(utc - now());
  // Раньше любая правдоподобная дата DS3231 сразу меняла системное время.
  // Скачок на минуты портил метки времени и превращал корректный GPS в R_TIME.
  // Большое расхождение требует независимой синхронизации с телефоном.
  if (delta > 30 || delta < -30) {
    g_rejectedResync = delta;
    return 0;
  }
  setBase(utc);
  return delta;
}

int32_t rejectedResync() { return g_rejectedResync; }

}  // namespace clockx
