#include "ui.h"

#include <Arduino.h>
#include <U8g2lib.h>
#include <math.h>

#include "app.h"
#include "clock.h"
#include "config.h"
#include "pins.h"
#include "radio.h"
#include "storage.h"

namespace ui {

namespace {

U8G2_SSD1306_128X64_NONAME_F_HW_I2C oled(U8G2_R0, U8X8_PIN_NONE);
bool g_oledOk = false;
bool g_screenOn = false;
uint32_t g_screenUntil = 0;
uint8_t g_page = 0;
constexpr uint8_t PAGES = 4;

// ---- Кнопки ----
struct Btn {
  explicit Btn(int p) : pin(p) {}
  int pin;
  bool down = false;
  bool longFired = false;
  uint32_t downAt = 0;
};
Btn g_a(PIN_BTN_A), g_b(PIN_BTN_B);

Button pollButton(Btn& b, Button shortEv, Button longEv) {
  bool pressed = digitalRead(b.pin) == LOW;
  uint32_t now = millis();
  if (pressed && !b.down) {
    b.down = true;
    b.longFired = false;
    b.downAt = now;
  } else if (pressed && b.down) {
    if (!b.longFired && now - b.downAt >= BTN_LONG_MS) {
      b.longFired = true;
      return longEv;
    }
  } else if (!pressed && b.down) {
    b.down = false;
    if (!b.longFired && now - b.downAt >= BTN_DEBOUNCE_MS) return shortEv;
  }
  return Button::NONE;
}

// ---- Звук и свет: шаги «вкл мс, выкл мс, цвет» ----
struct Step {
  uint16_t onMs, offMs;
  uint8_t r, g, b;
};
const Step* g_pattern = nullptr;
uint8_t g_patLen = 0, g_patIdx = 0;
bool g_patOn = false;
uint32_t g_patAt = 0;

const Step P_MARK[] = {{80, 0, 0, 0, 40}};
const Step P_CLAP[] = {{400, 0, 255, 255, 255}};
const Step P_FIX[] = {{60, 80, 0, 40, 0}, {60, 0, 0, 40, 0}};
const Step P_SPOOF[] = {{120, 120, 60, 0, 0}, {120, 120, 60, 0, 0}, {120, 0, 60, 0, 0}};
const Step P_BATT[] = {{500, 300, 60, 30, 0}, {500, 0, 60, 30, 0}};
const Step P_FIND[] = {{200, 300, 40, 0, 40}, {200, 300, 40, 0, 40}, {200, 300, 40, 0, 40},
                       {200, 300, 40, 0, 40}, {200, 300, 40, 0, 40}, {200, 300, 40, 0, 40},
                       {200, 300, 40, 0, 40}, {200, 300, 40, 0, 40}, {200, 300, 40, 0, 40}, {200, 0, 40, 0, 40}};
const Step P_WINDOW[] = {{60, 100, 0, 0, 50}, {60, 0, 0, 0, 50}};

void buzzer(bool on) {
#if BUZZER_ACTIVE
  digitalWrite(PIN_BUZZER, on ? HIGH : LOW);
#else
  ledcWriteTone(0, on ? BUZZER_TONE_HZ : 0);
#endif
}

void led(uint8_t r, uint8_t g, uint8_t b) { neopixelWrite(PIN_RGB, r, g, b); }

void tickPattern() {
  if (!g_pattern) return;
  uint32_t now = millis();
  const Step& s = g_pattern[g_patIdx];
  if (g_patOn && now - g_patAt >= s.onMs) {
    buzzer(false);
    led(0, 0, 0);
    g_patOn = false;
    g_patAt = now;
  } else if (!g_patOn && now - g_patAt >= s.offMs) {
    if (++g_patIdx >= g_patLen) {
      g_pattern = nullptr;
      return;
    }
    const Step& n = g_pattern[g_patIdx];
    buzzer(true);
    led(n.r, n.g, n.b);
    g_patOn = true;
    g_patAt = now;
  }
}

// ---- Экран ----
void fmtLocalTime(uint32_t utc, char* out, size_t cap) {
  uint32_t t = utc + S.tzMin * 60;
  snprintf(out, cap, "%02lu:%02lu", (t / 3600) % 24, (t / 60) % 60);
}

void drawHeader(const char* title) {
  char tm[8];
  fmtLocalTime(N.now, tm, sizeof(tm));
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  oled.drawUTF8(0, 10, tm);
  oled.drawUTF8(40, 10, title);
  char batt[8];
  if (N.pwr.batteryKnown()) snprintf(batt, sizeof(batt), "%u%%", N.pwr.pct);
  else snprintf(batt, sizeof(batt), "—");
  oled.drawUTF8(128 - oled.getUTF8Width(batt), 10, batt);
  oled.drawHLine(0, 13, 128);
}

void drawPage1() {
  drawHeader(P.recording ? "запись" : "пауза");
  oled.setFont(u8g2_font_logisoso24_tn);
  char t[12];
  if (isnan(N.env.tempCorr)) snprintf(t, sizeof(t), "--");
  else snprintf(t, sizeof(t), "%.1f", N.env.tempCorr);
  oled.drawStr(0, 44, t);
  int w = oled.getStrWidth(t);
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  oled.drawUTF8(w + 2, 26, "°C");
  char g[32];
  if (N.spoof) snprintf(g, sizeof(g), "ПОДМЕНА GPS!");
  else if (!N.gpsOn) snprintf(g, sizeof(g), "GPS выкл (стоянка)");
  else if (!N.fix) snprintf(g, sizeof(g), "GPS: поиск, %u сп.", N.satsView);
  else snprintf(g, sizeof(g), "GPS: %u сп. HDOP %.1f", N.satsUsed, N.hdop);
  oled.drawUTF8(0, 62, g);
}

void drawPage2() {
  drawHeader("погода");
  char l[32];
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  snprintf(l, sizeof(l), "Давл. %.0f гПа", N.env.pressHpa);
  oled.drawUTF8(0, 28, l);
  if (isnan(N.trend3h)) snprintf(l, sizeof(l), "Тренд: копим данные");
  else snprintf(l, sizeof(l), "За 3 ч: %+.1f%s", N.trend3h, N.trend3h <= -3 ? " ненаст." : "");
  oled.drawUTF8(0, 41, l);
  snprintf(l, sizeof(l), "Выс. %.0f м  Вл. %.0f%%", N.env.altBaro, N.env.hum);
  oled.drawUTF8(0, 54, l);
}

void drawPage3() {
  drawHeader("путь");
  char l[32], tm[8];
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  if (N.sunsetUtc) {
    fmtLocalTime(N.sunsetUtc, tm, sizeof(tm));
    int32_t d = (int32_t)N.sunsetUtc - (int32_t)N.now;
    uint32_t a = d < 0 ? -d : d;
    snprintf(l, sizeof(l), "Закат %s %s%lu:%02lu", tm, d < 0 ? "+" : "-", a / 3600, (a / 60) % 60);
  } else {
    snprintf(l, sizeof(l), "Закат: нет координат");
  }
  oled.drawUTF8(0, 28, l);
  snprintf(l, sizeof(l), "Пройдено %.2f км", P.distM / 1000.0);
  oled.drawUTF8(0, 41, l);
  snprintf(l, sizeof(l), "Шагов %lu", (unsigned long)P.stepDet.count());
  oled.drawUTF8(0, 54, l);
}

void drawPage4() {
  drawHeader("проверка");
  proto::Check c[proto::MAX_CHECKS];
  uint8_t n = buildChecks(c);
  static const char* names[] = {"", "Экр", "INA", "MPU", "Комп", "BME", "SD", "GPS", "АКБ", "Часы", "Нав"};
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  for (uint8_t i = 0; i < n; i++) {
    char l[16];
    const char* mark = c[i].level == 0 ? "+" : (c[i].level == 1 ? "!" : "x");
    snprintf(l, sizeof(l), "%s%s", mark, c[i].id < 11 ? names[c[i].id] : "?");
    oled.drawUTF8((i % 3) * 43, 23 + (i / 3) * (n > 9 ? 10 : 13), l);
  }
}

}  // namespace

void begin(bool coldBoot, int wakePin) {
  pinMode(PIN_BTN_A, INPUT_PULLUP);
  pinMode(PIN_BTN_B, INPUT_PULLUP);
  // Нажатие началось ещё во сне: считаем кнопку нажатой с момента пробуждения
  for (Btn* b : {&g_a, &g_b}) {
    if (b->pin == wakePin) {
      b->down = true;
      b->longFired = false;
      b->downAt = 0;
    }
  }
#if BUZZER_ACTIVE
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);
#else
  ledcSetup(0, BUZZER_TONE_HZ, 8);
  ledcAttachPin(PIN_BUZZER, 0);
#endif
  led(0, 0, 0);  // встроенный WS2812 гасим сразу: он и выключенный ест ток — проверить замером
  g_oledOk = sensors::presence().oled;
  if (g_oledOk) {
    oled.setBusClock(400000);
    oled.begin();
    oled.enableUTF8Print();
    oled.setPowerSave(1);
  }
  (void)coldBoot;
}

Button poll() {
  tickPattern();
  if (g_screenOn && (int32_t)(millis() - g_screenUntil) >= 0) {
    g_screenOn = false;
    if (g_oledOk) oled.setPowerSave(1);
  }
  Button e = pollButton(g_a, Button::A_SHORT, Button::A_LONG);
  if (e != Button::NONE) return e;
  return pollButton(g_b, Button::B_SHORT, Button::B_LONG);
}

bool anyHeld() { return g_a.down || g_b.down; }

void play(Sound s) {
  switch (s) {
    case Sound::MARK: g_pattern = P_MARK; g_patLen = 1; break;
    case Sound::CLAP: g_pattern = P_CLAP; g_patLen = 1; break;
    case Sound::FIX: g_pattern = P_FIX; g_patLen = 2; break;
    case Sound::ALERT_SPOOF: g_pattern = P_SPOOF; g_patLen = 3; break;
    case Sound::ALERT_BATT: g_pattern = P_BATT; g_patLen = 2; break;
    case Sound::FIND: g_pattern = P_FIND; g_patLen = 10; break;
    case Sound::WINDOW: g_pattern = P_WINDOW; g_patLen = 2; break;
  }
  g_patIdx = 0;
  const Step& st = g_pattern[0];
  buzzer(true);
  led(st.r, st.g, st.b);
  g_patOn = true;
  g_patAt = millis();
}

bool busy() { return g_pattern != nullptr; }

void screenOn() {
  if (!g_oledOk) return;
  if (!g_screenOn) {
    g_page = 0;
    oled.setPowerSave(0);
  }
  g_screenOn = true;
  g_screenUntil = millis() + SCREEN_ON_MS;
  draw();
}

void nextPage() {
  if (!g_screenOn) {
    screenOn();
    return;
  }
  g_page = (g_page + 1) % PAGES;
  g_screenUntil = millis() + SCREEN_ON_MS;
  draw();
}

bool screenActive() { return g_screenOn; }

void draw() {
  if (!g_oledOk || !g_screenOn) return;
  oled.clearBuffer();
  switch (g_page) {
    case 0: drawPage1(); break;
    case 1: drawPage2(); break;
    case 2: drawPage3(); break;
    default: drawPage4(); break;
  }
  oled.sendBuffer();
}

void splash(const char* line2) {
  if (!g_oledOk) return;
  oled.setPowerSave(0);
  oled.clearBuffer();
  oled.setFont(u8g2_font_10x20_t_cyrillic);
  oled.drawUTF8(14, 28, "vabuGEOde");
  oled.setFont(u8g2_font_6x13_t_cyrillic);
  oled.drawUTF8(0, 52, line2);
  oled.sendBuffer();
  g_screenOn = true;
  g_screenUntil = millis() + 2500;
}

void sleep() {
  buzzer(false);
  led(0, 0, 0);
  g_pattern = nullptr;
  if (g_oledOk) oled.setPowerSave(1);
  g_screenOn = false;
}

}  // namespace ui
