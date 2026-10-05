/**
 * Golden test G1: биквад DF-IIT — прошивка calcSectionTo + runSec
 * (firmware/ESP32_BiAmp/ESP32_BiAmp.ino) против теоретической АЧХ H(e^jw).
 *
 * Host-тест: компилируется и запускается обычным gcc/clang (без Arduino, без ESP-IDF).
 *
 * Проверяет, что биквад Direct-Form-II-Transposed, управляемый коэффициентами
 * calcSectionTo (Audio EQ Cookbook), воспроизводит модуль передаточной функции
 * для каждого типа фильтра (LP/HP/peaking/low-shelf/high-shelf/bypass).
 *
 * Два независимых критерия:
 *   impl_err — выход DF-IIT против теоретической АЧХ (верно ли реализован фильтр);
 *   coef_err — теоретическая АЧХ против известного значения (верно ли посчитаны
 *              коэффициенты в calcSectionTo).
 *
 * Сборка и запуск:
 *   gcc -O2 -Wall -o golden_biquad.exe golden_biquad.c -lm && golden_biquad.exe
 */

#include <stdio.h>
#include <stdint.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SAMPLE_RATE 44100.0f

/* ---- Зеркало прошивки: calcSectionTo (ESP32_BiAmp.ino) ---- */
static void calcSectionTo(float cb[3], float ca[2], uint8_t type, float f, float q, float db) {
  if (type == 5) {
    cb[0] = 1; cb[1] = cb[2] = ca[0] = ca[1] = 0;
    return;
  }
  float w0 = 2 * (float)M_PI * f / SAMPLE_RATE, cw = cosf(w0), sw = sinf(w0), alpha = sw / (2 * q);
  float b0, b1, b2, a0, a1, a2;
  if (type == 0) {
    b0 = (1 - cw) * 0.5f; b1 = 1 - cw; b2 = b0;
    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
  } else if (type == 1) {
    b0 = (1 + cw) * 0.5f; b1 = -(1 + cw); b2 = b0;
    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
  } else if (type == 2) {
    float A = powf(10, db / 40.0f);
    b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
    a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
  } else {
    float A = powf(10, db / 40.0f), sqA = alpha * sqrtf(A * (A + 1));
    if (type == 3) {
      b0 = A * ((A + 1) - (A - 1) * cw + sqA);
      b1 = 2 * A * ((A - 1) - (A + 1) * cw);
      b2 = A * ((A + 1) - (A - 1) * cw - sqA);
      a0 = (A + 1) + (A - 1) * cw + sqA;
      a1 = -2 * ((A - 1) + (A + 1) * cw);
      a2 = (A + 1) + (A - 1) * cw - sqA;
    } else {
      b0 = A * ((A + 1) + (A - 1) * cw + sqA);
      b1 = -2 * A * ((A - 1) + (A + 1) * cw);
      b2 = A * ((A + 1) + (A - 1) * cw - sqA);
      a0 = (A + 1) - (A - 1) * cw + sqA;
      a1 = 2 * ((A - 1) - (A + 1) * cw);
      a2 = (A + 1) - (A - 1) * cw - sqA;
    }
  }
  cb[0] = b0 / a0; cb[1] = b1 / a0; cb[2] = b2 / a0;
  ca[0] = a1 / a0; ca[1] = a2 / a0;
}

/* ---- Зеркало прошивки: runSec (DF-IIT) ---- */
static float st1, st2;
static float runSec(float x, const float cb[3], const float ca[2]) {
  float y = cb[0] * x + st1;
  st1 = cb[1] * x - ca[0] * y + st2;
  st2 = cb[2] * x - ca[1] * y;
  return y;
}

/* ---- Эталон: |H(e^jw)| из передаточной функции (независимо от DF-IIT) ---- */
static float ref_magnitude(const float cb[3], const float ca[2], float freq) {
  float w = 2 * (float)M_PI * freq / SAMPLE_RATE;
  float c1 = cosf(w), s1 = sinf(w);
  float c2 = cosf(2 * w), s2 = sinf(2 * w);
  float nr = cb[0] + cb[1] * c1 + cb[2] * c2;
  float ni = -(cb[1] * s1 + cb[2] * s2);
  float dr = 1 + ca[0] * c1 + ca[1] * c2;
  float di = -(ca[0] * s1 + ca[1] * s2);
  return sqrtf(nr * nr + ni * ni) / sqrtf(dr * dr + di * di);
}

/* Прогнать стационарный синус через фильтр, измерить амплитуду выхода. */
static float measured_gain(const float cb[3], const float ca[2], float freq) {
  st1 = 0; st2 = 0;
  float phase = 0, step = 2 * (float)M_PI * freq / SAMPLE_RATE;
  const int N = 16384, skip = 4096;
  float peak = 0;
  for (int i = 0; i < N; i++) {
    float y = runSec(sinf(phase), cb, ca);
    phase += step;
    if (phase >= 2 * (float)M_PI) phase -= 2 * (float)M_PI;
    if (i >= skip) { float a = fabsf(y); if (a > peak) peak = a; }
  }
  return peak;
}

static int fails = 0;

static void check(const char *name, uint8_t type, float f0, float q, float db,
                  float meas_freq, int has_expect, float expect_db, float tol_db) {
  float cb[3], ca[2];
  calcSectionTo(cb, ca, type, f0, q, db);
  float g_meas = measured_gain(cb, ca, meas_freq);
  float g_ref  = ref_magnitude(cb, ca, meas_freq);
  float err_impl = 20 * log10f(g_meas / g_ref);
  int ok = fabsf(err_impl) < 0.05f;
  if (has_expect) {
    float err_coef = 20 * log10f(g_ref) - expect_db;
    if (fabsf(err_coef) > tol_db) ok = 0;
  }
  printf("%s  %-22s impl_err=%+.4f dB%s\n",
         ok ? "PASS" : "FAIL", name, err_impl, has_expect ? "  coef_err=" : "");
  if (has_expect) {
    float err_coef = 20 * log10f(g_ref) - expect_db;
    printf("        (expect %+.2f dB, coef_err=%+.4f dB)\n", expect_db, err_coef);
  }
  if (!ok) fails++;
}

int main(void) {
  check("LP @fc",         0, 1000, 0.7071f, 0, 1000,  1, -3.01f, 0.2f);
  check("HP @fc",         1, 1000, 0.7071f, 0, 1000,  1, -3.01f, 0.2f);
  check("Peaking @f0",    2, 1000, 1.0f,    6, 1000,  1, +6.0f,  0.2f);
  check("LowShelf low",   3, 1000, 0.7071f, 6, 100,   1, +6.0f,  0.5f);
  check("HighShelf high", 4, 1000, 0.7071f, 6, 10000, 1, +6.0f,  0.5f);
  check("Bypass",         5, 1000, 0.7071f, 0, 1000,  1, +0.0f,  0.05f);

  printf("\n%s (%d fails)\n", fails ? "FAILED" : "ALL PASSED", fails);
  return fails ? 1 : 0;
}
