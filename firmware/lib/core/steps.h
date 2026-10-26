// Счётчик шагов по акселерометру (ТЗ, раздел 8): пики модуля ускорения,
// фильтр, порог с гистерезисом, «мёртвое время» после шага ~280 мс.
// Порог и фильтры — стартовые, подобрать на железе (рюкзак vs карман).
#pragma once
#include <stdint.h>

namespace steps {

struct Config {
  float threshold = 0.12f;    // g над средним — начало шага
  float release = 0.03f;      // g — опустились ниже, можно ловить следующий
  uint32_t refractoryMs = 280;
  uint32_t maxGapMs = 2000;   // реже — это не ходьба, а отдельные толчки
  float baseAlpha = 0.02f;    // скорость следования за «средним» (гравитация, наклон)
  float smoothAlpha = 0.35f;  // сглаживание шума
};

class Detector {
 public:
  explicit Detector(const Config& cfg = Config()) : cfg_(cfg) {}

  // Отсчёт акселерометра в g. true — засчитан шаг.
  bool feed(float ax, float ay, float az, uint32_t tMs);

  uint32_t count() const { return count_; }
  void setCount(uint32_t c) { count_ = c; }

 private:
  Config cfg_;
  bool init_ = false;
  float base_ = 1.0f, smooth_ = 0.0f;
  bool armed_ = true;
  uint32_t lastStep_ = 0;
  uint32_t count_ = 0;
  // Первый шаг серии засчитываем только когда за ним пришёл второй — отсекаем одиночные толчки
  bool pending_ = false;
  bool haveStep_ = false;
};

}  // namespace steps
