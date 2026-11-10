#pragma once
#include <stdint.h>
#include <string.h>
#include "proto.h"

namespace hike {
enum Phase : uint32_t { IDLE=0, ACTIVE=1, RECOVERY=2, ENDED=3, ERROR=4 };
enum Action : uint8_t { START=1, FINISH=2, RESUME=3 };
struct State {
  uint32_t magic=0x48494b31, revision=1, id=0, phase=IDLE, endedAt=0;
  uint32_t operation=0, action=0, expectedId=0, expectedRevision=0, checksum=0;
};
static_assert(sizeof(State)==40, "persistent hike layout");
inline uint32_t checksum(const State& s) {
  const uint32_t words[]={s.magic,s.revision,s.id,s.phase,s.endedAt,s.operation,s.action,s.expectedId,s.expectedRevision};
  uint32_t hash=2166136261u;
  for (auto word:words) for (int i=0;i<4;i++) { hash=(hash ^ (word & 255))*16777619u; word>>=8; }
  return hash;
}
inline void seal(State& s) { s.checksum=checksum(s); }
inline bool valid(const State& s) {
  return s.magic==0x48494b31 && s.revision && s.phase<=ENDED &&
      ((s.phase==IDLE)==(s.id==0)) && s.checksum==checksum(s);
}
inline bool replay(const State& s,uint8_t action,uint32_t id,uint32_t revision,uint32_t operation) {
  return valid(s) && operation && s.operation==operation && s.action==action &&
      s.expectedId==id && s.expectedRevision==revision;
}
template<class Write,class Read>
bool commit(State& current,const State& candidate,Write write,Read read) {
  if (!valid(candidate) || !write(candidate)) return false;
  State check;
  if (!read(check) || !valid(check) || memcmp(&candidate,&check,sizeof(check))) return false;
  current=candidate; return true;
}
inline State afterBoot(State state,bool cold) {
  if (!valid(state)) { state.phase=ERROR; return state; }
  if (cold && state.phase==ACTIVE) {
    if (state.revision==UINT32_MAX) { state.phase=ERROR; return state; }
    state.phase=RECOVERY; state.revision++; seal(state);
  }
  return state;
}
// Computes a candidate only. Callers publish it ONLY after durable storage succeeds.
inline uint8_t transition(const State& s,uint8_t action,uint32_t id,uint32_t revision,
                          uint32_t operation,uint32_t newId,uint32_t now,State& next) {
  next=s;
  if (!valid(s) || !operation || action<START || action>RESUME) return proto::ACK_BAD_ARGS;
  if (replay(s,action,id,revision,operation)) return proto::ACK_OK;
  if (s.id!=id || s.revision!=revision || revision==UINT32_MAX) return proto::ACK_BUSY;
  if (action==START) {
    if ((s.phase!=IDLE && s.phase!=ENDED) || !newId || newId<=s.id) return proto::ACK_BUSY;
    next.id=newId; next.phase=ACTIVE; next.endedAt=0;
  } else if (action==FINISH) {
    if (s.phase!=ACTIVE && s.phase!=RECOVERY) return proto::ACK_BUSY;
    next.phase=ENDED; next.endedAt=now;
  } else {
    if (s.phase!=RECOVERY) return proto::ACK_BUSY;
    next.phase=ACTIVE;
  }
  next.revision++; next.operation=operation; next.action=action;
  next.expectedId=id; next.expectedRevision=revision; seal(next);
  return proto::ACK_OK;
}
} // namespace hike
