#include "proto.h"

#include <string.h>

namespace proto {

namespace {

// Запись little-endian с контролем места
struct Writer {
  uint8_t* buf;
  size_t cap;
  size_t pos = 0;
  bool ok = true;

  Writer(uint8_t* b, size_t c) : buf(b), cap(c) {}

  void u8(uint8_t v) {
    if (pos + 1 > cap) { ok = false; return; }
    buf[pos++] = v;
  }
  void u16(uint16_t v) { u8(v & 0xFF); u8(v >> 8); }
  void i16(int16_t v) { u16(static_cast<uint16_t>(v)); }
  void u32(uint32_t v) { u16(v & 0xFFFF); u16(v >> 16); }
  void i32(int32_t v) { u32(static_cast<uint32_t>(v)); }
  void bytes(const void* p, size_t n) {
    if (pos + n > cap) { ok = false; return; }
    memcpy(buf + pos, p, n);
    pos += n;
  }
  void header(uint8_t type) { u8(VERSION); u8(type); }
  size_t done() const { return ok ? pos : 0; }
};

struct Reader {
  const uint8_t* buf;
  size_t len;
  size_t pos = 0;
  bool ok = true;

  Reader(const uint8_t* b, size_t l) : buf(b), len(l) {}

  uint8_t u8() {
    if (pos + 1 > len) { ok = false; return 0; }
    return buf[pos++];
  }
  uint16_t u16() { uint16_t lo = u8(); return lo | (uint16_t(u8()) << 8); }
  int16_t i16() { return static_cast<int16_t>(u16()); }
  uint32_t u32() { uint32_t lo = u16(); return lo | (uint32_t(u16()) << 16); }
  int32_t i32() { return static_cast<int32_t>(u32()); }
  bool atEnd() const { return ok && pos == len; }
};

}  // namespace

size_t utf8Limit(const char* s, size_t len, size_t maxBytes) {
  if (len <= maxBytes) return len;
  size_t n = maxBytes;
  // Откатываемся, пока следующий байт — продолжение многобайтового символа (10xxxxxx)
  while (n > 0 && (static_cast<uint8_t>(s[n]) & 0xC0) == 0x80) n--;
  return n;
}

size_t encodeStatus(const Status& m, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_STATUS);
  w.u32(m.time); w.u8(m.mode); w.u8(m.flags); w.u8(m.fix);
  w.u8(m.satsUsed); w.u8(m.satsView); w.u8(m.hdop10);
  w.i32(m.lat7); w.i32(m.lon7);
  w.u8(m.src); w.u8(m.spoofMask);
  w.i16(m.altGps10);
  w.u16(m.speed10); w.u16(m.course10);
  w.i16(m.tempRaw100); w.i16(m.tempCorr100);
  w.u16(m.hum100);
  w.u32(m.pressPa);
  w.i16(m.altBaro10); w.i16(m.trend100);
  w.u16(m.battMv); w.i16(m.battMa10); w.u8(m.battPct); w.u16(m.usedMah);
  w.u32(m.steps); w.u16(m.heading10); w.u32(m.distM);
  w.u16(m.hoursLeft10); w.u16(m.seq);
  w.u8(m.alerts);
  return w.done();
}

size_t encodeSats(const Sat* sats, size_t n, uint8_t* out, size_t cap) {
  if (n > MAX_SATS) n = MAX_SATS;
  Writer w(out, cap);
  w.header(MSG_SATS);
  w.u8(static_cast<uint8_t>(n));
  for (size_t i = 0; i < n; i++) {
    w.u8(sats[i].system); w.u8(sats[i].prn); w.u8(sats[i].snr); w.u8(sats[i].used ? 1 : 0);
  }
  return w.done();
}

size_t encodeInfo(const Info& m, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_INFO);
  w.u8(m.fwMajor); w.u8(m.fwMinor); w.u8(m.fwPatch); w.u8(m.chip);
  w.u32(m.uptime); w.u32(m.sdTotalMb); w.u32(m.sdFreeMb); w.u8(m.gnssMask);
  w.u32(m.hikeStart);
  w.u8(m.gpsMode); w.u16(m.gpsPulseS); w.u16(m.beaconS);
  uint8_t n = m.nChecks > MAX_CHECKS ? MAX_CHECKS : m.nChecks;
  w.u8(n);
  for (uint8_t i = 0; i < n; i++) {
    w.u8(m.checks[i].id); w.u8(m.checks[i].level); w.u8(m.checks[i].code); w.u8(m.checks[i].addr);
  }
  if (m.hasSettings) { w.i16(m.tempOffset100); w.u16(m.strideCm); }
  return w.done();
}

size_t encodeMark(const Mark& m, uint8_t* out, size_t cap) {
  size_t tl = utf8Limit(m.text, m.textLen, MAX_TEXT);
  Writer w(out, cap);
  w.header(MSG_MARK);
  w.u16(m.id); w.u32(m.time); w.u8(m.type); w.i32(m.lat7); w.i32(m.lon7);
  w.u8(static_cast<uint8_t>(tl));
  w.bytes(m.text, tl);
  return w.done();
}

size_t encodePoint(const Point& m, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_POINT);
  w.u32(m.time); w.i32(m.lat7); w.i32(m.lon7);
  w.u8((m.src & 0x03) | (m.spoof ? 0x04 : 0) | (m.startAnchor ? 0x08 : 0)); w.u8(m.spoofMask);
  w.i32(m.spoofLat7); w.i32(m.spoofLon7);
  w.i16(m.tempRaw100); w.i16(m.tempCorr100);
  w.u16(m.hum100); w.u32(m.pressPa);
  w.i16(m.altBaro10); w.i16(m.altGps10);
  w.u16(m.battMv); w.u8(m.battPct); w.u8(m.sats); w.u8(m.hdop10);
  w.u32(m.steps); w.u16(m.heading10); w.u16(m.speed10);
  return w.done();
}

size_t encodeSyncDone(uint8_t what, uint32_t count, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_SYNC_DONE);
  w.u8(what); w.u32(count);
  return w.done();
}

size_t encodeAck(uint8_t cmd, uint8_t result, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_ACK);
  w.u8(cmd); w.u8(result);
  return w.done();
}

size_t encodeHikeResult(uint8_t cmd, uint8_t result, uint32_t hike, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_HIKE_RESULT);
  w.u8(cmd); w.u8(result); w.u32(hike);
  return w.done();
}

size_t encodeAlert(uint32_t time, uint8_t alerts, uint8_t fresh, uint8_t battPct, uint8_t spoofMask, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.header(MSG_ALERT);
  w.u32(time); w.u8(alerts); w.u8(fresh); w.u8(battPct); w.u8(spoofMask);
  return w.done();
}

size_t encodeLog(const LogLine& m, uint8_t* out, size_t cap) {
  size_t tl = utf8Limit(m.text, m.textLen, MAX_LOG_TEXT);
  Writer w(out, cap);
  w.header(MSG_LOG);
  w.u32(m.seq); w.u32(m.time); w.u32(m.uptime); w.u8(m.level); w.u8(m.module);
  w.u8(static_cast<uint8_t>(tl));
  w.bytes(m.text, tl);
  return w.done();
}

size_t encodeBeacon(const Beacon& m, uint8_t* out, size_t cap) {
  Writer w(out, cap);
  w.u8(VERSION); w.u8(BEACON_TAG);
  w.u8(m.mode); w.u8(m.flags); w.u8(m.alerts);
  w.i16(m.tempCorr100); w.u8(m.battPct); w.u8(m.satsUsed);
  w.u16(m.press10); w.u32(m.lastRecord); w.u16(m.hikeId); w.u8(m.counter);
  return w.done();
}

bool decodePoint(const uint8_t* in, size_t len, Point& m) {
  if (len != POINT_SIZE) return false;
  Reader r(in, len);
  if (r.u8() != VERSION || r.u8() != MSG_POINT) return false;
  m.time = r.u32(); m.lat7 = r.i32(); m.lon7 = r.i32();
  uint8_t fl = r.u8();
  m.src = fl & 0x03; m.spoof = (fl & 0x04) != 0; m.startAnchor = (fl & 0x08) != 0;
  m.spoofMask = r.u8(); m.spoofLat7 = r.i32(); m.spoofLon7 = r.i32();
  m.tempRaw100 = r.i16(); m.tempCorr100 = r.i16();
  m.hum100 = r.u16(); m.pressPa = r.u32();
  m.altBaro10 = r.i16(); m.altGps10 = r.i16();
  m.battMv = r.u16(); m.battPct = r.u8(); m.sats = r.u8(); m.hdop10 = r.u8();
  m.steps = r.u32(); m.heading10 = r.u16(); m.speed10 = r.u16();
  return r.atEnd();
}

bool decodeMark(const uint8_t* in, size_t len, Mark& m) {
  Reader r(in, len);
  if (r.u8() != VERSION || r.u8() != MSG_MARK) return false;
  m.id = r.u16(); m.time = r.u32(); m.type = r.u8(); m.lat7 = r.i32(); m.lon7 = r.i32();
  m.textLen = r.u8();
  if (!r.ok || m.textLen > MAX_TEXT || r.pos + m.textLen != len) return false;
  memcpy(m.text, in + r.pos, m.textLen);
  m.text[m.textLen] = 0;
  return true;
}

bool decodeLog(const uint8_t* in, size_t len, LogLine& m) {
  Reader r(in, len);
  if (r.u8() != VERSION || r.u8() != MSG_LOG) return false;
  m.seq = r.u32(); m.time = r.u32(); m.uptime = r.u32(); m.level = r.u8(); m.module = r.u8();
  m.textLen = r.u8();
  if (!r.ok || m.textLen > MAX_LOG_TEXT || r.pos + m.textLen != len) return false;
  memcpy(m.text, in + r.pos, m.textLen);
  m.text[m.textLen] = 0;
  return true;
}

uint8_t decodeCommand(const uint8_t* in, size_t len, Command& c) {
  memset(&c, 0, sizeof(c));
  if (len < 2 || in[0] != VERSION) return ACK_UNSUPPORTED;
  Reader r(in, len);
  r.u8();
  c.code = r.u8();
  switch (c.code) {
    case CMD_SET_TIME: c.time = r.u32(); c.tzMin = r.i16(); break;
    case CMD_PHONE_POS:
      c.lat7=r.i32(); c.lon7=r.i32(); c.accuracyM=r.u16();
      if (r.atEnd()&&(c.lat7 < -900000000 || c.lat7 > 900000000 || c.lon7 < -1800000000 || c.lon7 > 1800000000 || c.accuracyM==0)) return ACK_BAD_ARGS;
      break;
    case CMD_CALIB_COMPASS:
      c.flag = r.u8();
      return r.atEnd() && c.flag <= 1 ? ACK_OK : ACK_BAD_ARGS;
    case CMD_RECORD:
    case CMD_SESSION:
    case CMD_ACK_ALERT:
    case CMD_SET_DEBUG: c.flag = r.u8(); break;
    case CMD_MARK: {
      c.markType = r.u8();
      c.textLen = r.u8();
      if (!r.ok || c.textLen > MAX_TEXT || r.pos + c.textLen != len) return ACK_BAD_ARGS;
      memcpy(c.text, in + r.pos, c.textLen);
      c.text[c.textLen] = 0;
      return c.markType <= 6 ? ACK_OK : ACK_BAD_ARGS;
    }
    case CMD_CLAP:
    case CMD_FIND:
    case CMD_GET_INFO:
    case CMD_BYE: break;
    case CMD_SET_INTERVALS:
      c.a = r.u16(); c.b = r.u16();
      if (r.atEnd() && (c.a < 1 || c.a > 3600 || c.b < 1 || c.b > 3600)) return ACK_BAD_ARGS;
      break;
    case CMD_CALIB_ALT: c.altDm = r.i16(); break;
    case CMD_BLACKLIST_V2:
    case CMD_BLACKLIST:
      c.blCount = r.u8();
      if (c.blCount > MAX_BLACKLIST) return ACK_BAD_ARGS;
      for (uint8_t i = 0; i < c.blCount; i++) {
        c.bl[i].lat7 = r.i32(); c.bl[i].lon7 = r.i32(); c.bl[i].radiusM = r.u16();
        if (c.bl[i].lat7 < -900000000 || c.bl[i].lat7 > 900000000 || c.bl[i].lon7 < -1800000000 || c.bl[i].lon7 > 1800000000 || c.bl[i].radiusM < 100 || c.bl[i].radiusM > 50000) return ACK_BAD_ARGS;
      }
      break;
    case CMD_SET_GPS_MODE:
      c.flag = r.u8(); c.a = r.u16();
      if (r.atEnd() && (c.flag > 1 || c.a < 10 || c.a > 600)) return ACK_BAD_ARGS;
      break;
    case CMD_SET_RADIO:
      c.a = r.u16();
      if (r.atEnd() && c.a != 0 && (c.a < 10 || c.a > 600)) return ACK_BAD_ARGS;
      break;
    case CMD_TEMP_OFFSET:
      c.altDm=r.i16();
      if (r.atEnd()&&(c.altDm < -2000 || c.altDm > 2000)) return ACK_BAD_ARGS;
      break;
    case CMD_SET_START:
      c.time=r.u32(); c.lat7=r.i32(); c.lon7=r.i32();
      if (r.atEnd()&&(c.lat7 < -900000000 || c.lat7 > 900000000 || c.lon7 < -1800000000 || c.lon7 > 1800000000 || !c.time)) return ACK_BAD_ARGS;
      break;
    case CMD_PDR_SETTINGS:
      c.a = r.u16(); c.altDm = r.i16(); c.flag = r.u8();
      if (r.atEnd() && (c.a < 30 || c.a > 150 || c.altDm < -1800 || c.altDm > 1800 || c.flag > 1)) return ACK_BAD_ARGS;
      break;
    case CMD_SYNC_TRACK:
    case CMD_SYNC_MARKS:
    case CMD_SYNC_LOG:
    case CMD_NEW_HIKE:
    case CMD_DELETE_HIKE: c.time = r.u32(); break;
    default: return ACK_UNSUPPORTED;
  }
  return r.atEnd() ? ACK_OK : ACK_BAD_ARGS;
}

}  // namespace proto
