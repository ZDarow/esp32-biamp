#ifndef BIAMP_GLOBALS_H
#define BIAMP_GLOBALS_H

#include <Arduino.h>
#include <atomic>
#include "AudioTools.h"
#include "BluetoothA2DPSink.h"
#include "BluetoothSerial.h"
#include <Preferences.h>

// ── Пины ──────────────────────────────────────────────────────────────
#define Z1_BCK 4
#define Z1_LCK 15
#define Z1_DIN 2
#define Z2_BCK 25
#define Z2_LCK 27
#define Z2_DIN 26

// ── Аудиопараметры ───────────────────────────────────────────────────
#define SAMPLE_RATE     44100
#define BITS_PER_SAMPLE 16
#define RAMP_K          0.001f
#define FADE_K          0.005f
#define FADE_EPS        5.0e-4f
#define RATIO48         (48000.0f / 44100.0f)

// ── NVS ───────────────────────────────────────────────────────────────
#define NVS_NS          "biampv12"
#define NVS_SAVE_DELAY  2000

// ── Идентификация ─────────────────────────────────────────────────────
#define DEVICE_NAME     "ESP32 BiAmp Speaker"

// ── Задержки ──────────────────────────────────────────────────────────
#define DELAY_BUF_SIZE  256
#define DELAY_BUF_MASK  (DELAY_BUF_SIZE - 1)
#define MAX_DELAY_SAMPLES 220

// ── Буферы и кольцо ───────────────────────────────────────────────────
#define BLOCK_FRAMES    256
#define RING_FRAMES     4096
#define RING_MASK       (RING_FRAMES - 1)
#define BT_ESP_I2S_BUFFERS 4
#define I2S_WRITE_TIMEOUT_MS 20
#define STARVE_GAP_MS   80
#define AUDIO_TASK_STACK 2048
#define AUDIO_TASK_PRIO  6
#define AUDIO_TASK_CORE  1

// ── Тест-сигнал ───────────────────────────────────────────────────────
#define TEST_VOL_DEFAULT 0.04f
#define TEST_VOL_MAX     0.06f

// ── Глобальные объекты ────────────────────────────────────────────────
extern I2SStream i2s_z1, i2s_z2;
extern BluetoothA2DPSink a2dp_sink;
extern BluetoothSerial SerialBT;
extern Preferences prefs;

// ── Глобальные переменные ─────────────────────────────────────────────
extern float vol_z[2];
extern bool  muted_z[2];
extern float fc_hz;
extern float sub_hz;
extern bool  sub_on;
extern float tlf_db, thf_db, eq_db[3];
extern float bal;
extern uint8_t xo_type;
extern bool  xo_on;
extern bool  lr_swap;
extern bool  dup_out;
extern float ch_hp[4], ch_lp[4];
extern uint16_t ch_delay[4];

extern bool paramsDirty;
extern uint32_t paramsChangedAt;
extern std::atomic<bool> ui_dirty;
extern std::atomic<bool> bt_connected;
extern std::atomic<bool> is_playing;
extern std::atomic<bool> src_48k;
extern std::atomic<uint32_t> g_in_frames;
extern std::atomic<uint8_t> test_mode;
extern std::atomic<float>   test_freq;
extern std::atomic<float>   test_vol;
extern std::atomic<uint32_t> starve_cnt, starve_max_ms;
extern std::atomic<uint32_t> starve_last_ms;
extern std::atomic<bool> dsp_reset_pending;
extern std::atomic<bool> dsp_fade_pending;
extern std::atomic<uint32_t> dsp_max_us, wr_max_us;
extern std::atomic<uint32_t> loop_max_ms, nvs_max_ms;
extern std::atomic<uint32_t> und_z[2];
extern std::atomic<uint32_t> clip_cnt[4];
extern std::atomic<uint32_t> ring_drops;
extern std::atomic<uint32_t> audio_blocks, audio_frames, audio_idle;

// ── Структуры ─────────────────────────────────────────────────────────
struct EvLog { uint32_t ms; uint8_t type; uint16_t val; };

struct AudioParams {
  float cb[8][3];
  float ca[8][2];
  float chb[4][2][3];
  float cha[4][2][2];
  float tlf, thf;
  float bl, br;
  float vol[2];
  bool  mute[2];
  uint16_t dly[4];
  bool  swap;
  bool  dup;
};

struct PBlob {
  uint32_t version;
  float v0, v1, fc, hp, tlf, thf, eq0, eq1, eq2, bal, tvol;
  float chh[4], chl[4];
  uint16_t dly[4];
  uint8_t m0, m1, sub, xot, swp, xoon;
} __attribute__((packed));

#endif
