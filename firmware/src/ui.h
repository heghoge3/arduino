// Кнопки, пищалка, светодиод, экран (ТЗ, раздел 10). Раскладка кнопок — в config.h.
#pragma once
#include <stdint.h>

namespace ui {

enum class Button : uint8_t { NONE, A_SHORT, A_LONG, B_SHORT, B_LONG };

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

// wakePin — кнопка, разбудившая из сна: её нажатие засчитывается, даже если уже отпустили
void begin(bool coldBoot, int wakePin = -1);
// Опрос кнопок (с антидребезгом). Вызывать каждую итерацию
Button poll();
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
