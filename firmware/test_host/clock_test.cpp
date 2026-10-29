// Модель трёхпроводной шины: DS1302 выдаёт данные на спаде, читает на фронте.
#include "Arduino.h"
#include "clock.h"
#include "pins.h"
#include "nmea.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

namespace {
uint8_t regs[64];
bool present = true, selected = false, reading = false;
int clockLevel = 0, dataLevel = 0, dataMode = INPUT_PULLUP;
unsigned bits = 0, readBit = 0;
uint8_t command = 0, incoming = 0, output = 1;
bool contention = false;
unsigned indexFor(unsigned bit) {
  unsigned base = (command & 0x7e) >> 1;
  return command == 0xbf ? bit / 8 : base;
}
}

void pinMode(int pin, int mode) { if (pin == PIN_RTC_DAT) dataMode = mode; }
void digitalWrite(int pin, int value) {
  if (pin == PIN_RTC_DAT) { dataLevel = value; return; }
  if (pin == PIN_RTC_RST) {
    selected = value;
    bits = readBit = command = incoming = 0;
    reading = false;
    return;
  }
  if (pin != PIN_RTC_CLK) return;
  bool rising = clockLevel == LOW && value == HIGH;
  bool falling = clockLevel == HIGH && value == LOW;
  clockLevel = value;
  if (!selected || !present) return;
  if (rising && bits < 8) {
    command |= dataLevel << bits++;
    if (bits == 8) reading = command & 1;
  } else if (rising && !reading) {
    incoming |= dataLevel << ((bits - 8) % 8);
    if (++bits % 8 == 0) {
      unsigned index = command == 0xbe ? (bits - 16) / 8 : (command & 0x7e) >> 1;
      if (index == 7 || !(regs[7] & 0x80)) regs[index] = incoming;
      incoming = 0;
    }
  }
  if (falling && reading) {
    if (dataMode == OUTPUT) contention = true;
    output = (regs[indexFor(readBit)] >> (readBit % 8)) & 1;
    ++readBit;
  }
}
int digitalRead(int) { return present ? output : 1; }
void delayMicroseconds(unsigned) {}
uint32_t millis() { return 1000; }

int main() {
  // Новый модуль: дата невалидна, но RAM отвечает. Должен обнаружиться без доверия времени.
  memset(regs, 0, sizeof(regs));
  regs[62] = 0x39;
  assert(clockx::begin());
  assert(clockx::rtcPresent());
  assert(!clockx::trusted());
  assert(regs[62] == 0x39);  // проба не портит RAM
  assert(regs[8] == 0);     // подзаряд CR2032 выключен
  assert(!contention);
  puts("PASS: new DS1302 detected, RAM preserved, charger off, no bus contention");

  uint32_t utc = nmea::toUnix(2030, 10, 30, 12, 34, 56);
  clockx::setFromPhone(utc);
  assert(clockx::trusted());
  assert(clockx::begin());
  assert(clockx::trusted());
  assert(clockx::now() == utc);  // чтение burst и запись всех байтов
  assert(!contention);
  puts("PASS: phone time survives restart and burst read");

  present = false;
  assert(!clockx::begin());
  assert(!clockx::rtcPresent());
  assert(!clockx::trusted());
  puts("PASS: disconnected DS1302 is not detected");
}
