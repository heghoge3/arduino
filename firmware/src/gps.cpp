#include "gps.h"

#include <Arduino.h>
#include <math.h>

#include "clock.h"
#include "pins.h"

namespace gps {

namespace {

nmea::Parser g_parser;
bool g_on = false;
bool g_newFix = false;
uint32_t g_lastFixCount = 0;
bool g_echo = false;
uint32_t g_lastSentenceMs = 0;
uint32_t g_onSinceMs = 0;

#ifdef FAKE_SENSORS
// Эмуляция: идём от точки старта, раз в 40 мин 3 минуты «подмены» в известную ложную точку
uint32_t g_fakeLastMs = 0;
double g_fakeLat = 60.1580, g_fakeLon = 30.5150;
float g_fakeHeading = 40;
bool g_fakeAnchored = false;

void fakeTick() {
  uint32_t ms = millis();
  if (ms - g_fakeLastMs < 1000) return;
  g_fakeLastMs = ms;
  if (ms - g_onSinceMs < 4000) return;  // «горячий старт»
  g_fakeHeading += (random(-100, 101) / 100.0f) * 8;
  double d = 1.2;  // м за секунду
  g_fakeLat += d * cos(g_fakeHeading * PI / 180) / 111320.0;
  g_fakeLon += d * sin(g_fakeHeading * PI / 180) / (111320.0 * cos(g_fakeLat * PI / 180));

  bool spoof = (ms / 1000) % 600 >= 480;  // последние 2 мин каждых 10 мин
  double lat = spoof ? 60.0346 : g_fakeLat;
  double lon = spoof ? 30.5693 : g_fakeLon;
  uint32_t t = clockx::now();
  char body[120], line[140];
  int dlat = (int)fabs(lat), dlon = (int)fabs(lon);
  double mlat = (fabs(lat) - dlat) * 60, mlon = (fabs(lon) - dlon) * 60;
  uint32_t sod = t % 86400;
  // Дата для RMC из Unix-времени
  long z = t / 86400 + 719468, era = z / 146097;
  unsigned doe = z - era * 146097, yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100), mp = (5 * doy + 2) / 153;
  unsigned day = doy - (153 * mp + 2) / 5 + 1, mon = mp < 10 ? mp + 3 : mp - 9;
  int year = yoe + era * 400 + (mon <= 2);

  snprintf(body, sizeof(body), "GNRMC,%02lu%02lu%02lu.000,A,%02d%07.4f,N,%03d%07.4f,E,2.3,%.1f,%02u%02u%02d,,,A",
           sod / 3600, (sod / 60) % 60, sod % 60, dlat, mlat, dlon, mlon, g_fakeHeading < 0 ? g_fakeHeading + 360 : fmod(g_fakeHeading, 360),
           day, mon, year % 100);
  nmea::buildCommand(body, line, sizeof(line));
  g_parser.parseLine(line);
  snprintf(body, sizeof(body), "GNGGA,%02lu%02lu%02lu.000,%02d%07.4f,N,%03d%07.4f,E,1,%02d,%.1f,%.1f,M,15.0,M,,",
           sod / 3600, (sod / 60) % 60, sod % 60, dlat, mlat, dlon, mlon, 6, spoof ? 0.7 : 1.1, spoof ? 12.0 : 70.0);
  nmea::buildCommand(body, line, sizeof(line));
  g_parser.parseLine(line);
  // Спутники: при подмене — подозрительно одинаковый сигнал
  snprintf(body, sizeof(body), "GPGSA,A,3,03,06,09,12,15,18,,,,,,,1.6,%.1f,1.3,1", spoof ? 0.7 : 1.1);
  nmea::buildCommand(body, line, sizeof(line));
  g_parser.parseLine(line);
  // Два сообщения GSV: 6 спутников, как в GGA/GSA (проверке SNR нужно ≥5)
  if (spoof) snprintf(body, sizeof(body), "GPGSV,2,1,06,03,40,100,41,06,40,100,42,09,40,100,41,12,40,100,42,0");
  else snprintf(body, sizeof(body), "GPGSV,2,1,06,03,40,100,23,06,40,100,38,09,40,100,31,12,40,100,44,0");
  nmea::buildCommand(body, line, sizeof(line));
  g_parser.parseLine(line);
  if (spoof) snprintf(body, sizeof(body), "GPGSV,2,2,06,15,40,100,41,18,40,100,42,0");
  else snprintf(body, sizeof(body), "GPGSV,2,2,06,15,40,100,27,18,40,100,35,0");
  nmea::buildCommand(body, line, sizeof(line));
  g_parser.parseLine(line);
  g_lastSentenceMs = ms;
}
#endif

}  // namespace

void begin() {
  pinMode(PIN_GPS_EN, OUTPUT);
  digitalWrite(PIN_GPS_EN, HIGH);  // выкл
  pinMode(PIN_GPS_RX, INPUT);
  pinMode(PIN_GPS_TX, INPUT);
  g_on = false;
}

void power(bool on) {
  if (on == g_on) return;
  g_on = on;
  if (on) {
    digitalWrite(PIN_GPS_EN, LOW);
    // 9600 бод ≈ 1 КБ/с: стандартных 256 байт хватает на ~0,25 с. Запись на SD или выгрузка по BLE
    // иногда держат цикл дольше — без запаса теряются предложения NMEA
    Serial1.setRxBufferSize(2048);
    Serial1.begin(9600, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
    g_onSinceMs = millis();
  } else {
    Serial1.end();
    // Иначе модуль паразитно питается через TX/RX (ТЗ, раздел 2)
    pinMode(PIN_GPS_RX, INPUT);
    pinMode(PIN_GPS_TX, INPUT);
    digitalWrite(PIN_GPS_EN, HIGH);
    g_parser.reset();
    g_newFix = false;
    g_lastFixCount = 0;
  }
}

bool isOn() { return g_on; }

#ifdef FAKE_SENSORS
bool fakeAnchor(double lat, double lon) {
  if (g_fakeAnchored) return false;
  g_fakeAnchored = true;
  g_fakeLat = lat;
  g_fakeLon = lon;
  return true;
}
#endif

void poll() {
  if (!g_on) return;
#ifdef FAKE_SENSORS
  fakeTick();
#else
  while (Serial1.available()) {
    char c = Serial1.read();
    if (g_echo) Serial.write(c);
    if (g_parser.feed(c)) g_lastSentenceMs = millis();
  }
#endif
  // Новый фикс — по RMC с достоверной позицией (раз в секунду)
  if (g_parser.validFixes() != g_lastFixCount) {
    g_lastFixCount = g_parser.validFixes();
    g_newFix = true;
  }
}

const nmea::Parser& parser() { return g_parser; }

bool takeNewFix() {
  bool v = g_newFix;
  g_newFix = false;
  return v && g_parser.fix().valid;
}

bool alive() { return g_on && millis() - g_lastSentenceMs < 3000; }

uint32_t onSeconds() { return g_on ? (millis() - g_onSinceMs) / 1000 : 0; }

void sendCommand(const char* body) {
  char line[96];
  if (!g_on) return;
  size_t n = nmea::buildCommand(body, line, sizeof(line));
  if (n) Serial1.write(reinterpret_cast<const uint8_t*>(line), n);
}

void setEcho(bool on) { g_echo = on; }

}  // namespace gps
