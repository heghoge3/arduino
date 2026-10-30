#include "clock.h"

#include <Arduino.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>

#include "nmea.h"
#include "pins.h"

namespace clockx {

namespace {

// Регистры DS1302 (адрес записи; чтение = +1)
constexpr uint8_t REG_SEC = 0x80;
constexpr uint8_t REG_WP = 0x8E;
constexpr uint8_t REG_TRICKLE = 0x90;
constexpr uint8_t REG_CLOCK_BURST = 0xBE;
constexpr uint8_t REG_RAM0 = 0xC0;

// Метка в RAM DS1302 (питается от батарейки): «время ставили с телефона/GPS».
// Села батарейка — RAM обнулится, метка пропадёт, время станет непроверенным.
constexpr uint8_t RAM_MAGIC = 0xA7;

bool g_present = false;
// Ход времени ведут внутренние часы ESP32 (системное время): они идут и в глубоком сне.
// Доверие и источник — в RTC-памяти, чтобы пережить сон, даже если DS1302 при пробуждении не ответил
RTC_DATA_ATTR bool g_trusted = false;
RTC_DATA_ATTR Source g_source = SRC_NONE;
RTC_DATA_ATTR bool g_sysValid = false;  // системное время уже ставили (до сна)
RTC_DATA_ATTR uint32_t g_bootUtc = 0;   // начало работы, сохраняется во сне и сдвигается вместе с часами

void writeByte(uint8_t v, bool reading = false) {
  pinMode(PIN_RTC_DAT, OUTPUT);
  for (int i = 0; i < 8; i++) {
    digitalWrite(PIN_RTC_DAT, v & 1);
    delayMicroseconds(1);
    digitalWrite(PIN_RTC_CLK, HIGH);
    delayMicroseconds(1);
    // DS1302 выдаёт первый бит уже на последнем спаде команды чтения.
    // Освобождаем DAT ДО этого спада, иначе оба устройства ведут линию.
    if (reading && i == 7) pinMode(PIN_RTC_DAT, INPUT_PULLUP);
    digitalWrite(PIN_RTC_CLK, LOW);
    v >>= 1;
  }
}

uint8_t readByte() {
  pinMode(PIN_RTC_DAT, INPUT_PULLUP);  // без модуля читаются 0xFF — дата невалидна
  uint8_t v = 0;
  for (int i = 0; i < 8; i++) {
    delayMicroseconds(1);
    v |= (digitalRead(PIN_RTC_DAT) ? 1 : 0) << i;
    digitalWrite(PIN_RTC_CLK, HIGH);
    delayMicroseconds(1);
    digitalWrite(PIN_RTC_CLK, LOW);
  }
  return v;
}

void start() {
  digitalWrite(PIN_RTC_CLK, LOW);
  digitalWrite(PIN_RTC_RST, HIGH);
  delayMicroseconds(4);
}

void stop() {
  digitalWrite(PIN_RTC_RST, LOW);
  delayMicroseconds(4);
}

uint8_t readReg(uint8_t reg) {
  start();
  writeByte(reg | 1, true);
  uint8_t v = readByte();
  stop();
  return v;
}

void writeReg(uint8_t reg, uint8_t v) {
  start();
  writeByte(reg);
  writeByte(v);
  stop();
}

uint8_t bcd2bin(uint8_t v) { return (v >> 4) * 10 + (v & 0x0F); }
uint8_t bin2bcd(uint8_t v) { return ((v / 10) << 4) | (v % 10); }

// Прочитать дату-время пачкой. halted — часы стояли (бит CH)
bool readClock(uint32_t& utc, bool& halted) {
  uint8_t r[8];
  start();
  writeByte(REG_CLOCK_BURST | 1, true);
  for (int i = 0; i < 8; i++) r[i] = readByte();
  stop();
  halted = r[0] & 0x80;
  int sec = bcd2bin(r[0] & 0x7F), min = bcd2bin(r[1] & 0x7F), hour = bcd2bin(r[2] & 0x3F);
  int day = bcd2bin(r[3] & 0x3F), mon = bcd2bin(r[4] & 0x1F), year = 2000 + bcd2bin(r[6]);
  // Нет модуля — линия данных висит, читаются 0xFF/0x00: такие даты невалидны
  if (sec > 59 || min > 59 || hour > 23 || day < 1 || day > 31 || mon < 1 || mon > 12) return false;
  utc = nmea::toUnix(year, mon, day, hour, min, sec);
  return true;
}

void writeClock(uint32_t utc) {
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

  writeReg(REG_WP, 0x00);  // снять защиту записи
  start();
  writeByte(REG_CLOCK_BURST);
  writeByte(bin2bcd(sod % 60));  // секунды, CH = 0 — часы идут
  writeByte(bin2bcd((sod / 60) % 60));
  writeByte(bin2bcd(sod / 3600));  // 24-часовой формат
  writeByte(bin2bcd(d));
  writeByte(bin2bcd(m));
  writeByte(1);  // день недели не используем
  writeByte(bin2bcd(y - 2000));
  writeByte(0x80);  // защита записи обратно
  stop();
  writeReg(REG_WP, 0x00);
  writeReg(REG_RAM0, RAM_MAGIC);
  writeReg(REG_WP, 0x80);
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
  pinMode(PIN_RTC_CLK, OUTPUT);
  pinMode(PIN_RTC_RST, OUTPUT);
  digitalWrite(PIN_RTC_RST, LOW);
  digitalWrite(PIN_RTC_CLK, LOW);

  // Подзаряд батарейки выключить ВСЕГДА: CR2032 не заряжается (ТЗ, раздел 2)
  writeReg(REG_WP, 0x00);
  writeReg(REG_TRICKLE, 0x00);
  writeReg(REG_WP, 0x80);

  uint32_t utc = 0;
  bool halted = true;
  // Неправильная дата нового модуля не означает, что он отсутствует.
  // Проверяем обмен через последний байт RAM, сохраняя его содержимое.
  constexpr uint8_t probeReg = 0xFC;
  uint8_t saved = readReg(probeReg);
  writeReg(REG_WP, 0x00);
  writeReg(probeReg, 0x5A);
  bool first = readReg(probeReg) == 0x5A;
  writeReg(probeReg, 0xA5);
  g_present = first && readReg(probeReg) == 0xA5;
  writeReg(probeReg, saved);
  writeReg(REG_WP, 0x80);
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
  if (halted) {
    // Часы стояли: запускаем с того, что есть, но время не доверенное
    writeClock(utc);
    writeReg(REG_WP, 0x00);
    writeReg(REG_RAM0, 0x00);  // снять метку доверия
    writeReg(REG_WP, 0x80);
  }
  g_trusted = !halted && readReg(REG_RAM0) == RAM_MAGIC;
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
  if (!g_present || !g_trusted || readReg(REG_RAM0) != RAM_MAGIC) return 0;
  uint32_t utc;
  bool halted;
  if (!readClock(utc, halted) || halted || utc < 1700000000UL) return 0;
  int32_t delta = static_cast<int32_t>(utc - now());
  setBase(utc);
  return delta;
}

}  // namespace clockx
