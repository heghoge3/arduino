// Разбор NMEA от ATGM336H: RMC, GGA, GSA, GSV.
// Свой парсер вместо TinyGPSPlus: нужен SNR по системам (GSV) для проверки подмены,
// и так весь разбор тестируется на ПК.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace nmea {

enum System : uint8_t { SYS_GPS = 0, SYS_BDS = 1, SYS_GLO = 2, SYS_GAL = 3, SYS_UNKNOWN = 255 };

struct SatInfo {
  uint8_t system;
  uint8_t prn;
  uint8_t snr;  // 0 — не слышно
  bool used;
};

struct Fix {
  // Время UTC из RMC (дата + время)
  bool timeValid = false;
  uint32_t utc = 0;
  // Позиция
  bool valid = false;      // RMC статус A и качество GGA > 0
  double lat = 0, lon = 0;
  bool altValid = false;
  float altM = 0;
  uint8_t quality = 0;     // GGA: 0 нет, 1 GPS, 2 DGPS, 6 счисление
  uint8_t fixType = 1;     // GSA: 1 нет, 2 2D, 3 3D
  uint8_t satsUsed = 0;
  float hdop = 99.9f;
  float speedKmh = 0;
  float courseDeg = 0;
};

constexpr size_t MAX_SATS = 32;

class Parser {
 public:
  // Подать очередной символ из UART. true — разобрано полное корректное предложение.
  bool feed(char c);

  const Fix& fix() const { return fix_; }
  // Список спутников (из последних полных групп GSV по каждой системе)
  size_t satCount() const { return satCount_; }
  const SatInfo& sat(size_t i) const { return sats_[i]; }
  uint8_t systemsSeen() const { return systemsSeen_; }  // биты System

  // Счётчики для самопроверки
  uint32_t sentences() const { return sentences_; }
  uint32_t checksumErrors() const { return checksumErrors_; }
  // Сколько RMC с достоверной позицией разобрано — «новый фикс» (раз в секунду)
  uint32_t validFixes() const { return validFixes_; }

  // Разобрать готовую строку целиком (без \r\n). Для тестов и повторного использования.
  bool parseLine(const char* line);

  // Сбросить спутники и фикс (например, после выключения GPS)
  void reset();

 private:
  bool handle(char* body);
  void onRmc(char** f, int n);
  void onGga(char** f, int n);
  void onGsa(char** f, int n, uint8_t talkerSys);
  void onGsv(char** f, int n, uint8_t talkerSys);
  void rebuildSats();

  char buf_[100];
  size_t len_ = 0;
  bool inSentence_ = false;

  Fix fix_;
  uint32_t sentences_ = 0, checksumErrors_ = 0, validFixes_ = 0;
  uint8_t systemsSeen_ = 0;

  // Сбор GSV по системам: новая группа (номер сообщения 1) заменяет старую
  struct SysSats {
    uint8_t n = 0;
    uint8_t prn[16];
    uint8_t snr[16];
    uint8_t usedPrn[16];
    uint8_t nUsed = 0;
  };
  SysSats sys_[4];
  SatInfo sats_[MAX_SATS];
  size_t satCount_ = 0;
};

// Контрольная сумма NMEA: XOR всех символов между '$' и '*'
uint8_t checksum(const char* body, size_t len);

// Собрать команду «$<body>*CS\r\n» в out. Возвращает длину.
size_t buildCommand(const char* body, char* out, size_t cap);

// Unix-время из даты и времени UTC
uint32_t toUnix(int year, int month, int day, int hour, int minute, int second);

}  // namespace nmea
