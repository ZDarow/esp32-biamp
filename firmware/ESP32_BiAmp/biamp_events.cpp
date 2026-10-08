#include "biamp_events.h"

void logEvent(uint8_t type, uint16_t val) {
  extern portMUX_TYPE ev_mux;
  extern EvLog evbuf[16];
  extern uint8_t ev_idx;
  portENTER_CRITICAL(&ev_mux);
  uint8_t idx = ev_idx & 15;
  ev_idx++;
  evbuf[idx].ms = millis();
  evbuf[idx].type = type;
  evbuf[idx].val = val;
  portEXIT_CRITICAL(&ev_mux);
}

void dumpEventLog(void) {
  extern portMUX_TYPE ev_mux;
  extern EvLog evbuf[16];
  extern uint8_t ev_idx;
  EvLog snap[16];
  portENTER_CRITICAL(&ev_mux);
  uint8_t start = ev_idx & 15;
  for (uint8_t i = 0; i < 16; i++) snap[i] = evbuf[(start + i) & 15];
  portEXIT_CRITICAL(&ev_mux);
  for (uint8_t i = 0; i < 16; i++) {
    if (!snap[i].type) continue;
    Serial.print(snap[i].ms); Serial.print(F(" t=")); Serial.print(snap[i].type);
    Serial.print(F(" v=")); Serial.println(snap[i].val);
  }
}
