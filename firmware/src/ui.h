// Кнопки, пищалка, светодиод, экран (ТЗ, раздел 10). Раскладка кнопок — в config.h.
#pragma once
#include <stdint.h>
#include "screen_input.h"

namespace ui {

using Button = screeninput::Button;

enum class Sound : uint8_t {
  MARK,         // короткий писк
  CLAP,         // громкий длинный + вспышка — «хлопушка» для синхронизации камер
  FIX,          // поймал спутники
  ALERT_SPOOF,  // три коротких
  ALERT_BATT,   // два длинных
  FIND,         // «найди меня» — 5 с
  WINDOW,       // окно подключения открыто
  ACHIEVEMENT,  // короткая наградная последовательность
};

// wakePin — кнопка, разбудившая из сна: при наличии экрана только включает его.
void begin(bool coldBoot, int wakePin = -1);
// Опрос кнопок (с антидребезгом). Вызывать каждую итерацию
Button poll();
Button hikeButton(Button event);
void hikeResult(bool ok);
bool anyHeld();

void play(Sound s);
bool busy();  // играет звук или горит светодиод

void screenOn();
void nextPage();
bool screenActive();
// Перерисовать экран (раз в секунду, если включён)
void draw();
void splash(const char* line2);
void sleep();

}  // namespace ui
