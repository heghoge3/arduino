// Протокол BLE v2 — точное отражение docs/protocol.md.
// Чистый C++ без Arduino: собирается и тестируется на ПК (env:native).
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace proto {

constexpr uint8_t VERSION = 2;

// UUID (строки для NimBLE)
constexpr const char* SERVICE_UUID = "e0f30001-5642-4744-8a1e-76616275676f";
constexpr const char* CHAR_STATUS_UUID = "e0f30002-5642-4744-8a1e-76616275676f";
constexpr const char* CHAR_COMMAND_UUID = "e0f30003-5642-4744-8a1e-76616275676f";
constexpr const char* CHAR_EVENT_UUID = "e0f30004-5642-4744-8a1e-76616275676f";

constexpr uint16_t COMPANY_ID = 0xFFFF;  // тестовый id для маячка
constexpr uint8_t BEACON_TAG = 0x56;     // 'V'

// Сообщения устройства
enum Msg : uint8_t {
  MSG_STATUS = 0x01,
  MSG_SATS = 0x02,
  MSG_INFO = 0x03,
  MSG_MARK = 0x04,
  MSG_POINT = 0x05,
  MSG_SYNC_DONE = 0x06,
  MSG_ACK = 0x07,
  MSG_ALERT = 0x08,
  MSG_LOG = 0x09,
  MSG_HIKE_RESULT = 0x0A,
};

// Команды телефона
enum CmdCode : uint8_t {
  CMD_SET_TIME = 0x01,
  CMD_PHONE_POS = 0x02,
  CMD_RECORD = 0x03,
  CMD_MARK = 0x04,
  CMD_CLAP = 0x05,
  CMD_FIND = 0x06,
  CMD_SET_INTERVALS = 0x07,
  CMD_CALIB_ALT = 0x08,
  CMD_CALIB_COMPASS = 0x09,
  CMD_BLACKLIST = 0x0A,
  CMD_GET_INFO = 0x0B,
  CMD_SESSION = 0x0C,
  CMD_BYE = 0x0D,
  CMD_SET_GPS_MODE = 0x0E,
  CMD_SET_RADIO = 0x0F,
  CMD_SYNC_TRACK = 0x10,
  CMD_SYNC_MARKS = 0x11,
  CMD_ACK_ALERT = 0x12,
  CMD_SYNC_LOG = 0x13,
  CMD_SET_DEBUG = 0x14,
  CMD_NEW_HIKE = 0x15,
  CMD_DELETE_HIKE = 0x16,
  CMD_PDR_SETTINGS = 0x17,
};

enum AckResult : uint8_t {
  ACK_OK = 0,
  ACK_BAD_ARGS = 1,
  ACK_BUSY = 2,
  ACK_UNSUPPORTED = 3,
  ACK_NOT_BONDED = 4,
};

// Флаги STATUS
enum StatusFlag : uint8_t {
  F_RECORDING = 1 << 0,
  F_GPS_ON = 1 << 1,
  F_SPOOF = 1 << 2,
  F_TIME_OK = 1 << 3,
  F_CHARGING = 1 << 4,
  F_SD_OK = 1 << 5,
  F_LOW_BATT = 1 << 6,
  F_ALERT = 1 << 7,
};

// Флаги маячка
enum BeaconFlag : uint8_t {
  B_RECORDING = 1 << 0,
  B_FIX = 1 << 1,
  B_SPOOF = 1 << 2,
  B_TIME_OK = 1 << 3,
  B_WINDOW = 1 << 4,
  B_SD_OK = 1 << 5,
};

// Тревоги
enum Alert : uint8_t {
  AL_SPOOF = 1 << 0,
  AL_LOW_BATT = 1 << 1,
  AL_CRIT_BATT = 1 << 2,
  AL_SD_ERROR = 1 << 3,
};

constexpr int16_t NA16 = -32768;
constexpr uint8_t SRC_PAUSE = 3;  // точка на паузе: только датчики, без координат
constexpr uint8_t HDOP_NA = 255;
constexpr size_t MAX_TEXT = 60;
constexpr size_t MAX_SATS = 24;
constexpr size_t MAX_BLACKLIST = 24;
constexpr size_t MAX_CHECKS = 12;
constexpr size_t MAX_LOG_TEXT = 80;

constexpr size_t STATUS_SIZE = 64;
constexpr size_t POINT_SIZE = 51;
constexpr size_t BEACON_SIZE = 18;
constexpr size_t MAX_PACKET = 99;  // SATS с 24 спутниками

// ---- Сообщения: значения уже в масштабе протокола ----

struct Status {
  uint32_t time;
  uint8_t mode, flags, fix, satsUsed, satsView, hdop10;
  int32_t lat7, lon7;
  uint8_t src, spoofMask;
  int16_t altGps10;
  uint16_t speed10, course10;
  int16_t tempRaw100, tempCorr100;
  uint16_t hum100;
  uint32_t pressPa;
  int16_t altBaro10, trend100;
  uint16_t battMv;
  int16_t battMa10;
  uint8_t battPct;
  uint16_t usedMah;
  uint32_t steps;
  uint16_t heading10;
  uint32_t distM;
  uint16_t hoursLeft10;
  uint16_t seq;
  uint8_t alerts;
};

struct Sat {
  uint8_t system, prn, snr, used;
};

struct Check {
  uint8_t id, level, code, addr;
};

struct Info {
  uint8_t fwMajor, fwMinor, fwPatch, chip;
  uint32_t uptime, sdTotalMb, sdFreeMb;
  uint8_t gnssMask;
  uint32_t hikeStart;
  uint8_t gpsMode;
  uint16_t gpsPulseS, beaconS;
  uint8_t nChecks;
  Check checks[MAX_CHECKS];
};

struct Mark {
  uint16_t id;
  uint32_t time;
  uint8_t type;
  int32_t lat7, lon7;
  uint8_t textLen;
  char text[MAX_TEXT + 1];
};

struct Point {
  uint32_t time;
  int32_t lat7, lon7;
  uint8_t src;
  bool spoof;
  uint8_t spoofMask;
  int32_t spoofLat7, spoofLon7;
  int16_t tempRaw100, tempCorr100;
  uint16_t hum100;
  uint32_t pressPa;
  int16_t altBaro10, altGps10;
  uint16_t battMv;
  uint8_t battPct, sats, hdop10;
  uint32_t steps;
  uint16_t heading10, speed10;
};

// Строка журнала: level — биты 0–1 уровень, бит 7 «время проверено»
struct LogLine {
  uint32_t seq, time, uptime;
  uint8_t level, module, textLen;
  char text[MAX_LOG_TEXT + 1];
};

struct Beacon {
  uint8_t mode, flags, alerts;
  int16_t tempCorr100;
  uint8_t battPct, satsUsed;
  uint16_t press10;
  uint32_t lastRecord;
  uint16_t hikeId;
  uint8_t counter;
};

// Кодирование: возвращает длину пакета (0 — не влезло в буфер)
size_t encodeStatus(const Status& m, uint8_t* out, size_t cap);
size_t encodeSats(const Sat* sats, size_t n, uint8_t* out, size_t cap);
size_t encodeInfo(const Info& m, uint8_t* out, size_t cap);
size_t encodeMark(const Mark& m, uint8_t* out, size_t cap);
size_t encodePoint(const Point& m, uint8_t* out, size_t cap);
size_t encodeSyncDone(uint8_t what, uint32_t count, uint8_t* out, size_t cap);
size_t encodeAck(uint8_t cmd, uint8_t result, uint8_t* out, size_t cap);
size_t encodeHikeResult(uint8_t cmd, uint8_t result, uint32_t hike, uint8_t* out, size_t cap);
size_t encodeAlert(uint32_t time, uint8_t alerts, uint8_t fresh, uint8_t battPct, uint8_t spoofMask, uint8_t* out, size_t cap);
size_t encodeLog(const LogLine& m, uint8_t* out, size_t cap);
// Сводка маячка — 18 байт (без company id)
size_t encodeBeacon(const Beacon& m, uint8_t* out, size_t cap);

// Разбор POINT/MARK (нужен при синхронизации из двоичных файлов на SD и в тестах)
bool decodePoint(const uint8_t* in, size_t len, Point& m);
bool decodeMark(const uint8_t* in, size_t len, Mark& m);
bool decodeLog(const uint8_t* in, size_t len, LogLine& m);

// ---- Команды ----

struct BlEntry {
  int32_t lat7, lon7;
  uint16_t radiusM;
};

struct Command {
  uint8_t code;
  uint32_t time;          // SET_TIME, SYNC_TRACK/MARKS; SYNC_LOG — номер строки
  int16_t tzMin;          // SET_TIME
  int32_t lat7, lon7;     // PHONE_POS
  uint16_t accuracyM;     // PHONE_POS
  uint8_t flag;           // RECORD, CALIB_COMPASS, SESSION, SET_GPS_MODE (режим), ACK_ALERT (биты), SET_DEBUG
  uint8_t markType;       // MARK
  uint8_t textLen;
  char text[MAX_TEXT + 1];
  uint16_t a, b;          // SET_INTERVALS (в пути, стоянка); SET_GPS_MODE (a = период); SET_RADIO (a = маячок)
  int16_t altDm;          // CALIB_ALT, ×10 м
  uint8_t blCount;
  BlEntry bl[MAX_BLACKLIST];
};

// Разбор команды. Возвращает код ACK: OK, BAD_ARGS (длина/значения), UNSUPPORTED (версия/код)
uint8_t decodeCommand(const uint8_t* in, size_t len, Command& c);

// Код команды из сырого пакета (для ACK даже на битые аргументы)
inline uint8_t commandCode(const uint8_t* in, size_t len) { return len >= 2 ? in[1] : 0; }

// Обрезать UTF-8 до maxBytes, не разрезая символ. Возвращает длину.
size_t utf8Limit(const char* s, size_t len, size_t maxBytes);

}  // namespace proto
