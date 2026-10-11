#ifndef BIAMP_AUDIO_H
#define BIAMP_AUDIO_H

#include <stdint.h>
#include "biamp_globals.h"

#ifdef __cplusplus
extern "C" {
#endif

void audioTask(void *pvParameters);
void resetDspState(void);
void fadeOutDsp(void);
void processBlock(const int16_t *in, int n);
void refreshParams(void);
void computeTargets(bool test_on, float tgt[4]);
int genTestBlock(int16_t *out, int maxn);

void ringPush(const int16_t *frm, int n);
int ringPop(int16_t *dst, int maxn);
void ringFlush(void);
uint32_t ringLevel(void);
bool ringSelfTest(void);

void write_data_stream(const uint8_t *data, uint32_t length);
void bt_state_cb(esp_a2d_connection_state_t state, void *);
void audio_state_cb(esp_a2d_audio_state_t state, void *);
void on_sample_rate(uint16_t rate);

#ifdef __cplusplus
}
#endif

#endif
