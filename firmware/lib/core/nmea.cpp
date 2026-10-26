#include "nmea.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace nmea {

namespace {

int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

// Разбить по запятым на месте. Возвращает число полей.
int split(char* s, char** fields, int maxFields) {
  int n = 0;
  fields[n++] = s;
  for (char* p = s; *p && n < maxFields; p++) {
    if (*p == ',') {
      *p = 0;
      fields[n++] = p + 1;
    }
  }
  return n;
}

// ddmm.mmmm + полушарие → градусы
bool parseCoord(const char* v, const char* hemi, int degDigits, double& out) {
  if (!v[0] || !hemi[0]) return false;
  size_t l = strlen(v);
  if (l < (size_t)degDigits + 2) return false;
  char deg[4] = {0};
  memcpy(deg, v, degDigits);
  double d = atof(deg) + atof(v + degDigits) / 60.0;
  if (hemi[0] == 'S' || hemi[0] == 'W') d = -d;
  out = d;
  return true;
}

uint8_t talkerSystem(const char* talker) {
  if (!strncmp(talker, "GP", 2)) return SYS_GPS;
  if (!strncmp(talker, "BD", 2) || !strncmp(talker, "GB", 2)) return SYS_BDS;
  if (!strncmp(talker, "GL", 2)) return SYS_GLO;
  if (!strncmp(talker, "GA", 2)) return SYS_GAL;
  return SYS_UNKNOWN;  // GN — смешанное
}

// Идентификатор системы в GSA (NMEA 4.10+): 1 GPS, 2 ГЛОНАСС, 3 Galileo, 4 BeiDou
uint8_t gsaSystemId(int id) {
  switch (id) {
    case 1: return SYS_GPS;
    case 2: return SYS_GLO;
    case 3: return SYS_GAL;
    case 4: return SYS_BDS;
    default: return SYS_UNKNOWN;
  }
}

}  // namespace

uint8_t checksum(const char* body, size_t len) {
  uint8_t cs = 0;
  for (size_t i = 0; i < len; i++) cs ^= static_cast<uint8_t>(body[i]);
  return cs;
}

size_t buildCommand(const char* body, char* out, size_t cap) {
  int n = snprintf(out, cap, "$%s*%02X\r\n", body, checksum(body, strlen(body)));
  return (n > 0 && (size_t)n < cap) ? (size_t)n : 0;
}

uint32_t toUnix(int y, int m, int d, int hh, int mm, int ss) {
  // Дни от 1970-01-01 (алгоритм Хиннанта days_from_civil)
  y -= m <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  const long days = era * 146097L + static_cast<long>(doe) - 719468L;
  return static_cast<uint32_t>(days * 86400L + hh * 3600L + mm * 60L + ss);
}

void Parser::reset() {
  fix_ = Fix();
  validFixes_ = 0;
  for (auto& s : sys_) s = SysSats();
  satCount_ = 0;
  len_ = 0;
  inSentence_ = false;
}

bool Parser::feed(char c) {
  if (c == '$') {
    inSentence_ = true;
    len_ = 0;
    return false;
  }
  if (!inSentence_) return false;
  if (c == '\r' || c == '\n') {
    inSentence_ = false;
    buf_[len_] = 0;
    return handle(buf_);
  }
  if (len_ >= sizeof(buf_) - 1) {  // слишком длинное — мусор
    inSentence_ = false;
    return false;
  }
  buf_[len_++] = c;
  return false;
}

bool Parser::parseLine(const char* line) {
  if (line[0] == '$') line++;
  strncpy(buf_, line, sizeof(buf_) - 1);
  buf_[sizeof(buf_) - 1] = 0;
  // Отрезать \r\n, если есть
  for (char* p = buf_; *p; p++) {
    if (*p == '\r' || *p == '\n') { *p = 0; break; }
  }
  return handle(buf_);
}

bool Parser::handle(char* body) {
  char* star = strchr(body, '*');
  if (!star || strlen(star) < 3) return false;
  int h = hexVal(star[1]), l = hexVal(star[2]);
  if (h < 0 || l < 0) return false;
  if (checksum(body, star - body) != ((h << 4) | l)) {
    checksumErrors_++;
    return false;
  }
  *star = 0;
  if (strlen(body) < 5) return false;

  char* f[24];
  int n = split(body, f, 24);
  const char* type = f[0] + 2;
  uint8_t sys = talkerSystem(f[0]);
  if (sys != SYS_UNKNOWN) systemsSeen_ |= 1 << sys;

  if (!strcmp(type, "RMC")) onRmc(f, n);
  else if (!strcmp(type, "GGA")) onGga(f, n);
  else if (!strcmp(type, "GSA")) onGsa(f, n, sys);
  else if (!strcmp(type, "GSV")) onGsv(f, n, sys);
  else return false;
  sentences_++;
  return true;
}

void Parser::onRmc(char** f, int n) {
  // $GNRMC,hhmmss.ss,A,llll.ll,N,yyyyy.yy,E,speed,course,ddmmyy,...
  if (n < 10) return;
  const char* t = f[1];
  const char* d = f[9];
  if (strlen(t) >= 6 && strlen(d) == 6) {
    auto two = [](const char* p) { return (p[0] - '0') * 10 + (p[1] - '0'); };
    int year = 2000 + two(d + 4);
    // Год 2000+: защита от «недели GPS» и мусора — в разумных пределах
    if (year >= 2024 && year <= 2099) {
      fix_.utc = toUnix(year, two(d + 2), two(d), two(t), two(t + 2), two(t + 4));
      fix_.timeValid = true;
    }
  }
  bool active = f[2][0] == 'A';
  double lat, lon;
  if (active && parseCoord(f[3], f[4], 2, lat) && parseCoord(f[5], f[6], 3, lon)) {
    fix_.lat = lat;
    fix_.lon = lon;
    fix_.valid = true;
    validFixes_++;
    fix_.speedKmh = f[7][0] ? static_cast<float>(atof(f[7]) * 1.852) : 0;
    fix_.courseDeg = f[8][0] ? static_cast<float>(atof(f[8])) : 0;
  } else {
    fix_.valid = false;
  }
}

void Parser::onGga(char** f, int n) {
  // $GNGGA,hhmmss.ss,lat,N,lon,E,quality,numSV,hdop,alt,M,...
  if (n < 10) return;
  fix_.quality = static_cast<uint8_t>(atoi(f[6]));
  fix_.satsUsed = static_cast<uint8_t>(atoi(f[7]));
  fix_.hdop = f[8][0] ? static_cast<float>(atof(f[8])) : 99.9f;
  fix_.altValid = f[9][0] != 0 && fix_.quality > 0;
  if (fix_.altValid) fix_.altM = static_cast<float>(atof(f[9]));
  if (fix_.quality == 0) fix_.valid = false;
}

void Parser::onGsa(char** f, int n, uint8_t talkerSys) {
  // $xxGSA,mode,fixType,prn×12,pdop,hdop,vdop[,systemId]
  if (n < 18) return;
  fix_.fixType = static_cast<uint8_t>(atoi(f[2]));
  uint8_t sys = talkerSys;
  if (n >= 19 && f[18][0]) sys = gsaSystemId(atoi(f[18]));
  if (sys == SYS_UNKNOWN) return;  // GN без id системы — не знаем, к какой относится
  SysSats& s = sys_[sys];
  s.nUsed = 0;
  for (int i = 3; i < 15 && s.nUsed < 16; i++) {
    if (f[i][0]) s.usedPrn[s.nUsed++] = static_cast<uint8_t>(atoi(f[i]));
  }
  rebuildSats();
}

void Parser::onGsv(char** f, int n, uint8_t talkerSys) {
  // $xxGSV,total,num,inView,(prn,elev,az,snr)×до 4[,signalId]
  if (n < 4 || talkerSys == SYS_UNKNOWN) return;
  int num = atoi(f[2]);
  SysSats& s = sys_[talkerSys];
  if (num == 1) s.n = 0;  // новая группа
  // Группа из 4 полей целиком; хвостовое поле signalId (NMEA 4.10) в группу не попадает
  for (int i = 4; i + 3 < n; i += 4) {
    if (!f[i][0] || s.n >= 16) break;
    s.prn[s.n] = static_cast<uint8_t>(atoi(f[i]));
    s.snr[s.n] = f[i + 3][0] ? static_cast<uint8_t>(atoi(f[i + 3])) : 0;
    s.n++;
  }
  if (num == atoi(f[1])) rebuildSats();  // последняя часть группы
}

void Parser::rebuildSats() {
  satCount_ = 0;
  for (uint8_t sys = 0; sys < 4; sys++) {
    const SysSats& s = sys_[sys];
    for (uint8_t i = 0; i < s.n && satCount_ < MAX_SATS; i++) {
      bool used = false;
      for (uint8_t j = 0; j < s.nUsed; j++) {
        if (s.usedPrn[j] == s.prn[i]) { used = true; break; }
      }
      sats_[satCount_++] = SatInfo{sys, s.prn[i], s.snr[i], used};
    }
  }
}

}  // namespace nmea
