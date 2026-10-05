/**
 * Golden test G3: линия задержки — прошивка applyDelay
 * (firmware/ESP32_BiAmp/ESP32_BiAmp.ino) против эталонного импульсного отклика.
 *
 * Host-тест: компилируется и запускается обычным gcc/clang (без Arduino, без ESP-IDF).
 *
 * Проверяет, что кольцевой буфер задержки даёт ровно N отсчётов задержки и не
 * искажает данные: импульс появляется на выходе точно через N отсчётов, до и
 * после него — ноль. Для delay=0 — прозрачный проход (выход = вход).
 *
 * Сборка и запуск:
 *   gcc -O2 -Wall -o golden_delay.exe golden_delay.c -lm && golden_delay.exe
 */

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

#define DELAY_BUF_SIZE 256
#define DELAY_BUF_MASK (DELAY_BUF_SIZE - 1)

static float delay_buf[DELAY_BUF_SIZE];
static uint16_t delay_idx = 0;

/* Зеркало applyDelay (ESP32_BiAmp.ino) */
static float applyDelay(float x, uint16_t delay_samples) {
  if (delay_samples == 0) {
    delay_buf[delay_idx] = x;
    delay_idx = (delay_idx + 1) & DELAY_BUF_MASK;
    return x;
  }
  float y = delay_buf[(delay_idx + DELAY_BUF_SIZE - delay_samples) & DELAY_BUF_MASK];
  delay_buf[delay_idx] = x;
  delay_idx = (delay_idx + 1) & DELAY_BUF_MASK;
  return y;
}

static int fails = 0;

static void check_delay(uint16_t d) {
  memset(delay_buf, 0, sizeof(delay_buf));
  delay_idx = 0;
  int found = -1;
  int corrupt = 0;
  for (int n = 0; n < DELAY_BUF_SIZE; n++) {
    float x = (n == 0) ? 1.0f : 0.0f;
    float y = applyDelay(x, d);
    if (d == 0) {
      if (n == 0 && fabsf(y - 1.0f) > 1e-6f) corrupt = 1;
      if (n != 0 && fabsf(y) > 1e-6f) corrupt = 1;
    } else {
      if (found < 0 && fabsf(y - 1.0f) < 1e-6f) found = n;
      if (n < d && fabsf(y) > 1e-6f) corrupt = 1;
      if (n > d && fabsf(y) > 1e-6f) corrupt = 1;
    }
  }
  int ok = (d == 0) ? !corrupt : (found == (int)d && !corrupt);
  printf("%s  delay=%3u  impulse_at=%d  corrupt=%d\n", ok ? "PASS" : "FAIL", d, found, corrupt);
  if (!ok) fails++;
}

int main(void) {
  check_delay(0);
  check_delay(1);
  check_delay(64);   // типичная DSP-задержка
  check_delay(220);  // MAX_DELAY_SAMPLES
  printf("\n%s (%d fails)\n", fails ? "FAILED" : "ALL PASSED", fails);
  return fails ? 1 : 0;
}
