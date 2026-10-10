/**
 * Golden test G2: кроссовер — каскады LP/HP прошивки против теоретического
 * отклика (firmware/ESP32_BiAmp/ESP32_BiAmp.ino, секции 1..4).
 *
 * Host-тест: компилируется и запускается обычным gcc/clang (без Arduino, без ESP-IDF).
 *
 * Прошивка строит кроссовер из биквадов Q=0.7071 на fc:
 *   xo_type==1 (12 дБ/окт): 1 LP + 1 HP
 *   xo_type==2 (24 дБ/окт): 2 LP + 2 HP  (Linkwitz-Riley, flat-сумма на fc)
 *
 * Проверяем:
 *   12 дБ/окт: |LP(fc)| = |HP(fc)| = -3 дБ
 *   24 дБ/окт: |LP(fc)| = |HP(fc)| = -6 дБ и |LP+HP|(fc) = 0 дБ (flat)
 *
 * Сборка и запуск:
 *   gcc -O2 -Wall -o golden_crossover.exe golden_crossover.c -lm && golden_crossover.exe
 */

#include <stdio.h>
#include <stdint.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SAMPLE_RATE 44100.0f
#define MAX_STAGES 4

/* Зеркало calcSectionTo для LP/HP (как в кроссовере, Q=0.7071) */
static void calcLPHP(float cb[3], float ca[2], int is_hp, float f) {
  float q = 0.7071f;
  float w0 = 2 * (float)M_PI * f / SAMPLE_RATE, cw = cosf(w0), sw = sinf(w0), alpha = sw / (2 * q);
  float b0, b1, b2, a0, a1, a2;
  if (!is_hp) {
    b0 = (1 - cw) * 0.5f; b1 = 1 - cw; b2 = b0;
  } else {
    b0 = (1 + cw) * 0.5f; b1 = -(1 + cw); b2 = b0;
  }
  a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
  cb[0] = b0 / a0; cb[1] = b1 / a0; cb[2] = b2 / a0;
  ca[0] = a1 / a0; ca[1] = a2 / a0;
}

typedef struct {
  int n;
  float cb[MAX_STAGES][3];
  float ca[MAX_STAGES][2];
  float st[MAX_STAGES][2];
} Cascade;

static void cascade_calc(Cascade *c, int n, int is_hp, float fc) {
  c->n = n;
  for (int s = 0; s < n; s++) {
    calcLPHP(c->cb[s], c->ca[s], is_hp, fc);
    c->st[s][0] = 0; c->st[s][1] = 0;
  }
}

static float cascade_run(Cascade *c, float x) {
  for (int s = 0; s < c->n; s++) {
    float y = c->cb[s][0] * x + c->st[s][0];
    c->st[s][0] = c->cb[s][1] * x - c->ca[s][0] * y + c->st[s][1];
    c->st[s][1] = c->cb[s][2] * x - c->ca[s][1] * y;
    x = y;
  }
  return x;
}

static int fails = 0;

static void measure(int num_stages, float fc, float *lp_db, float *hp_db, float *sum_db) {
  Cascade lp, hp;
  cascade_calc(&lp, num_stages, 0, fc);
  cascade_calc(&hp, num_stages, 1, fc);
  float phase = 0, step = 2 * (float)M_PI * fc / SAMPLE_RATE;
  const int N = 16384, skip = 4096;
  float lp_p = 0, hp_p = 0, sum_p = 0;
  for (int i = 0; i < N; i++) {
    float x = sinf(phase); phase += step;
    if (phase >= 2 * (float)M_PI) phase -= 2 * (float)M_PI;
    float lo = cascade_run(&lp, x);
    float ho = cascade_run(&hp, x);
    if (i >= skip) {
      if (fabsf(lo) > lp_p) lp_p = fabsf(lo);
      if (fabsf(ho) > hp_p) hp_p = fabsf(ho);
      if (fabsf(lo + ho) > sum_p) sum_p = fabsf(lo + ho);
    }
  }
  *lp_db = 20 * log10f(lp_p);
  *hp_db = 20 * log10f(hp_p);
  *sum_db = 20 * log10f(sum_p);
}

static void report(const char *name, float got, float expect, float tol) {
  int ok = fabsf(got - expect) < tol;
  printf("%s  %-30s got=%+7.3f dB expect=%+7.3f dB\n", ok ? "PASS" : "FAIL", name, got, expect);
  if (!ok) fails++;
}

int main(void) {
  const float fc = 400.0f;  // FC_DEFAULT

  float lp, hp, sum;

  // 12 дБ/окт (1 каскад, Butterworth)
  measure(1, fc, &lp, &hp, &sum);
  report("12dB/oct LP @fc", lp, -3.01f, 0.2f);
  report("12dB/oct HP @fc", hp, -3.01f, 0.2f);

  // 24 дБ/окт (2 каскада, Linkwitz-Riley)
  measure(2, fc, &lp, &hp, &sum);
  report("24dB/oct LP @fc", lp, -6.02f, 0.2f);
  report("24dB/oct HP @fc", hp, -6.02f, 0.2f);
  report("24dB/oct LP+HP @fc (flat)", sum, 0.0f, 0.3f);

  printf("\n%s (%d fails)\n", fails ? "FAILED" : "ALL PASSED", fails);
  return fails ? 1 : 0;
}
