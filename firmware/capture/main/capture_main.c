// Сниффер цифрового выхода Master (ESP32 BiAmp) на ESP32-S3.
//
// Что делает: читает оба I²S-потока Master в подчинённом режиме, склеивает их
// в один 4-канальный поток и отдаёт по нативному USB. Настройки Master,
// прошивка и протокол не меняются — измеряется ровно то, что физически уходит
// на провода, включая возможные ошибки на них.
//
// Порядок каналов в потоке: 0 — Z1 левый (НЧ), 1 — Z1 правый (ВЧ),
// 2 — Z2 левый, 3 — Z2 правый.
//
// Выравнивание зон. Склеивать надо строго по номеру кадра, а не по времени
// чтения: кадр N зоны Z1 и кадр N зоны Z2 пришли из одного блока Master, и
// только так можно потом измерить задержку между каналами. Поэтому читатели
// зон независимы, а упаковщик ждёт, пока в обоих кольцах есть кадры, и забирает
// их по индексу.
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "driver/i2s_std.h"
#include "driver/usb_serial_jtag.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_task_wdt.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "capture_pins.h"
#include "capture_proto.h"

static const char *TAG = "capture";

// Сторож задач. Подписываться надо каждой рабочей задаче: по умолчанию он
// проверяет только IDLE-задачи, а с задачами, закреплёнными за ядрами, IDLE
// может не добраться до обслуживания — тогда паника ложная, но она глушит
// весь захват. Вызывается из начала задачи, поэтому подписывает её саму.
#define CAP_WDT_INIT()                                   \
  do {                                                   \
    esp_task_wdt_add(NULL);                              \
  } while (0)
#define CAP_WDT_RESET() esp_task_wdt_reset()

// Задержка задачи в миллисекундах, которая не может превратиться в ноль тиков.
// vTaskDelay(0) не уступает CPU, и задача с приоритетом выше main задавливает
// его насмерть — плата замолкает, не печатая ни строки. Округление pdMS_TO_TICKS
// вниз на тике 10 мс давало ровно этот ноль, поэтому минимум в один тик
// зашит здесь.
static inline void cap_delay_ms(uint32_t ms) {
  TickType_t ticks = pdMS_TO_TICKS(ms);
  vTaskDelay(ticks > 0 ? ticks : 1);
}

// Частота в заголовке — не измерение, а объявление. Подчинённый режим берёт
// такт с провода, и настоящую частоту знает только ПК: он находит её по
// опорному тону тест-сигнала и пересчитывает отсчёты. Это значение нужно
// драйверу только для расчёта внутренних делителей MCLK.
#define CAP_DECLARED_RATE 44100

// Первые кадры после старта выбрасываются. Ровно в момент включения канала DMA
// может поймать половину кадра, и один-единственный сдвиг на слот в начале
// файла портит все измерения фазы. 2048 кадров — 46 мс, за это время заведомо
// набежали настоящие кадры.
#define CAP_WARMUP_FRAMES 2048

static i2s_chan_handle_t s_z1, s_z2;
static int16_t *s_ring[CAP_ZONES];
static uint32_t s_ring_w[CAP_ZONES];
static uint32_t s_ring_r[CAP_ZONES];
static portMUX_TYPE s_ring_mux = portMUX_INITIALIZER_UNLOCKED;

static volatile bool s_run;
static int64_t s_deadline_us;
static int64_t s_start_us;
static uint32_t s_warmup;
static uint32_t s_frames, s_dropped[CAP_ZONES], s_max_skew, s_errors;

static int usb_write(const void *data, size_t len) {
  const uint8_t *p = (const uint8_t *)data;
  size_t left = len;
  while (left > 0) {
    int w = usb_serial_jtag_write_bytes(p, left, pdMS_TO_TICKS(2000));
    if (w <= 0) return -1;
    p += (size_t)w;
    left -= (size_t)w;
  }
  return 0;
}

static void usb_puts(const char *s) { usb_write(s, strlen(s)); }

// ── чтение зон ──────────────────────────────────────────────────────────
static void zone_task(void *arg) {
  const uint32_t z = (uint32_t)(uintptr_t)arg;
  i2s_chan_handle_t h = (z == 0) ? s_z1 : s_z2;
  int16_t chunk[CAP_READ_FRAMES * 2];
  CAP_WDT_INIT();

  for (;;) {
    CAP_WDT_RESET();
    if (!s_run) {
      cap_delay_ms(5);
      continue;
    }
    size_t got = 0;
    esp_err_t err = i2s_channel_read(h, chunk, sizeof(chunk), &got, pdMS_TO_TICKS(100));
    if (err != ESP_OK || got < 4) {
      // Таймаут — это норма: хост ещё не включил звук. Остальное — сбой,
      // и о нём честнее сказать в отчёте, чем молча писать тишину.
      if (err != ESP_OK && err != ESP_ERR_TIMEOUT) s_errors++;
      continue;
    }
    const uint32_t frames = (uint32_t)(got / 4);
    portENTER_CRITICAL(&s_ring_mux);
    const uint32_t used = s_ring_w[z] - s_ring_r[z];
    const uint32_t space = (used >= CAP_ZRING_FRAMES) ? 0u : (CAP_ZRING_FRAMES - used);
    if (frames <= space) {
      for (uint32_t f = 0; f < frames; f++) {
        const uint32_t idx = ((s_ring_w[z] + f) & CAP_ZRING_MASK) * 2;
        s_ring[z][idx] = chunk[f * 2];
        s_ring[z][idx + 1] = chunk[f * 2 + 1];
      }
      s_ring_w[z] += frames;
    }
    portEXIT_CRITICAL(&s_ring_mux);
    // Часть блока не влезла — блок отбрасывается целиком. Запись частично
    // сдвинула бы нумерацию кадров, и дальше зоны встали бы вразнобой.
    if (frames > space) s_dropped[z] += frames;
  }
}

// ── склейка и выдача ────────────────────────────────────────────────────
static void packer_task(void *arg) {
  (void)arg;
  static int16_t out[CAP_OUT_BLOCK * CAP_CHANNELS];
  CAP_WDT_INIT();

  for (;;) {
    CAP_WDT_RESET();
    if (!s_run) {
      cap_delay_ms(2);
      continue;
    }
    uint32_t avail[CAP_ZONES];
    portENTER_CRITICAL(&s_ring_mux);
    for (int z = 0; z < CAP_ZONES; z++) avail[z] = s_ring_w[z] - s_ring_r[z];
    portEXIT_CRITICAL(&s_ring_mux);

    // Расхождение заполненности колец — это накопленный разрыв синхронизации
    // между зонами. Само по себе безобидно, но именно оно объясняет любую
    // измеренную задержку, поэтому уходит в отчёт.
    const uint32_t skew = (avail[0] > avail[1]) ? (avail[0] - avail[1]) : (avail[1] - avail[0]);
    if (skew > s_max_skew) s_max_skew = skew;

    if (s_warmup > 0) {
      if (avail[0] >= CAP_OUT_BLOCK && avail[1] >= CAP_OUT_BLOCK) {
        portENTER_CRITICAL(&s_ring_mux);
        s_ring_r[0] += CAP_OUT_BLOCK;
        s_ring_r[1] += CAP_OUT_BLOCK;
        portEXIT_CRITICAL(&s_ring_mux);
        s_warmup -= CAP_OUT_BLOCK;
      }
      cap_delay_ms(1);
      continue;
    }

    if (avail[0] < CAP_OUT_BLOCK || avail[1] < CAP_OUT_BLOCK) {
      if (avail[0] >= CAP_ZRING_FRAMES || avail[1] >= CAP_ZRING_FRAMES) {
        // Кольцо переполнено: USB не успевает. Продолжать со старого хвоста
        // нельзя — в файл уйдёт мусор, который потом принимают за дефект DSP.
        portENTER_CRITICAL(&s_ring_mux);
        for (int z = 0; z < CAP_ZONES; z++) {
          s_ring_r[z] = s_ring_w[z];
          s_dropped[z] += CAP_ZRING_FRAMES;
        }
        portEXIT_CRITICAL(&s_ring_mux);
        s_errors++;
      }
      cap_delay_ms(1);
      continue;
    }

    portENTER_CRITICAL(&s_ring_mux);
    const uint32_t r1 = s_ring_r[0], r2 = s_ring_r[1];
    for (uint32_t f = 0; f < CAP_OUT_BLOCK; f++) {
      const int16_t *p1 = &s_ring[0][((r1 + f) & CAP_ZRING_MASK) * 2];
      const int16_t *p2 = &s_ring[1][((r2 + f) & CAP_ZRING_MASK) * 2];
      out[f * CAP_CHANNELS + 0] = p1[0];
      out[f * CAP_CHANNELS + 1] = p1[1];
      out[f * CAP_CHANNELS + 2] = p2[0];
      out[f * CAP_CHANNELS + 3] = p2[1];
    }
    s_ring_r[0] = r1 + CAP_OUT_BLOCK;
    s_ring_r[1] = r2 + CAP_OUT_BLOCK;
    portEXIT_CRITICAL(&s_ring_mux);

    s_frames += CAP_OUT_BLOCK;
    if (usb_write(out, sizeof(out)) != 0) s_errors++;
  }
}

// ── команды с ПК ────────────────────────────────────────────────────────
static void stop_capture(void) {
  if (!s_run) return;
  s_run = false;
  const cap_trailer_t t = {
    .frames = s_frames,
    .dropped_z1 = s_dropped[0],
    .dropped_z2 = s_dropped[1],
    .max_skew_frames = s_max_skew,
    .errors = s_errors,
    .elapsed_ms = (uint32_t)((esp_timer_get_time() - s_start_us) / 1000),
    .reserved = 0,
    .magic_end = CAP_MAGIC_END,
  };
  usb_write(&t, sizeof(t));
  char msg[128];
  snprintf(msg, sizeof(msg), "END frames=%" PRIu32 " dropped=%" PRIu32 "/%" PRIu32
                             " skew=%" PRIu32 " errors=%" PRIu32 "\n",
           t.frames, t.dropped_z1, t.dropped_z2, t.max_skew_frames, t.errors);
  usb_puts(msg);
}

static bool start_capture(uint32_t seconds) {
  if (s_run) return false;

  s_frames = 0;
  s_max_skew = 0;
  s_errors = 0;
  s_dropped[0] = s_dropped[1] = 0;
  s_warmup = CAP_WARMUP_FRAMES;
  for (int z = 0; z < CAP_ZONES; z++) s_ring_w[z] = s_ring_r[z] = 0;

  // Перезапуск обоих каналов подряд: disable сбрасывает FIFO, поэтому первый
  // кадр у обеих зон соответствует одному моменту потока Master. Без этого
  // выравнивание зависело бы от того, сколько кадров успело набежать в зону,
  // которая стартовала первой, — величина случайная.
  ESP_ERROR_CHECK(i2s_channel_disable(s_z1));
  ESP_ERROR_CHECK(i2s_channel_disable(s_z2));
  cap_delay_ms(20);
  ESP_ERROR_CHECK(i2s_channel_enable(s_z1));
  ESP_ERROR_CHECK(i2s_channel_enable(s_z2));

  const cap_header_t h = {
    .magic = CAP_MAGIC,
    .version = CAP_VERSION,
    .channels = CAP_CHANNELS,
    .bits = CAP_BITS,
    .slot_bits = CAP_SLOT_BITS,
    .declared_rate = CAP_DECLARED_RATE,
    .frame_bytes = CAP_FRAME_BYTES,
    .zones = CAP_ZONES,
    .flags = CAP_FLAG_MSB_JUSTIFIED | CAP_FLAG_SHORT_SLOTS,
    .reserved = 0,
  };
  if (usb_write(&h, sizeof(h)) != 0) return false;

  const int64_t now = esp_timer_get_time();
  // Запас на прогрев: кадры не пишутся CAP_WARMUP_FRAMES, значит до реального
  // начала записи остаётся это время.
  const int64_t warmup_us = (int64_t)CAP_WARMUP_FRAMES * 1000000LL / CAP_DECLARED_RATE;
  s_start_us = now;
  s_deadline_us = now + (int64_t)seconds * 1000000LL + warmup_us;
  s_run = true;
  return true;
}

static void handle_command(const char *line) {
  if (strcmp(line, "PING") == 0) { usb_puts("PONG\n"); return; }
  if (strcmp(line, "INFO") == 0) {
    char msg[192];
    snprintf(msg, sizeof(msg),
             "INFO chans=%d bits=%d slot=%d rate=%d frame=%d warmup=%d\n",
             CAP_CHANNELS, CAP_BITS, CAP_SLOT_BITS, CAP_DECLARED_RATE,
             CAP_FRAME_BYTES, CAP_WARMUP_FRAMES);
    usb_puts(msg);
    return;
  }
  if (strcmp(line, "STOP") == 0) { stop_capture(); return; }
  if (strncmp(line, "START", 5) == 0) {
    uint32_t sec = 10;
    const char *sp = strchr(line, ' ');
    if (sp) sec = (uint32_t)strtoul(sp + 1, NULL, 10);
    if (sec == 0 || sec > 3600) { usb_puts("ERR bad seconds\n"); return; }
    if (!start_capture(sec)) { usb_puts("ERR busy\n"); return; }
    usb_puts("GO\n");
    return;
  }
  usb_puts("ERR unknown command\n");
}

static void console_task(void *arg) {
  (void)arg;
  char line[64];
  size_t at = 0;
  CAP_WDT_INIT();
  for (;;) {
    CAP_WDT_RESET();
    uint8_t c;
    if (usb_serial_jtag_read_bytes(&c, 1, 0) == 1) {
      if (c == '\n' || c == '\r') {
        line[at] = '\0';
        if (at > 0) handle_command(line);
        at = 0;
      } else if (at + 1 < sizeof(line)) {
        line[at++] = (char)c;
      }
      continue;
    }
    if (s_run && esp_timer_get_time() >= s_deadline_us) stop_capture();
    cap_delay_ms(2);
  }
}

// ── инициализация ───────────────────────────────────────────────────────
static esp_err_t init_zone(i2s_chan_handle_t *out, int port,
                           gpio_num_t bclk, gpio_num_t lrck, gpio_num_t data) {
  i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(port, I2S_ROLE_SLAVE);
  ESP_RETURN_ON_ERROR(i2s_new_channel(&chan_cfg, NULL, out), TAG, "new_channel %d", port);

  // Philips с битовым сдвигом и 16-битными слотами — ровно то, что шлёт
  // Master (legacy-драйвер, MSB-first, слот равен ширине отсчёта).
  // Слот шире данных или уже — данные придут сдвинутыми, и это выглядит как
  // «шум», а не как ошибка формата.
  i2s_std_config_t std_cfg = {
    .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(CAP_DECLARED_RATE),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(CAP_BITS, I2S_SLOT_MODE_STEREO),
    .gpio_cfg = {
      .mclk = I2S_GPIO_UNUSED,
      .bclk = bclk,
      .ws = lrck,
      .dout = I2S_GPIO_UNUSED,
      .din = data,
      .invert_flags = { .mclk_inv = 0, .bclk_inv = 0, .ws_inv = 0 },
    },
  };
  ESP_RETURN_ON_ERROR(i2s_channel_init_std_mode(*out, &std_cfg), TAG, "init_std %d", port);
  return ESP_OK;
}

void app_main(void) {
  ESP_LOGI(TAG, "старт: ставлю драйвер нативного USB");
  usb_serial_jtag_driver_config_t usb_cfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
  usb_cfg.tx_buffer_size = 4096;
  usb_cfg.rx_buffer_size = 1024;
  ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&usb_cfg));

  ESP_LOGI(TAG, "выделяю кольца зон");
  for (int z = 0; z < CAP_ZONES; z++) {
    s_ring[z] = heap_caps_malloc(CAP_ZRING_FRAMES * 2 * sizeof(int16_t), MALLOC_CAP_DMA);
    if (!s_ring[z]) {
      ESP_LOGE(TAG, "не хватило памяти на кольцо зоны %d", z);
      abort();
    }
  }

  ESP_LOGI(TAG, "создаю каналы I2S");
  ESP_ERROR_CHECK(init_zone(&s_z1, I2S_NUM_0, CAP_Z1_BCK, CAP_Z1_LRCK, CAP_Z1_DATA));
  ESP_ERROR_CHECK(init_zone(&s_z2, I2S_NUM_1, CAP_Z2_BCK, CAP_Z2_LRCK, CAP_Z2_DATA));

  ESP_LOGI(TAG, "создаю задачи");
  // Читатели зон приоритетнее упаковщика: пока USB занят, кольца наполняются
  // сами по себе, и задерживать их незачем. Опора — вторая зона не должна
  // ждать первую.
  xTaskCreatePinnedToCore(zone_task, "zone0", 3072, (void *)(uintptr_t)0, 8, NULL, 1);
  xTaskCreatePinnedToCore(zone_task, "zone1", 3072, (void *)(uintptr_t)1, 8, NULL, 1);
  xTaskCreatePinnedToCore(packer_task, "packer", 4096, NULL, 6, NULL, 1);
  xTaskCreatePinnedToCore(console_task, "console", 4096, NULL, 4, NULL, 0);

  // Дренаж: команды принимаются и до START, поэтому каналы должны быть
  // включены уже на старте — иначе первый START ждал бы лишние 20 мс.
  ESP_LOGI(TAG, "включаю каналы");
  ESP_ERROR_CHECK(i2s_channel_enable(s_z1));
  ESP_ERROR_CHECK(i2s_channel_enable(s_z2));

  ESP_LOGI(TAG, "сниффер готов, зона Z1 = I2S0 (BCK %d, LRCK %d, DATA %d), "
                "зона Z2 = I2S1 (BCK %d, LRCK %d, DATA %d)",
           CAP_Z1_BCK, CAP_Z1_LRCK, CAP_Z1_DATA,
           CAP_Z2_BCK, CAP_Z2_LRCK, CAP_Z2_DATA);
  ESP_LOGI(TAG, "команды на нативном USB — PING, INFO, START <секунды>, STOP");
}