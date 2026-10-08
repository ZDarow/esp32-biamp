#ifndef BIAMP_EVENTS_H
#define BIAMP_EVENTS_H

#include <stdint.h>
#include "biamp_globals.h"

#ifdef __cplusplus
extern "C" {
#endif

void logEvent(uint8_t type, uint16_t val);
void dumpEventLog(void);

#ifdef __cplusplus
}
#endif

#endif
