// Минимальный интерфейс Arduino для проверки настоящего драйвера DS1302 на ПК.
#pragma once
#include <stdint.h>
#include <stdlib.h>
constexpr int OUTPUT = 1, INPUT_PULLUP = 2, HIGH = 1, LOW = 0;
void pinMode(int pin, int mode);
void digitalWrite(int pin, int value);
int digitalRead(int pin);
void delayMicroseconds(unsigned value);
uint32_t millis();
