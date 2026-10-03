// Формат обмена сниффера с ПК по нативному USB.
//
// Управление — ASCII-строки с \n, данные — двоичные блоки без разделителей.
// Смешивать их в одном потоке нельзя, поэтому порядок строго задан:
// на START плата шлёт заголовок, затем кадры, затем трейлер и строку END.
//
//   ПК → S3 : "PING\n" | "INFO\n" | "START <секунды>\n" | "STOP\n"
//   S3 → ПК : "PONG\n" | "INFO ...\n" | <заголовок 32 Б> <кадры> <трейлер 32 Б> "END ...\n"
//
// Файл из этих же констант читает firmware/tools/capture-analyze.py, поэтому
// правка здесь требует правки CAP_* в том скрипте.
#pragma once

#include <stdint.h>

// "BAM1" и "END1" в записи little-endian.
#define CAP_MAGIC     0x314D4142u
#define CAP_MAGIC_END 0x31444E45u

#define CAP_VERSION   1
#define CAP_ZONES     2
#define CAP_CHANNELS  4
#define CAP_BITS      16
#define CAP_SLOT_BITS 16
#define CAP_FRAME_BYTES (CAP_CHANNELS * CAP_BITS / 8)

// Сколько кадров читает задача зоны за один вызов и сколько кадров склеивает
// задача упаковки в один блок для USB.
#define CAP_READ_FRAMES 64
#define CAP_OUT_BLOCK   512

// Глубина кольца зоны в кадрах. 2048 кадров при 44.1 кГц — это 46 мс запаса
// на момент, пока USB занят: 4 КБ блок уходит по USB дольше, чем накапливается.
#define CAP_ZRING_FRAMES 2048
#define CAP_ZRING_MASK   (CAP_ZRING_FRAMES - 1)

// Флаги в заголовке.
#define CAP_FLAG_MSB_JUSTIFIED 0x1u
#define CAP_FLAG_SHORT_SLOTS   0x2u

typedef struct {
  uint32_t magic;
  uint16_t version;
  uint16_t channels;
  uint16_t bits;
  uint16_t slot_bits;
  uint32_t declared_rate;
  uint32_t frame_bytes;
  uint32_t zones;
  uint32_t flags;
  uint32_t reserved;
} cap_header_t;

typedef struct {
  uint32_t frames;
  uint32_t dropped_z1;
  uint32_t dropped_z2;
  uint32_t max_skew_frames;
  uint32_t errors;
  uint32_t elapsed_ms;
  uint32_t reserved;
  uint32_t magic_end;
} cap_trailer_t;

_Static_assert(sizeof(cap_header_t) == 32, "заголовок должен быть 32 байта");
_Static_assert(sizeof(cap_trailer_t) == 32, "трейлер должен быть 32 байта");