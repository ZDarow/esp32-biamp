/**
 * @file ESP32_BiAmp.ino
 * @brief Би-амп ESP32: приём A2DP-аудио и вывод на два I2S-порта,
 *        управление по Bluetooth SPP. Версия 34.
 *
 * Описание архитектуры, всех команд и диагностики — в firmware/DOCUMENTATION.md.
 *
 * Ключевые решения версий:
 *        v23: DSP вынесен из A2DP-колбэка в отдельную FreeRTOS-задачу;
 *             SPSC-кольцо между колбэком и задачей вывода (A1).
 *             i2s_write ограничен по таймауму, был portMAX_DELAY (A2).
 *             Коэффициенты и цели — снимок под мьютексом на границе блока,
 *             DSP работает без блокировок (A3).
 *             Запись в кольцевой журнал атомарна, мьютекс ev_mux (A8).
 *             Частота источника берётся из переговоров A2DP, а не из
 *             эвристики по числу кадров (A9).
 *             Смена задержки обнуляет линию задержки вместо выброса
 *             старого аудио (B1).
 *        v25: 4 буфера I2S вместо 8, кольцо 2048 кадра — устранены потери.
 *        v29: ringPush/ringPop копируют двумя частями при переносе через
 *             конец массива. Один memcpy выходил за границу ring_buf
 *             примерно на 3580 байт — источник треска в динамиках при
 *             нулевых RingDrops и Underrun. Самотест проверяет содержимое
 *             кадров, а не только объём.
 *        v30: общий кроссовер выключается целиком командой xo:0 — секции
 *             1..4 уходят в обход, сабсоник и полосные фильтры остаются.
 *             Значения fc и xotype при этом сохраняются. Блоб NVS стал
 *             версии 22: xoon дописан последним, поэтому блоб v21
 *             принимается целиком и мигрирует без потери настроек.
 *        v31: на старте потока обнуляются состояния biquad'ов, линии задержки
 *             и рампы громкости (событие t=7). После паузы источника
 *             фильтры хранили отсчёты двухминутной давности, и первый блок
 *             выходил со ступенькой — щелчком. Счётчик Starve теперь меряет
 *             разрывы только внутри играющего потока: пауза источника
 *             (заблокированный экран останавливает A2DP, но is_playing
 *             остаётся true) раньше попадала в него целиком и давала
 *             161 с «недокорма» при нулевых Underrun и RingDrops.
 *        v32: щелчок на остановке потока устранён. Раньше телефон замолкал,
 *             задача вывода простаивала с пустым кольцом, DMA доигрывал
 *             последний блок и замирал на ненулевом отсчёте — усилитель
 *             получал ступеньку. Теперь на переходе «играем → не играем»
 *             задача вывода прогоняет через DSP тишину, уводя gain в ноль
 *             рампой FADE_K, и дописывает в I2S нулевой блок (событие t=8).
 *             Скорость гашения отдельная от рампы громкости: RAMP_K даёт
 *             постоянную времени 22 мс, на ноль уходит больше 200 мс.
 *        v33: счётчик Starve приведён к реальным потерям. Порог 40 мс был
 *             ниже межпакетного интервала: телефон шлёт аудио пачками, между
 *             пакетами штатно 41-53 мс, и счётчик на живом устройстве давал
 *             n=184 max=3019 мс при нулевых Underrun и RingDrops. Порог
 *             поднят до 80 мс — по запасу кольца (93 мс). Отметка времени
 *             последнего пакета вынесена в starve_last_ms и сбрасывается в
 *             audio_state_cb: на паузе колбэк A2DP не приходит вовсе, и без
 *             сброса первый пакет нового потока посчитал бы всю паузу
 *             провалом (наблюдалось t=1 v=3075).
 *        v34: щелчок на границах потока измерен прибором, а не на слух.
 *             Команда click меряет размах выхода в первых 256 кадрах после
 *             старта и в последнем блоке после гашения. Обычные счётчики
 *             щелчок не видят: данные не теряются, неверно только начало
 *             отсчёта. Контрольный прогон с отключённым сбросом (макрос
 *             CLICK_PROBE_SELFTEST) дал Start max 1985 при уровне сигнала
 *             168 — 1181% от сигнала, выход прыгал в 12 раз выше нужного.
 *             С фиксом на том же устройстве: Start 0, Fade end 0.
 *
 * @author Kilo
 * @license GNU General Public License v3.0 or later
 * @copyright (C) 2026 ZDarow
 */

#pragma GCC optimize("Os")

#include "AudioTools.h"
#include "BluetoothA2DPSink.h"
#include "BluetoothSerial.h"
#include <esp_idf_version.h>
#include <esp_system.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Preferences.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <atomic>

// Выводы I²S разведены по двум портам: каждому усилилю свой порт со своими
// тремя выводами. Общие пины у портов быть не могут — иначе один порт
// перезапишет выводы другого при инициализации и оба зазвучат неверно.
// Z1 — левый усилитель: BCK 4, LCK 15, DIN 2.
// Z2 — правый усилитель: BCK 25, LCK 27, DIN 26. Порядок BCK/DIN здесь
// обратный относительно Z1 и взят с реальной разводки платы.
#define Z1_BCK 4
#define Z1_LCK 15
#define Z1_DIN 2
#define Z2_BCK 25
#define Z2_LCK 27
#define Z2_DIN 26
#define OLED_SDA 21
#define OLED_SCL 22
#define OLED_ADDR 0x3C

#define SAMPLE_RATE     44100
#define BITS_PER_SAMPLE 16
#define RAMP_K          0.001f
// Рампа гашения при остановке потока. Обычная RAMP_K даёт постоянную времени
// около 22 мс, на выход в ноль уходит больше 200 мс — слышно как «висит звук».
// Для гашения нужен отклик около 4.5 мс: до −60 дБ выход доходит за 46 мс,
// но в уши попадает только последняя часть затухания.
#define FADE_K          0.005f
#define FADE_EPS        5.0e-4f   // −66 дБ от полной шкалы
#define RATIO48         (48000.0f / 44100.0f)
#define NVS_NS          "biampv12"
#define NVS_SAVE_DELAY  2000
#define DEVICE_NAME     "ESP32 BiAmp Speaker"
#define DELAY_BUF_SIZE  256
#define DELAY_BUF_MASK  (DELAY_BUF_SIZE - 1)
#define MAX_DELAY_SAMPLES 220   // 5 мс @44.1 кГц — с запасом для развёртки динамиков

// --- параметры аудиопрохода -------------------------------------------
#define BLOCK_FRAMES    256   // кадров на блок обработки
#define RING_FRAMES     4096  // 93 мс запаса: телефон рвёт поток интервалами до 72 мс,
                               // при 46 мс кольцо переполнялось на всплесках, а в паузах
                               // DMA останавливался — отсюда треск
#define RING_MASK       (RING_FRAMES - 1)
#define BT_ESP_I2S_BUFFERS 4  // 4 x 512 = 11.6 мс DMA; 8 буферов стоят ~22 КБ кучи,
                               // а провал источника гасит подпитка тишиной, не запас DMA
#define I2S_WRITE_TIMEOUT_MS 20
// Порог счётчика Starve: зазор между пакетами A2DP больше этого значения
// считается провалом потока. Ниже 80 мс зазор штатный — телефон присылает
// аудио пачками, а кольцо на 93 мс ещё не опустошается.
#define STARVE_GAP_MS     80
#define AUDIO_TASK_STACK 2048
#define AUDIO_TASK_PRIO  6
#define AUDIO_TASK_CORE  1

I2SStream i2s_z1, i2s_z2;
BluetoothA2DPSink a2dp_sink;
BluetoothSerial SerialBT;
Adafruit_SSD1306 disp(128, 64, &Wire, -1);
Preferences prefs;

portMUX_TYPE ctrl_mux = portMUX_INITIALIZER_UNLOCKED;

// ===================== ПАРАМЕТРЫ (только задача loop) ===================
float vol_z[2] = {0.10f, 0.10f};
bool  muted_z[2] = {false, false};

float fc_hz = 400.0f;
float sub_hz = 45.0f;
bool  sub_on = true;
float tlf_db = 0.0f, thf_db = -1.0f, eq_db[3] = {0, 0, 0};
float bal = 0.0f;
uint8_t xo_type = 1;
// Выключатель кроссовера: при false секции 1..4 (ФНЧ/ФВЧ на fc) считаются
// как bypass. Сабсоник (секция 0) и полосные фильтры при этом остаются
// включены — выключается именно разделение на НЧ/ВЧ-ветку.
bool  xo_on = true;
// Перестановка выходов Л/П: при true каналы 0,1 уходят в правый разъём,
// а 2,3 — в левый. Нужна, если усилитель физически подключён наоборот:
// менять провода не всегда возможно, а слышать левый канал в правом
// динамике неправильно. Инверсия фазы для этого не годится — она
// переворачивает сигнал, а меняет стороны света.
bool  lr_swap = false;
float ch_hp[4] = {0, 0, 0, 0};
float ch_lp[4] = {0, 0, 0, 0};
uint16_t ch_delay[4] = {0, 0, 0, 0};

bool paramsDirty = false;
uint32_t paramsChangedAt = 0;
std::atomic<bool> ui_dirty{false};
bool oled_ok = false;

std::atomic<bool> bt_connected{false};
std::atomic<bool> is_playing{false};
std::atomic<bool> src_48k{false};
std::atomic<uint32_t> g_in_frames{0};

std::atomic<uint8_t> test_mode{0};
std::atomic<float>   test_freq{440.0f};
std::atomic<float>   test_vol{0.06f};

std::atomic<uint32_t> starve_cnt{0}, starve_max_ms{0};
// Отметка времени последнего пакета A2DP для счётчика Starve. Сбрасывается
// на каждой границе потока, иначе первый пакет после паузы посчитал бы
// провалом всю паузу: колбэк во время паузы не приходит и сам себя сбросить
// не может.
std::atomic<uint32_t> starve_last_ms{0};
// Ставится колбэком A2DP на старте потока, снимается задачей вывода.
std::atomic<bool> dsp_reset_pending{false};
// Ставится колбэком A2DP на остановке потока: выход нужно плавно свести
// в ноль, иначе DMA замолкает на ненулевом отсчёте и усилитель щёлкает.
std::atomic<bool> dsp_fade_pending{false};
std::atomic<uint32_t> dsp_max_us{0}, wr_max_us{0};
std::atomic<uint32_t> loop_max_ms{0}, nvs_max_ms{0};
std::atomic<uint32_t> und_z[2] = {{0}, {0}};
std::atomic<uint32_t> clip_cnt[4] = {{0}, {0}, {0}, {0}};
std::atomic<uint32_t> ring_drops{0};
// Счётчики задачи вывода: сколько блоков и кадров реально обработано.
std::atomic<uint32_t> audio_blocks{0}, audio_frames{0}, audio_idle{0};

// ===================== ПАРАМЕТРЫ АУДИО (снимок) =========================
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
  // Перестановка выходов Л/П едет в снимке, а не живёт отдельной глобалкой:
  // задача вывода читает ap только через refreshParams(), поэтому и swap
  // обязан приходить тем же снимком. Отдельная переменная, которую читает
  // audioTask, а пишет loop, — это гонка: компилятор вправе переставить
  // чтение относительно записи, и кадр может уйти с перепутанными каналами.
  bool  swap;
};

static AudioParams shadow_ap;            // владеет loop, пишется под ctrl_mux
static std::atomic<bool> ap_dirty{false};

static AudioParams ap;                   // личное состояние задачи вывода
static float st1[2][8], st2[2][8];      // состояния глобальных секций
static float chst[4][2][2];             // состояния канальных секций
static float delay_buf[4][DELAY_BUF_SIZE];
static uint16_t delay_idx[4] = {0, 0, 0, 0};
static float gain[4] = {0, 0, 0, 0};
// Живёт только в задаче вывода: 1 во время гашения потока. computeTargets
// обнуляет цели, и рампы доводят gain до нуля мягко, без ступеньки на выходе.
static bool dsp_fade = false;
static int16_t bz1[BLOCK_FRAMES * 2], bz2[BLOCK_FRAMES * 2];
static int16_t inbuf[BLOCK_FRAMES * 2];

// ===================== SPSC-КОЛЬЦО ======================================
static int16_t ring_buf[RING_FRAMES * 2];
// Индексы кольца защищены ring_mux, атомарность им не нужна.
static uint32_t ring_w = 0, ring_r = 0;
static std::atomic<bool>    ring_flush{false};

struct EvLog { uint32_t ms; uint8_t type; uint16_t val; };
static EvLog evbuf[16];
static uint8_t ev_idx = 0;
static portMUX_TYPE ev_mux = portMUX_INITIALIZER_UNLOCKED;

// Блок сохранённых параметров. Объявлен здесь, а не в разделе NVS:
// arduino-cli вставляет автопрототипы функций после этого блока глобальных
// объявлений, и функция с параметром PBlob иначе не соберётся — тип ещё
// не объявлен в момент генерации прототипа.
struct PBlob {
  uint32_t version;
  float v0, v1, fc, hp, tlf, thf, eq0, eq1, eq2, bal, tvol;
  float chh[4], chl[4];
  uint16_t dly[4];
  // xoon добавлен последним: так поля v21 остаются на прежних смещениях,
  // и старый блоб можно принять целиком, а не сбрасывать настройки.
  // invm переименован в swp на том же месте: инверсия фазы убрана,
  // вместо неё перестановка Л/П. Размер и смещения не изменились,
  // поэтому старый блоб читается как есть — просто его invm игнорируется.
  uint8_t m0, m1, sub, xot, swp, xoon;
} __attribute__((packed));

constexpr uint32_t PBLOB_VERSION = 23;

// Запись и чтение журнала под мьютексом: запись записи должна быть атомарной.
void logEvent(uint8_t type, uint16_t val) {
  portENTER_CRITICAL(&ev_mux);
  uint8_t idx = ev_idx & 15;
  ev_idx++;
  evbuf[idx].ms = millis();
  evbuf[idx].type = type;
  evbuf[idx].val = val;
  portEXIT_CRITICAL(&ev_mux);
}

void dumpEventLog() {
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

// ===================== РАСЧЁТ ФИЛЬТРОВ (задача loop) ====================
void calcSectionTo(float out_cb[][3], float out_ca[][2],
                   uint8_t sec, uint8_t type, float f, float q, float db) {
  if (type == 5) {
    out_cb[sec][0] = 1; out_cb[sec][1] = out_cb[sec][2] = out_ca[sec][0] = out_ca[sec][1] = 0;
    return;
  }
  float w0 = 2 * PI * f / SAMPLE_RATE, cw = cosf(w0), sw = sinf(w0), alpha = sw / (2 * q);
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
    float A = powf(10, db / 20.0f), sqA = 2 * sqrtf(A) * alpha;
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
  out_cb[sec][0] = b0 / a0; out_cb[sec][1] = b1 / a0; out_cb[sec][2] = b2 / a0;
  out_ca[sec][0] = a1 / a0; out_ca[sec][1] = a2 / a0;
}

void calcAllFilters(AudioParams &p) {
  memset(p.cb, 0, sizeof(p.cb));
  memset(p.ca, 0, sizeof(p.ca));
  // Секции 0..4: сабсоник и кроссовер. При xo_on == false кроссоверные
  // секции становятся bypass, а сабсоник остаётся — он не часть кроссовера.
  calcSectionTo(p.cb, p.ca, 0, sub_on ? 1 : 5, sub_hz, 0.7071f, 0);
  calcSectionTo(p.cb, p.ca, 1, xo_on ? 0 : 5, fc_hz, 0.7071f, 0);
  calcSectionTo(p.cb, p.ca, 2, (xo_on && xo_type == 2) ? 0 : 5, fc_hz, 0.7071f, 0);
  calcSectionTo(p.cb, p.ca, 3, xo_on ? 1 : 5, fc_hz, 0.7071f, 0);
  calcSectionTo(p.cb, p.ca, 4, (xo_on && xo_type == 2) ? 1 : 5, fc_hz, 0.7071f, 0);
  calcSectionTo(p.cb, p.ca, 5, fabsf(eq_db[0]) < 0.05f ? 5 : 3, 120, 0.7071f, eq_db[0]);
  calcSectionTo(p.cb, p.ca, 6, fabsf(eq_db[1]) < 0.05f ? 5 : 2, 1000, 1, eq_db[1]);
  calcSectionTo(p.cb, p.ca, 7, fabsf(eq_db[2]) < 0.05f ? 5 : 4, 6000, 0.7071f, eq_db[2]);
  p.tlf = powf(10, tlf_db / 20.0f);
  p.thf = powf(10, thf_db / 20.0f);
}

void calcChannelFilters(AudioParams &p) {
  memset(p.chb, 0, sizeof(p.chb));
  memset(p.cha, 0, sizeof(p.cha));
  for (uint8_t c = 0; c < 4; c++) {
    calcSectionTo(p.chb[c], p.cha[c], 0,
                  (ch_hp[c] >= 20.0f) ? 1 : 5, max(ch_hp[c], 20.0f), 0.7071f, 0.0f);
    calcSectionTo(p.chb[c], p.cha[c], 1,
                  (ch_lp[c] >= 20.0f) ? 0 : 5, max(ch_lp[c], 20.0f), 0.7071f, 0.0f);
  }
}

// Единственная точка публикации параметров. Пишет только задача loop,
// копирование в shadow_ap — под ctrl_mux.
void scheduleFilterUpdate() {
  AudioParams tmp;
  calcAllFilters(tmp);
  calcChannelFilters(tmp);
  tmp.bl = 1.0f - bal * 0.03f;
  tmp.br = 1.0f + bal * 0.03f;
  tmp.vol[0] = vol_z[0]; tmp.vol[1] = vol_z[1];
  tmp.mute[0] = muted_z[0]; tmp.mute[1] = muted_z[1];
  for (uint8_t c = 0; c < 4; c++) { tmp.dly[c] = ch_delay[c]; }
  tmp.swap = lr_swap;
  portENTER_CRITICAL(&ctrl_mux);
  memcpy(&shadow_ap, &tmp, sizeof(tmp));
  ap_dirty.store(true, std::memory_order_release);
  portEXIT_CRITICAL(&ctrl_mux);
}

// ===================== ЩЕЛЧОК НА ГРАНИЦАХ ПОТОКА ======================
// Щелчок — это ступенька выхода там, где сигнала быть не должно: в начале
// потока и в конце гашения. Проверять это на слух нельзя без акустики, и
// обычные счётчики его не видят: ни Underrun, ни RingDrops, ни BadSamples при
// щелчке не растут, потому что данные не теряются — неверно только начало
// отсчёта.
//
// Поэтому измеряем амплитуду выхода в двух окнах: первые CLICK_PROBE_FRAMES
// кадров после старта потока и последний блок после гашения. Обе величины
// должны быть близки к нулю. Если бы ступенька осталась, они сравнялись бы с
// уровнем сигнала: щелчок на полной громкости — это размах сигнала.
//
// Уровень сигнала (click_level) нужен как масштаб: он показывает, сколько
// было бы слышно при ступеньке. Отношение start/level и есть искомый ответ.
#define CLICK_PROBE_FRAMES 256
static std::atomic<int32_t> click_probe{0};      // осталось кадров в окне
static std::atomic<bool>    click_probe_armed{false};
static uint32_t click_start_max[2] = {0, 0};
static uint32_t click_fade_max[2] = {0, 0};
static uint32_t click_level = 0;                  // типичный размах сигнала
static uint32_t click_blk = 0;                   // счётчик блоков для выборки
static uint8_t  click_dst = 0;                    // 0 — в start, 1 — в fade

inline uint32_t abs16(int16_t v) { return (uint32_t)(v < 0 ? -(int32_t)v : v); }

// ===================== DSP (только задача вывода) ========================
inline float runSec(float x, uint8_t s, uint8_t sec) {
  float y = ap.cb[sec][0] * x + st1[s][sec];
  st1[s][sec] = ap.cb[sec][1] * x - ap.ca[sec][0] * y + st2[s][sec];
  st2[s][sec] = ap.cb[sec][2] * x - ap.ca[sec][1] * y;
  return y;
}

inline float runCh(float x, uint8_t c) {
  for (uint8_t k = 0; k < 2; k++) {
    float y = ap.chb[c][k][0] * x + chst[c][k][0];
    chst[c][k][0] = ap.chb[c][k][1] * x - ap.cha[c][k][0] * y + chst[c][k][1];
    chst[c][k][1] = ap.chb[c][k][2] * x - ap.cha[c][k][1] * y;
    x = y;
  }
  return x;
}

inline float applyDelay(float x, uint8_t ch, uint16_t delay_samples) {
  if (delay_samples == 0) {
    delay_buf[ch][delay_idx[ch]] = x;
    delay_idx[ch] = (delay_idx[ch] + 1) & DELAY_BUF_MASK;
    return x;
  }
  float y = delay_buf[ch][(delay_idx[ch] + DELAY_BUF_SIZE - delay_samples) & DELAY_BUF_MASK];
  delay_buf[ch][delay_idx[ch]] = x;
  delay_idx[ch] = (delay_idx[ch] + 1) & DELAY_BUF_MASK;
  return y;
}

inline float fast_tanh(float x) {
  float x2 = x * x, y = x * (27 + x2) / (27 + 9 * x2);
  return y > 1 ? 1 : (y < -1 ? -1 : y);
}

inline float softClip(float x, uint8_t ch) {
  float a = fabsf(x);
  if (a > 1) clip_cnt[ch].fetch_add(1, std::memory_order_relaxed);
  if (a > 0.8f) {
    float y = 0.8f + 0.2f * fast_tanh((a - 0.8f) / 0.2f);
    x = x < 0 ? -y : y;
  }
  return x;
}

// Единый снимок коэффициентов и целей на границе блока.
void refreshParams() {
  if (!ap_dirty.exchange(false, std::memory_order_acquire)) return;
  portENTER_CRITICAL(&ctrl_mux);
  memcpy(&ap, &shadow_ap, sizeof(ap));
  portEXIT_CRITICAL(&ctrl_mux);
  // Смена длительности задержки скачком сдвинула бы фазу и выбросила бы в выход
  // до 11 мс старого аудио из линии. Обнуляем линию: короткое затухание вместо щелчка.
  static uint16_t applied_dly[4] = {0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF};
  for (uint8_t c = 0; c < 4; c++) {
    if (applied_dly[c] != ap.dly[c]) {
      applied_dly[c] = ap.dly[c];
      memset(delay_buf[c], 0, sizeof(delay_buf[c]));
      delay_idx[c] = 0;
    }
  }
}

void computeTargets(bool test_on, float tgt[4]) {
  // Гашение: цели в ноль независимо от громкости и баланса, иначе рампы
  // держали бы выход на прежнем уровне и щелчок на остановке остался бы.
  if (dsp_fade) {
    for (uint8_t c = 0; c < 4; c++) tgt[c] = 0.0f;
    return;
  }
  float tv = test_vol.load(std::memory_order_relaxed);
  for (uint8_t c = 0; c < 4; c++) {
    uint8_t z = c >> 1;
    bool low = (c & 1) == 0;
    float v = test_on ? tv : ap.vol[z];
    if (ap.mute[z]) v = 0.0f;
    tgt[c] = v * (low ? ap.tlf : ap.thf) * (z == 0 ? ap.bl : ap.br);
  }
}

static uint8_t testMaskOf(uint8_t tm) {
  switch (tm) {
    case 1:  return 0xF;
    case 2:  return 0x3;
    case 3:  return 0xC;
    case 4:  return 0x5;
    case 5:  return 0xA;
    case 6:  return 0x1;
    case 7:  return 0x2;
    case 8:  return 0x4;
    case 9:  return 0x8;
    case 10: return 0xF;
    case 11: return 0xF;
    default: return 0xF;
  }
}

void processBlock(const int16_t *in, int n) {
  uint32_t t_dsp = micros();
  audio_blocks.fetch_add(1, std::memory_order_relaxed);
  audio_frames.fetch_add((uint32_t)n, std::memory_order_relaxed);
  uint8_t tm = test_mode.load(std::memory_order_relaxed);
  bool test_on = tm != 0;
  float tgt[4];
  computeTargets(test_on, tgt);
  uint8_t mask = test_on ? testMaskOf(tm) : 0xF;

  for (int f = 0; f < n; f++) {
    // Гашение идёт быстрее обычной рампы громкости, но всё ещё плавно:
    // выход не должен прыгнуть к нулю скачком.
    float rk = dsp_fade ? FADE_K : RAMP_K;
    for (uint8_t c = 0; c < 4; c++) gain[c] += (tgt[c] - gain[c]) * rk;
    float raw[4], out[4];
    for (uint8_t s = 0; s < 2; s++) {
      float x = in[f * 2 + s] * (1.0f / 32768.0f);
      x = runSec(runSec(runSec(x, s, 5), s, 6), s, 7);
      raw[s * 2]     = runSec(runSec(runSec(x, s, 0), s, 1), s, 2);
      raw[s * 2 + 1] = runSec(runSec(x, s, 3), s, 4);
    }
    for (uint8_t c = 0; c < 4; c++) {
      float v = runCh(raw[c], c);
      v = applyDelay(v, c, ap.dly[c]);
      if (!(mask & (1 << c))) v = 0.0f;
      out[c] = softClip(v * gain[c], c);
    }
    // Перестановка Л/П делается на готовых отсчётах, а не на входе:
    // так каналы меняются местами целиком, вместе со своей обработкой.
    // Флаг берётся из ap — личного снимка задачи вывода, а не из общей
    // переменной: иначе переключатель мог бы смениться посреди блока и
    // половина кадра ушла бы в левый разъём, половина — в правый.
    int16_t *o1; int16_t *o2;
    if (ap.swap) { o1 = bz2 + f * 2; o2 = bz1 + f * 2; }
    else         { o1 = bz1 + f * 2; o2 = bz2 + f * 2; }
    o1[0] = (int16_t)(out[ap.swap ? 2 : 0] * 32767);
    o1[1] = (int16_t)(out[ap.swap ? 3 : 1] * 32767);
    o2[0] = (int16_t)(out[ap.swap ? 0 : 2] * 32767);
    o2[1] = (int16_t)(out[ap.swap ? 1 : 3] * 32767);
    // Замер окна границы потока: ищем максимум размаха, а не разницу между
    // отсчётами. Размах не зависит от того, попал ли сам щелчок в этот кадр:
    // если ступенька есть, она сделает максимум большим в любом случае.
    if (click_probe_armed.load(std::memory_order_relaxed)) {
      uint32_t m1 = abs16(bz1[f * 2]), m2 = abs16(bz2[f * 2]);
      if (m1 > click_start_max[0] || m2 > click_start_max[1]) {
        if (m1 > click_start_max[0]) click_start_max[0] = m1;
        if (m2 > click_start_max[1]) click_start_max[1] = m2;
      }
      if (click_probe.fetch_sub(1, std::memory_order_relaxed) <= 1) {
        click_probe_armed.store(false, std::memory_order_relaxed);
        if (click_dst == 1) { click_fade_max[0] = click_start_max[0]; click_fade_max[1] = click_start_max[1]; }
      }
    }
  }

  uint32_t dsp_us = micros() - t_dsp;
  if (dsp_us > dsp_max_us.load(std::memory_order_relaxed)) dsp_max_us.store(dsp_us, std::memory_order_relaxed);

  size_t bytes = (size_t)n * 4;
  // Масштаб для оценки щелчка: размах обычного сигнала раз в 64 блока, вне
  // окон замера. Значения click_level и кликов сравниваются между собой.
  if (!click_probe_armed.load(std::memory_order_relaxed) && ((click_blk++ & 63u) == 0)) {
    for (int i = 0; i < n; i++) {
      uint32_t m = abs16(bz1[i * 2]);
      if (m > click_level) click_level = m;
    }
  }
  uint32_t t_wr = micros();
  bool w1 = (i2s_z1.write((uint8_t *)bz1, bytes) == bytes);
  bool w2 = (i2s_z2.write((uint8_t *)bz2, bytes) == bytes);
  uint32_t dw = micros() - t_wr;
  if (dw > wr_max_us.load(std::memory_order_relaxed)) wr_max_us.store(dw, std::memory_order_relaxed);
  if (!w1) { und_z[0].fetch_add(1, std::memory_order_relaxed); logEvent(2, 0); }
  if (!w2) { und_z[1].fetch_add(1, std::memory_order_relaxed); logEvent(2, 1); }
}

// ===================== ГЕНЕРАТОР ТЕСТА (задача вывода) =================
static float tone_phase = 0.0f, sweep_phase = 0.0f;
static uint8_t last_test_mode = 0xFF;

int genTestBlock(int16_t *out, int maxn) {
  uint8_t tm = test_mode.load(std::memory_order_relaxed);
  if (tm == 0) return 0;
  if (tm != last_test_mode) {
    last_test_mode = tm;
    tone_phase = 0.0f;
    sweep_phase = 0.0f;
  }
  float freq = test_freq.load(std::memory_order_relaxed);
  bool anti = (tm == 10);
  int n = maxn;
  for (int i = 0; i < maxn; i++) {
    if ((i & 63) == 0 && test_mode.load(std::memory_order_relaxed) == 0) { n = i; break; }
    if (tm == 11) {
      freq = 20.0f * powf(1000.0f, sweep_phase / 10.0f);
      sweep_phase += 1.0f / SAMPLE_RATE;
      if (sweep_phase > 10.0f) sweep_phase = 0.0f;
    }
    int16_t s = (int16_t)(12000 * sinf(tone_phase));
    tone_phase += 2 * PI * freq / SAMPLE_RATE;
    if (tone_phase >= 2 * PI) tone_phase -= 2 * PI;
    out[i * 2] = s;
    out[i * 2 + 1] = anti ? (int16_t)(-s) : s;
  }
  return n;
}

// ===================== КОЛЬЦО ==========================================
// Производитель: задача A2DP (ядро 0). Потребитель: задача вывода (ядро 1).
//
// ВАЖНО: кэши данных двух ядер Xtensa не когерентны. Одних atomic-индексов с
// acquire/release недостаточно — consumer может прочитать устаревшие строки
// кэша из ring_buf и получить верный ОБЪЁМ кадров, но часть отсчётов придёт
// из прошлого: это слышно как треск, и ни один счётчик не срабатывает.
// Поэтому и данные, и индексы живут под критической секцией — она и даёт
// аппаратную когерентность между ядрами.
static portMUX_TYPE ring_mux = portMUX_INITIALIZER_UNLOCKED;

// A2DP отдаёт блоки по ~896 кадров, поэтому пишем по частям: иначе блок
// целиком не поместился бы и кадры терялись бы постоянно.
static void ringPush(const int16_t *frm, int n) {
  if (n <= 0) return;
  uint32_t dropped = 0;
  portENTER_CRITICAL(&ring_mux);
  uint32_t w = ring_w;
  int space = (int)(RING_FRAMES - (w - ring_r));
  if (space <= 0) {
    dropped = (uint32_t)n;
  } else {
    int take = (n < space) ? n : space;
    // Копирование обязано разорваться на границе буфера: иначе блок, начатый
    // у конца массива, уйдёт за ring_buf и не попадёт в начало кольца.
    size_t idx   = (size_t)(w & RING_MASK) * 2;
    size_t first = (size_t)((uint32_t)take < (uint32_t)(RING_FRAMES - (w & RING_MASK))
                            ? (uint32_t)take : (uint32_t)(RING_FRAMES - (w & RING_MASK)));
    memcpy(&ring_buf[idx], frm, first * 4);
    if (first < (size_t)take) memcpy(&ring_buf[0], frm + first * 2, ((size_t)take - first) * 4);
    ring_w = w + (uint32_t)take;
    if (take < n) dropped = (uint32_t)(n - take);
  }
  portEXIT_CRITICAL(&ring_mux);
  // Счётчики и журнал — вне секции: свой мьютекс, вложенности не будет.
  if (dropped) {
    ring_drops.fetch_add(dropped, std::memory_order_relaxed);
    logEvent(5, (uint16_t)(dropped > 65000u ? 65000u : dropped));
  }
}

static int ringPop(int16_t *dst, int maxn) {
  int n;
  portENTER_CRITICAL(&ring_mux);
  uint32_t r = ring_r;
  uint32_t avail = ring_w - r;
  n = (int)((avail < (uint32_t)maxn) ? avail : (uint32_t)maxn);
  if (n > 0) {
    size_t idx   = (size_t)(r & RING_MASK) * 2;
    size_t first = (size_t)((uint32_t)n < (uint32_t)(RING_FRAMES - (r & RING_MASK))
                            ? (uint32_t)n : (uint32_t)(RING_FRAMES - (r & RING_MASK)));
    memcpy(dst, &ring_buf[idx], first * 4);
    if (first < (size_t)n) memcpy(dst + first * 2, &ring_buf[0], ((size_t)n - first) * 4);
    ring_r = r + (uint32_t)n;
  }
  portEXIT_CRITICAL(&ring_mux);
  return n;
}

static void ringFlush() {
  portENTER_CRITICAL(&ring_mux);
  ring_r = ring_w;
  portEXIT_CRITICAL(&ring_mux);
}

static uint32_t ringLevel() {
  portENTER_CRITICAL(&ring_mux);
  uint32_t lvl = ring_w - ring_r;
  portEXIT_CRITICAL(&ring_mux);
  return lvl;
}

// Проверка целостности данных кольца. Подпись блока зависит только от номера
// блока, который обе стороны считают САМИ (производитель — по счётчику цикла,
// потребитель — по своему счётчику кадров). Ничего общего между ядрами нет,
// иначе проверка заразилась бы ровно той болезнью, которую ловит.
#define SELFTEST_CHUNK 896
#define ST_BAD_LOG 8
static std::atomic<bool> st_active{false};
static std::atomic<uint32_t> st_errors{0};
static uint32_t st_seen = 0;   // счётчик кадров потребителя, живёт только в audioTask
static uint16_t st_bad_at[ST_BAD_LOG];
static uint8_t  st_bad_n = 0;
static uint32_t st_first_pos = 0;
static int16_t  st_first_exp = 0, st_first_l = 0, st_first_r = 0;

static inline int16_t selftestSig(uint32_t chunk) {
  return (int16_t)(0x1000 + chunk * 16);
}

// Сверка содержимого кольца на стороне потребителя.
static void verifyRingBlock(int n) {
  uint32_t bad = 0;
  for (int i = 0; i < n; i++) {
    uint32_t pos = st_seen + (uint32_t)i;
    int16_t want = selftestSig(pos / SELFTEST_CHUNK);
    if (inbuf[i * 2] != want || inbuf[i * 2 + 1] != (int16_t)-want) {
      bad++;
      if (st_bad_n == 0) {
        st_first_pos = pos; st_first_exp = want;
        st_first_l = inbuf[i * 2]; st_first_r = inbuf[i * 2 + 1];
      }
      if (st_bad_n < ST_BAD_LOG) st_bad_at[st_bad_n++] = (uint16_t)pos;
    }
  }
  if (bad) st_errors.fetch_add(bad, std::memory_order_relaxed);
  st_seen += (uint32_t)n;
}

// Сквозной самотест кольца: A2DP подключается позже, поэтому единственный
// производитель — мы. Проверяем и количество кадров, и их содержимое.
static bool ringSelfTest() {
  const int CHUNKS = 12;   // 10752 кадров — кольцо обходится больше двух раз,
                           // иначе устаревшая строка кэша не успевает проявиться
  static int16_t blk[SELFTEST_CHUNK * 2];
  uint32_t f0 = audio_frames.load();
  uint32_t d0 = ring_drops.load();
  st_errors.store(0);
  st_bad_n = 0;
  st_seen = 0;
  st_active.store(true);
  for (uint32_t k = 0; k < CHUNKS; k++) {
    int16_t sig = selftestSig(k);
    for (int i = 0; i < SELFTEST_CHUNK; i++) { blk[i * 2] = sig; blk[i * 2 + 1] = (int16_t)-sig; }
    ringPush(blk, SELFTEST_CHUNK);
    uint32_t t0 = millis();
    while (ringLevel() > 128 && millis() - t0 < 500) delay(2);
  }
  uint32_t total = CHUNKS * SELFTEST_CHUNK;
  uint32_t t0 = millis();
  while ((audio_frames.load() - f0) < total && millis() - t0 < 2000) delay(5);
  st_active.store(false);
  uint32_t got = audio_frames.load() - f0;
  uint32_t err = st_errors.load();
  bool ok = (got == total) && (ring_drops.load() == d0) && (err == 0);
  Serial.print(F("Ring selftest: "));
  Serial.print(ok ? F("PASS") : F("FAIL"));
  Serial.print(F(" frames=")); Serial.print(got);
  Serial.print(F("/")); Serial.print(total);
  Serial.print(F(" bad=")); Serial.print(err);
  Serial.print(F(" drops=")); Serial.print(ring_drops.load() - d0);
  if (st_bad_n) {
    Serial.print(F(" first@")); Serial.print(st_first_pos);
    Serial.print(F(" want=")); Serial.print(st_first_exp);
    Serial.print(F(" got=")); Serial.print(st_first_l);
    Serial.print('/'); Serial.print(st_first_r);
    Serial.print(F(" at="));
    for (uint8_t i = 0; i < st_bad_n; i++) { Serial.print(st_bad_at[i]); Serial.print(' '); }
  }
  Serial.println();
  return ok;
}

// ===================== ЗАДАЧА ВЫВОДА ===================================
static void setI2SWriteTimeoutMs(TickType_t ms) {
#if !USE_LEGACY_I2S
  static_cast<audio_tools::I2SDriverESP32V1 *>(i2s_z1.driver())->setWaitTimeWriteMs(ms);
  static_cast<audio_tools::I2SDriverESP32V1 *>(i2s_z2.driver())->setWaitTimeWriteMs(ms);
#else
  static_cast<audio_tools::I2SDriverESP32 *>(i2s_z1.driver())->setWaitTimeWriteMs(ms);
  static_cast<audio_tools::I2SDriverESP32 *>(i2s_z2.driver())->setWaitTimeWriteMs(ms);
#endif
}

// Сброс состояния DSP при старте потока. Вызывается только из audioTask,
// поэтому гонки с processBlock нет. Кольцо здесь не трогаем: его сбрасывает
// ring_flush, выставленный тем же колбэком.
void resetDspState() {
  memset(st1, 0, sizeof(st1));
  memset(st2, 0, sizeof(st2));
  memset(chst, 0, sizeof(chst));
  for (uint8_t c = 0; c < 4; c++) {
    memset(delay_buf[c], 0, sizeof(delay_buf[c]));
    delay_idx[c] = 0;
  }
  // Рампы громкости тоже к нулю: иначе на первом кадре усиление прыгнет
  // от значения, набранного до паузы.
  for (uint8_t c = 0; c < 4; c++) gain[c] = 0.0f;
  // Замеряем первые кадры нового потока: если ступенька на старте есть, она
  // попадёт в этот максимум.
  click_start_max[0] = 0; click_start_max[1] = 0;
  click_dst = 0;
  click_probe.store(CLICK_PROBE_FRAMES, std::memory_order_relaxed);
  click_probe_armed.store(true, std::memory_order_relaxed);
  logEvent(7, 0);
}

// Плавное сведение выхода в ноль при остановке потока.
//
// Пока телефон молчит, кольцо пусто и задача вывода простаивает, не записав
// в I2S ничего: DMA доигрывает последний блок и замирает на нём. Если в этом
// отсчёте ненулевое значение (а оно почти всегда ненулевое — сигнал живой),
// усилитель получает ступеньку и динамик щёлкает на паузе.
//
// Поэтому при остановке потока прогоняем через DSP тишину, уводя gain в ноль
// рампой FADE_K, и дописываем в I2S блок, который уже целиком нулевой: DMA
// замирает на нуле, и следующий поток начинается с тишины, а не со ступеньки.
#define FADE_MAX_FRAMES 2048   // 46 мс @44.1 кГц
void fadeOutDsp() {
  dsp_fade = true;
  static int16_t zbuf[BLOCK_FRAMES * 2];
  memset(zbuf, 0, sizeof(zbuf));
  uint32_t frames = 0;
  // Выходим, как только gain всех каналов ниже FADE_EPS, иначе жжём лишние
  // 46 мс тишины в I2S на каждой паузе. Потолок нужен на случай, если рампы
  // не достигли нуля: громкость могла вырасти в ходе гашения (новые цели от
  // команды vol:), и тогда gain снова растёт, а не падает.
  while (frames < FADE_MAX_FRAMES) {
    processBlock(zbuf, BLOCK_FRAMES);
    frames += BLOCK_FRAMES;
    bool quiet = true;
    for (uint8_t c = 0; c < 4; c++) {
      if (gain[c] > FADE_EPS) { quiet = false; break; }
    }
    if (quiet) break;
  }
  dsp_fade = false;
  // Последний блок нулевой целиком: DMA останавливается на тишине. Замеряем
  // его размах — это ответ на вопрос, остался ли щелчок на остановке.
  click_start_max[0] = 0; click_start_max[1] = 0;
  click_dst = 1;
  click_probe.store(BLOCK_FRAMES, std::memory_order_relaxed);
  click_probe_armed.store(true, std::memory_order_relaxed);
  processBlock(zbuf, BLOCK_FRAMES);
  for (uint8_t c = 0; c < 4; c++) gain[c] = 0.0f;
  logEvent(8, (uint16_t)frames);
}

void audioTask(void *) {
  setI2SWriteTimeoutMs(pdMS_TO_TICKS(I2S_WRITE_TIMEOUT_MS));
  for (;;) {
    if (ring_flush.exchange(false)) ringFlush();
    // Старт потока: обнуляем состояние фильтров и рампы. После паузы
    // (заблокированный экран телефона, другой трек) biquad'ы хранят отсчёты
    // двухминутной давности, и первый же блок выходит со ступенькой — щелчком.
    // Сброс делается здесь, в задаче вывода: из колбэка A2DP это была бы гонка
    // с processBlock по тем же переменным.
    if (dsp_reset_pending.exchange(false, std::memory_order_acquire)) {
      resetDspState();
      // Поток успел возобновиться, пока гашение ждало в очереди: гасить уже
      // нечего, а 34 мс нуля в начале трека были бы слышны как заикание.
      dsp_fade_pending.store(false, std::memory_order_release);
    }
    // Остановка потока: сначала гасим выход, и только потом берём новые данные.
    // Иначе остаток старого блока попал бы в I2S после начала тишины.
    bool fading = dsp_fade_pending.exchange(false, std::memory_order_acquire);
    refreshParams();
    if (fading) fadeOutDsp();
    int n;
    bool from_ring = false;
    if (test_mode.load(std::memory_order_relaxed) != 0) n = genTestBlock(inbuf, BLOCK_FRAMES);
    else { n = ringPop(inbuf, BLOCK_FRAMES); from_ring = true; }
    if (n <= 0) { audio_idle.fetch_add(1, std::memory_order_relaxed); vTaskDelay(1); continue; }
    if (from_ring && st_active.load(std::memory_order_relaxed)) verifyRingBlock(n);
    processBlock(inbuf, n);
  }
}

// ===================== КОЛБЭК A2DP (задача BT) =========================
void write_data_stream(const uint8_t *data, uint32_t length) {
  if (test_mode.load(std::memory_order_relaxed) != 0) return;

  uint32_t cb_now = millis();
  // Отметка времени последнего пакета. Общая с audio_state_cb: на паузе
  // колбэк A2DP не приходит вовсе, поэтому сбросить отметку из самого
  // write_data_stream невозможно — первый пакет нового потока сравнился бы
  // с временем до паузы и дал ложный Starve (на живом устройстве v=3075 мс
  // при нулевых Underrun и RingDrops). Сбрасывает audio_state_cb.
  //
  // Дополнительно счётчик живёт только внутри играющего потока. Раньше
  // отметка обновлялась всегда, и пауза источника (заблокированный экран
  // телефона останавливает A2DP, но is_playing остаётся true) попадала в
  // Starve целиком: счётчик показывал 161 с «недокорма» при нулевых
  // Underrun и RingDrops.
  //
  // Порог 40 мс оказался ниже межпакетного интервала: телефон присылает
  // аудио пачками, и между пакетами штатно бывает 41-53 мс. На живом
  // устройстве это давало n=184 при max=3019 мс и нулевых Underrun/RingDrops —
  // счётчик врал. Ориентир — не пакетизация, а запас: RING_FRAMES=4096
  // держат 93 мс, телефон рвёт поток интервалами до 72 мс, и опустошение
  // кольца начинается в районе 80 мс. Ниже этого порога зазор не означает
  // потерю данных, поэтому и не считается.
  if (is_playing.load()) {
    uint32_t prev = starve_last_ms.exchange(cb_now, std::memory_order_relaxed);
    if (prev) {
      uint32_t gap = cb_now - prev;
      if (gap > STARVE_GAP_MS) {
        starve_cnt.fetch_add(1, std::memory_order_relaxed);
        if (gap > starve_max_ms.load(std::memory_order_relaxed)) starve_max_ms.store(gap, std::memory_order_relaxed);
        logEvent(1, (uint16_t)min(gap, 65000ul));
      }
    }
  }

  const int16_t *in = (const int16_t *)data;
  int n = (int)(length / 4);
  if (n <= 0) return;
  g_in_frames.fetch_add((uint32_t)n, std::memory_order_relaxed);

  static int16_t obuf[BLOCK_FRAMES * 2];
  static float rpos = 0;
  static int16_t pl = 0, pr = 0;
  static bool rs_init = false;
  static bool was_48 = false;

  bool now_48 = src_48k.load(std::memory_order_relaxed);
  if (now_48 != was_48) { was_48 = now_48; rpos = 0; rs_init = false; }
  if (!now_48) { ringPush(in, n); return; }

  int i = 0, on = 0;
  if (!rs_init && n > 0) { pl = in[0]; pr = in[1]; i = 1; rs_init = true; }
  for (;;) {
    while (rpos >= 1 && i < n) { pl = in[i * 2]; pr = in[i * 2 + 1]; i++; rpos -= 1; }
    while (on < BLOCK_FRAMES && i < n) {
      float cl = in[i * 2], cr = in[i * 2 + 1];
      obuf[on * 2]     = (int16_t)(pl + (cl - pl) * rpos);
      obuf[on * 2 + 1] = (int16_t)(pr + (cr - pr) * rpos);
      on++; rpos += RATIO48;
      while (rpos >= 1 && i < n) { pl = in[i * 2]; pr = in[i * 2 + 1]; i++; rpos -= 1; }
    }
    if (on > 0) { ringPush(obuf, on); on = 0; }
    if (i >= n) break;
  }
}

// ===================== CALLBACKS BT ====================================
void bt_state_cb(esp_a2d_connection_state_t state, void *) {
  bt_connected.store(state == ESP_A2D_CONNECTION_STATE_CONNECTED);
  ui_dirty.store(true);
}
void audio_state_cb(esp_a2d_audio_state_t state, void *) {
  // Сброс состояния фильтров только на переходе «не играем → играем».
  // is_playing ставим выше; счётчик перехода живёт здесь, потому что
  // write_data_stream — другой поток и общего состояния с ним не имеет.
  static bool was_playing = false;
  bool now = (state == ESP_A2D_AUDIO_STATE_STARTED);
  is_playing.store(now);
  // Отметку для Starve сбрасываем на каждой границе потока, а не только на
  // остановке: первый пакет нового потока приходит через 2-3 с после resume,
  // и без сброса он посчитал бы всю паузу провалом.
  starve_last_ms.store(0, std::memory_order_relaxed);
  if (now && !was_playing) {
    // Событие приходит до первого аудиоблока нового потока, но гарантии по
    // времени нет, поэтому просим задачу вывода сбросить себя. Иначе после
    // паузы biquad'ы хранят отсчёты двухминутной давности, и первый блок
    // выходит со ступенькой — щелчком на старте.
    dsp_reset_pending.store(true, std::memory_order_release);
    ring_flush.store(true);
  } else if (!now && was_playing) {
    // Остановка потока: телефон замолчал, но усилитель ещё держит последний
    // отсчёток. Просим задачу вывода свести выход в ноль — иначе щелчок на паузе.
    // Порядок важен: сначала гашение, остаток кольца не должен попасть в I2S
    // после него, поэтому кольцо сбрасывается тут же.
    dsp_fade_pending.store(true, std::memory_order_release);
    ring_flush.store(true);
  }
  was_playing = now;
  ui_dirty.store(true);
}

// Частота источника берётся из переговоров A2DP, а не оценивается по числу кадров:
// к моменту первого аудиоблока значение уже известно точно.
void on_sample_rate(uint16_t rate) {
  bool is48 = rate > 46000;
  bool was = src_48k.exchange(is48);
  if (was != is48) {
    ui_dirty.store(true);
    logEvent(4, is48 ? 48 : 44);
    Serial.print(F("Sample rate: "));
    Serial.println(is48 ? F("48 kHz") : F("44.1 kHz"));
  }
}

void say(const String &s) {
  Serial.println(s);
  if (SerialBT.hasClient()) SerialBT.println(s);
}
void say(const __FlashStringHelper *s) {
  Serial.println(s);
  if (SerialBT.hasClient()) SerialBT.println(s);
}

// ===================== NVS =============================================
void saveAllParams() {
  uint32_t t0 = millis();
  PBlob b;
  memset(&b, 0, sizeof(b));
  b.version = PBLOB_VERSION;
  b.v0 = vol_z[0]; b.v1 = vol_z[1];
  b.m0 = muted_z[0]; b.m1 = muted_z[1];
  b.fc = fc_hz; b.hp = sub_hz; b.sub = sub_on ? 1 : 0; b.xot = xo_type;
  b.xoon = xo_on ? 1 : 0;
  b.tlf = tlf_db; b.thf = thf_db;
  b.eq0 = eq_db[0]; b.eq1 = eq_db[1]; b.eq2 = eq_db[2];
  b.bal = bal; b.tvol = test_vol.load();
  for (uint8_t c = 0; c < 4; c++) { b.chh[c] = ch_hp[c]; b.chl[c] = ch_lp[c]; b.dly[c] = ch_delay[c]; }
  b.swp = lr_swap ? 1 : 0;
  bool ok = (prefs.putBytes("blob", &b, sizeof(b)) == sizeof(b));
  uint32_t dt = millis() - t0;
  if (dt > nvs_max_ms.load(std::memory_order_relaxed)) nvs_max_ms.store(dt, std::memory_order_relaxed);
  if (dt > 50) logEvent(3, (uint16_t)dt);
  if (!ok) Serial.println(F("NVS: save error"));
}

// Перенос всех полей блоба в живые параметры, кроме xoon: его вызывающий
// разбирает сам, потому что в блоке v21 этого поля ещё не было.
void applyBlob(const PBlob &b) {
  vol_z[0] = b.v0; vol_z[1] = b.v1;
  muted_z[0] = b.m0; muted_z[1] = b.m1;
  fc_hz = b.fc; sub_hz = b.hp; sub_on = (b.sub != 0); xo_type = b.xot;
  tlf_db = b.tlf; thf_db = b.thf;
  eq_db[0] = b.eq0; eq_db[1] = b.eq1; eq_db[2] = b.eq2;
  bal = b.bal; test_vol.store(b.tvol);
  for (uint8_t c = 0; c < 4; c++) {
    ch_hp[c] = b.chh[c]; ch_lp[c] = b.chl[c]; ch_delay[c] = b.dly[c];
  }
}

void sanitizeParams() {
  for (uint8_t c = 0; c < 2; c++) if (!isfinite(vol_z[c]) || vol_z[c] < 0 || vol_z[c] > 1) vol_z[c] = 0.10f;
  if (!isfinite(fc_hz) || fc_hz < 200 || fc_hz > 1000) fc_hz = 400;
  if (!isfinite(sub_hz) || sub_hz < 20 || sub_hz > 80) sub_hz = 45;
  if (!isfinite(tlf_db) || tlf_db < -6 || tlf_db > 3) tlf_db = 0;
  if (!isfinite(thf_db) || thf_db < -6 || thf_db > 3) thf_db = -1;
  if (!isfinite(bal) || bal < -10 || bal > 10) bal = 0;
  for (uint8_t e = 0; e < 3; e++) if (!isfinite(eq_db[e]) || eq_db[e] < -12 || eq_db[e] > 12) eq_db[e] = 0;
  if (xo_type < 1 || xo_type > 2) xo_type = 1;
  { float tv = test_vol.load(); if (!isfinite(tv) || tv < 0 || tv > 1) test_vol.store(0.06f); }
  for (uint8_t c = 0; c < 4; c++) {
    if (!isfinite(ch_hp[c]) || ch_hp[c] < 0 || ch_hp[c] > 20000) ch_hp[c] = 0;
    if (!isfinite(ch_lp[c]) || ch_lp[c] < 0 || ch_lp[c] > 20000) ch_lp[c] = 0;
    if (ch_delay[c] > MAX_DELAY_SAMPLES) ch_delay[c] = 0;
  }
}

void applyPreset(uint8_t p) {
  if (p == 0)      { fc_hz=400; tlf_db=0;  thf_db=-1; sub_on=true; eq_db[0]=eq_db[1]=eq_db[2]=0; }
  else if (p == 1) { fc_hz=500; tlf_db=-2; thf_db=1;  sub_on=true; eq_db[0]=0; eq_db[1]=2; eq_db[2]=0; }
  else if (p == 2) { fc_hz=400; tlf_db=-4; thf_db=-2; sub_on=true; eq_db[0]=eq_db[1]=eq_db[2]=0; }
  else             { fc_hz=350; tlf_db=3;  thf_db=0;  sub_on=true; eq_db[0]=2; eq_db[1]=0; eq_db[2]=1; }
  scheduleFilterUpdate();
  paramsDirty = true; paramsChangedAt = millis();
  say("Preset " + String(p));
  ui_dirty.store(true);
}

// ===================== ПАРСЕР КОМАНД ==================================
static char cmd_buf_usb[64], cmd_buf_bt[64];
static uint8_t cmd_len_usb = 0, cmd_len_bt = 0;
static bool skip_usb = false, skip_bt = false;

const char *pollLine(Stream &s, char *buf, uint8_t &len, bool &skip) {
  while (s.available()) {
    char c = (char)s.read();
    if (c == '\n' || c == '\r') {
      if (!skip && len > 0) { buf[len] = 0; len = 0; return buf; }
      skip = false; len = 0;
    } else if (!skip) {
      if (len < 63) buf[len++] = c;
      else skip = true;
    }
  }
  return nullptr;
}

static bool cmdIs(const char *cmd, const char *prefix) {
  return strncmp(cmd, prefix, strlen(prefix)) == 0;
}

static bool parseFloat(const char *s, float &out, float min, float max) {
  if (!s || *s == '\0') return false;
  char *endptr;
  float v = strtof(s, &endptr);
  if (endptr == s || !isfinite(v) || v < min || v > max) return false;
  out = v;
  return true;
}

static bool safeCmdVal(const char *cmd, float &out, float min, float max) {
  const char *p = strchr(cmd, ':');
  if (!p) return false;
  return parseFloat(p + 1, out, min, max);
}

static bool cmdArgIs(const char *cmd, const char *arg) {
  const char *p = strchr(cmd, ':');
  return p && strcmp(p + 1, arg) == 0;
}

#define TOUCH() do { scheduleFilterUpdate(); paramsDirty = true; paramsChangedAt = millis(); } while (0)

void dispatchCommand(const char *cmd) {
  float v;
  if (cmdIs(cmd, "vol:")) {
    if (safeCmdVal(cmd, v, 0, 100)) { vol_z[0] = vol_z[1] = v / 100.0f; TOUCH(); }
  }
  else if (cmdIs(cmd, "v0:")) {
    if (safeCmdVal(cmd, v, 0, 100)) { vol_z[0] = v / 100.0f; TOUCH(); }
  }
  else if (cmdIs(cmd, "v1:")) {
    if (safeCmdVal(cmd, v, 0, 100)) { vol_z[1] = v / 100.0f; TOUCH(); }
  }
  else if (cmdIs(cmd, "bal:")) {
    if (safeCmdVal(cmd, v, -10, 10)) { bal = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "mute:")) {
    if (safeCmdVal(cmd, v, 0, 1)) { uint8_t z = (uint8_t)v; muted_z[z] = !muted_z[z]; TOUCH(); }
  }
  else if (cmdIs(cmd, "fc:")) {
    if (safeCmdVal(cmd, v, 200, 1000)) { fc_hz = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "hp:")) {
    if (safeCmdVal(cmd, v, 20, 80)) { sub_hz = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "sub:")) {
    if (safeCmdVal(cmd, v, 0, 1)) { sub_on = ((int)v == 1); TOUCH(); }
  }
  else if (cmdIs(cmd, "tlf:")) {
    if (safeCmdVal(cmd, v, -6, 3)) { tlf_db = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "thf:")) {
    if (safeCmdVal(cmd, v, -6, 3)) { thf_db = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "eql:")) {
    if (safeCmdVal(cmd, v, -12, 12)) { eq_db[0] = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "eqm:")) {
    if (safeCmdVal(cmd, v, -12, 12)) { eq_db[1] = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "eqh:")) {
    if (safeCmdVal(cmd, v, -12, 12)) { eq_db[2] = v; TOUCH(); }
  }
  else if (cmdIs(cmd, "xotype:")) {
    if (safeCmdVal(cmd, v, 1, 2)) { xo_type = (uint8_t)v; TOUCH(); }
  }
  else if (cmdIs(cmd, "xo:")) {
    if (safeCmdVal(cmd, v, 0, 1)) { xo_on = ((int)v == 1); TOUCH(); }
  }
  else if (cmdIs(cmd, "swap:")) {
    if (safeCmdVal(cmd, v, 0, 1)) { lr_swap = ((int)v == 1); TOUCH(); }
  }
  else if (strncmp(cmd, "chhp:", 5) == 0 || strncmp(cmd, "chlp:", 5) == 0) {
    bool is_hp = (cmd[2] == 'h');
    const char *rest = cmd + 5;
    if (!isdigit((unsigned char)rest[0])) return;
    int ch = rest[0] - '0';
    if (ch < 0 || ch > 3) return;
    const char *q = strchr(rest, ':');
    float fv;
    if (q && parseFloat(q + 1, fv, 0, 20000)) {
      if (fv != 0 && fv < 20) fv = 20;
      if (is_hp) ch_hp[ch] = fv; else ch_lp[ch] = fv;
      TOUCH();
    }
  }
  else if (strncmp(cmd, "delay", 5) == 0 && isdigit((unsigned char)cmd[5])) {
    int ch = cmd[5] - '0';
    if (ch < 0 || ch > 3) return;
    const char *q = strchr(cmd + 5, ':');
    float fv;
    if (q && parseFloat(q + 1, fv, 0, MAX_DELAY_SAMPLES)) { ch_delay[ch] = (uint16_t)fv; TOUCH(); }
  }
  else if (cmdIs(cmd, "tvol:")) {
    if (safeCmdVal(cmd, v, 0, 100)) {
      test_vol.store(v / 100.0f);
      paramsDirty = true; paramsChangedAt = millis();
      say("Test volume: " + String((int)v) + "%");
    }
  }
  else if (cmdIs(cmd, "preset:")) {
    if (safeCmdVal(cmd, v, 0, 3)) applyPreset((uint8_t)v);
  }
  else if (strcmp(cmd, "play")  == 0) { if (a2dp_sink.is_avrc_connected()) a2dp_sink.play(); }
  else if (strcmp(cmd, "pause") == 0) { if (a2dp_sink.is_avrc_connected()) a2dp_sink.pause(); }
  else if (strcmp(cmd, "next")  == 0) { if (a2dp_sink.is_avrc_connected()) a2dp_sink.next(); }
  else if (strcmp(cmd, "prev")  == 0) { if (a2dp_sink.is_avrc_connected()) a2dp_sink.previous(); }
  else if (cmdIs(cmd, "test:")) {
    uint8_t tm = 0;
    if      (cmdArgIs(cmd, "off"))   tm = 0;
    else if (cmdArgIs(cmd, "all"))   tm = 1;
    else if (cmdArgIs(cmd, "l"))     tm = 2;
    else if (cmdArgIs(cmd, "r"))     tm = 3;
    else if (cmdArgIs(cmd, "woof"))  tm = 4;
    else if (cmdArgIs(cmd, "tweet")) tm = 5;
    else if (cmdArgIs(cmd, "1"))     tm = 6;
    else if (cmdArgIs(cmd, "2"))     tm = 7;
    else if (cmdArgIs(cmd, "3"))     tm = 8;
    else if (cmdArgIs(cmd, "4"))     tm = 9;
    else if (cmdArgIs(cmd, "anti"))  tm = 10;
    else if (cmdArgIs(cmd, "sweep")) tm = 11;
    test_mode.store(tm);
    ring_flush.store(true);
    say("Test mode: " + String(tm));
  }
  else if (cmdIs(cmd, "tf:")) {
    if (safeCmdVal(cmd, v, 20, 20000)) test_freq.store(v);
  }
  else if (strcmp(cmd, "status") == 0) {
    say("V0=" + String((int)(vol_z[0]*100)) + "% V1=" + String((int)(vol_z[1]*100)) + "% bal=" + String(bal));
    say("Fc=" + String((int)fc_hz) + "Hz hp=" + String((int)sub_hz) + "Hz sub=" + String(sub_on ? "ON" : "OFF"));
    say("XO: " + String(xo_type == 2 ? "LR4" : "Butter") + (xo_on ? " ON" : " OFF"));
    say("TLF=" + String(tlf_db) + "dB THF=" + String(thf_db) + "dB");
    say("EQ: L=" + String(eq_db[0]) + " M=" + String(eq_db[1]) + " H=" + String(eq_db[2]));
    say(String("SWP: ") + String(lr_swap ? 1 : 0));
    say(String("BT: ") + String(bt_connected.load() ? "ON" : "OFF") + " | SPP: " + String(SerialBT.hasClient() ? "ON" : "OFF"));
    say(String("Src: ") + String(src_48k.load() ? "48" : "44.1") + " kHz");
    say("Test: " + String(test_mode.load()) + " TVol=" + String((int)(test_vol.load()*100)) + "%");
    say("CHF: " + String((int)ch_hp[0]) + "/" + String((int)ch_lp[0]) + " "
               + String((int)ch_hp[1]) + "/" + String((int)ch_lp[1]) + " "
               + String((int)ch_hp[2]) + "/" + String((int)ch_lp[2]) + " "
               + String((int)ch_hp[3]) + "/" + String((int)ch_lp[3]));
    say("Delay: " + String(ch_delay[0]) + "/" + String(ch_delay[1]) + "/"
                + String(ch_delay[2]) + "/" + String(ch_delay[3]));
  }
  else if (strcmp(cmd, "click") == 0) {
    // Щелчок не виден обычными счётчиками: данные не теряются, неверно только
    // начало отсчёта. Поэтому сравниваем размах выхода на границах потока
    // с размахом обычного сигнала — щелчок это размах сигнала, взятый
    // в момент, когда сигнала быть не должно.
    uint32_t lvl = click_level ? click_level : 1;
    float start_db = 20.0f * log10f((float)(click_start_max[0] ? click_start_max[0] : 1) / 32768.0f);
    float fade_db  = 20.0f * log10f((float)(click_fade_max[0] ? click_fade_max[0] : 1) / 32768.0f);
    float lvl_db   = 20.0f * log10f((float)lvl / 32768.0f);
    say("Signal level: " + String(click_level) + " (" + String(lvl_db, 1) + " dBFS)");
    say("Start max: " + String(click_start_max[0]) + "/" + String(click_start_max[1])
        + " (" + String(start_db, 1) + " dBFS)");
    say("Fade end max: " + String(click_fade_max[0]) + "/" + String(click_fade_max[1])
        + " (" + String(fade_db, 1) + " dBFS)");
    say("Start rel: " + String((int)(100.0f * click_start_max[0] / lvl)) + "%  Fade rel: "
        + String((int)(100.0f * click_fade_max[0] / lvl)) + "%");
    say("Click window: " + String(click_probe_armed.load() ? "measuring" : "idle"));
  }
  else if (strcmp(cmd, "stats") == 0) {
    say("Frames: " + String(g_in_frames.load()));
    say("Blocks: " + String(audio_blocks.load()) + " AF: " + String(audio_frames.load()) +
        " Idle: " + String(audio_idle.load()));
    say("Underrun: Z1=" + String(und_z[0].load()) + " Z2=" + String(und_z[1].load()));
    say("RingDrops: " + String(ring_drops.load()) + " BadSamples: " + String(st_errors.load()));
    say("Clips: " + String(clip_cnt[0].load()) + "/" + String(clip_cnt[1].load()) +
        "/" + String(clip_cnt[2].load()) + "/" + String(clip_cnt[3].load()));
    say("Starve: n=" + String(starve_cnt.load()) + " max=" + String(starve_max_ms.load()) + "ms");
    say("DSP: max=" + String(dsp_max_us.load()) + "us WR: max=" + String(wr_max_us.load()) + "us");
    say("Loop: max=" + String(loop_max_ms.load()) + "ms NVS: max=" + String(nvs_max_ms.load()) + "ms");
    say("HeapMin: " + String(esp_get_minimum_free_heap_size()));
  }
  else if (strcmp(cmd, "evlog") == 0) {
    say(F("evlog: only via USB"));
    dumpEventLog();
  }
  else if (strcmp(cmd, "heap") == 0) say("Free heap: " + String(ESP.getFreeHeap()));
  else if (strcmp(cmd, "save") == 0)   { saveAllParams(); paramsDirty = false; say(F("Saved")); }
  else if (strcmp(cmd, "reboot") == 0) { say(F("Rebooting...")); delay(500); ESP.restart(); }
  else if (strcmp(cmd, "factory") == 0){ prefs.clear(); say(F("Factory reset")); delay(500); ESP.restart(); }
  else if (strcmp(cmd, "help") == 0) {
    say(F("vol:N v0:N v1:N bal:N mute:N"));
    say(F("fc:N hp:N sub:0/1 xo:0/1 xotype:1-2 tlf:N thf:N"));
    say(F("eql:N eqm:N eqh:N preset:0-3"));
    say(F("swap:0/1 (swap L/R outputs)"));
    say(F("chhp:C:F chlp:C:F delayC:N"));
    say(F("tvol:N (test volume, default 6%)"));
    say(F("play pause next prev"));
    say(F("test:all/l/r/woof/tweet/1-4/anti/sweep/off tf:N"));
    say(F("status stats click evlog heap"));
    say(F("save reboot factory"));
  }
  ui_dirty.store(true);
}

void handleSerial() {
  const char *c1 = pollLine(Serial, cmd_buf_usb, cmd_len_usb, skip_usb);
  if (c1) dispatchCommand(c1);
  const char *c2 = pollLine(SerialBT, cmd_buf_bt, cmd_len_bt, skip_bt);
  if (c2) dispatchCommand(c2);
}

// ===================== OLED ============================================
bool oledInit() { oled_ok = disp.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR); return oled_ok; }

void updateGeneralDisplay() {
  if (!oled_ok) return;
  bool bt = bt_connected.load();
  bool play = is_playing.load();
  bool spp = SerialBT.hasClient();
  // Громкость на OLED общая: два числа занимали обе нижние строки экрана и
  // читались как отдельные каналы, хотя разъёмов у усилителя два стерео-входа,
  // а слышно на выходе одно поле. Общее — среднее уровней; если каналы
  // разошлись (bal), рядом ставится звёздочка: значит это не точное значение
  // ни одного из них, и без неё показано было бы то, чего на самом деле нет.
  float v0 = vol_z[0], v1 = vol_z[1];
  int vavg = (int)(((v0 + v1) * 50.0f) + 0.5f);
  if (vavg > 100) vavg = 100;
  bool split = fabsf(v0 - v1) > 0.005f;
  disp.clearDisplay();
  disp.setTextSize(2); disp.setTextColor(SSD1306_WHITE);
  disp.setCursor(0, 0); disp.print(bt ? F("BT ON") : F("BT --"));
  if (spp) { disp.setCursor(64, 0); disp.print(F("SPP")); }
  disp.setCursor(0, 16);
  if (muted_z[0] && muted_z[1]) disp.print(F("MUTED"));
  else disp.print(play ? F("PLAY") : F("PAUSE"));
  disp.setCursor(0, 32);
  disp.print(F("VOL")); disp.setCursor(52, 32);
  disp.print(vavg); disp.print(F("%"));
  if (split) disp.print(F("*"));
  // Баланс без громкости не показать: на OLED осталась свободная строка,
  // и молчаливое место читалось бы как «баланс = 0».
  if (split) { disp.setCursor(0, 48); disp.print(F("BAL")); disp.setCursor(52, 48); disp.print((int)(bal * 100.0f)); }
  disp.display();
}

// ===================== SETUP / LOOP ===================================
void setup() {
  Serial.begin(115200); delay(500);
  Serial.println(F("\n\nboot: bi-amp v35 (lr swap instead of phase inversion)"));
  Serial.print(F("Heap: ")); Serial.println(ESP.getFreeHeap());
  Serial.println(F("Type 'help' for commands"));

  if (!prefs.begin(NVS_NS, false)) Serial.println(F("NVS: open error"));
  PBlob b;
  size_t got = prefs.getBytes("blob", &b, sizeof(b));
  if (got == sizeof(b) && b.version == PBLOB_VERSION) {
    applyBlob(b);
    xo_on = (b.xoon != 0);
    sanitizeParams();
    Serial.println(F("NVS: blob loaded (v23)"));
  } else if (got == sizeof(b) && b.version == 22) {
    // Блоб v22 отличается только названием байта invm → swp, размер и
    // смещения те же. Перестановка Л/П на этом месте была нулём, поэтому
    // её значение не восстанавливаем: старый invm относился к инверсии
    // фазы, и переносить его в swap было бы неверно.
    applyBlob(b);
    xo_on = (b.xoon != 0);
    lr_swap = false;
    sanitizeParams();
    saveAllParams();
    Serial.println(F("NVS: blob migrated v22 -> v23"));
  } else if (got == sizeof(b) - 1 && b.version == 21) {
    // Блоб v21 — та же упакованная структура без последнего байта xoon.
    // Структура дописана в конец, поэтому прежние поля лежат на тех же
    // смещениях: принимаем блоб на байт короче и переписываем уже в v22.
    // Простое «не совпала версия → ветка legacy» обнулило бы у пользователя
    // все настройки, потому что saveAllParams пишет только ключ blob.
    applyBlob(b);
    xo_on = true;
    lr_swap = false;
    sanitizeParams();
    saveAllParams();
    Serial.println(F("NVS: blob migrated v21 -> v23"));
  } else {
    for (uint8_t c = 0; c < 2; c++) {
      char kv[4] = {'v', (char)('0'+c), 0, 0};
      char km[4] = {'m', (char)('0'+c), 0, 0};
      vol_z[c] = prefs.getFloat(kv, 0.10f); muted_z[c] = prefs.getBool(km, false);
    }
    fc_hz = prefs.getFloat("fc", 400); sub_hz = prefs.getFloat("hp", 45);
    sub_on = prefs.getBool("sub", true);
    xo_on = prefs.getBool("xoon", true);
    tlf_db = prefs.getFloat("tlf", 0); thf_db = prefs.getFloat("thf", -1);
    eq_db[0] = prefs.getFloat("eql", 0); eq_db[1] = prefs.getFloat("eqm", 0); eq_db[2] = prefs.getFloat("eqh", 0);
    bal = prefs.getFloat("bal", 0);
    xo_type = prefs.getUChar("xot", 1);
    { float tv = prefs.getFloat("tvol", 0.06f); test_vol.store(tv); }
    lr_swap = prefs.getBool("swp", false);
    for (uint8_t c = 0; c < 4; c++) {
      char kh[4] = {'c', (char)('0'+c), 'h', 0};
      char kl[4] = {'c', (char)('0'+c), 'l', 0};
      char kd[4] = {'c', (char)('0'+c), 'd', 0};
      ch_hp[c] = prefs.getFloat(kh, 0); ch_lp[c] = prefs.getFloat(kl, 0);
      ch_delay[c] = prefs.getUShort(kd, 0);
    }
    sanitizeParams();
    saveAllParams();
    Serial.println(F("NVS: legacy migrated to v23"));
  }
  scheduleFilterUpdate();

  Wire.begin(OLED_SDA, OLED_SCL);
  Wire.setClock(400000);
  Wire.setTimeout(10);
  Serial.println(oledInit() ? F("OLED OK") : F("OLED ERR"));

  bool ok1, ok2;
  auto cfg1 = i2s_z1.defaultConfig(TX_MODE);
  cfg1.pin_bck = Z1_BCK; cfg1.pin_ws = Z1_LCK; cfg1.pin_data = Z1_DIN; cfg1.pin_mck = -1;
  cfg1.sample_rate = SAMPLE_RATE; cfg1.channels = 2; cfg1.bits_per_sample = BITS_PER_SAMPLE;
  cfg1.port_no = 0; cfg1.buffer_count = BT_ESP_I2S_BUFFERS; cfg1.buffer_size = 512; cfg1.auto_clear = true;
  ok1 = i2s_z1.begin(cfg1);
  auto cfg2 = i2s_z2.defaultConfig(TX_MODE);
  cfg2.pin_bck = Z2_BCK; cfg2.pin_ws = Z2_LCK; cfg2.pin_data = Z2_DIN; cfg2.pin_mck = -1;
  cfg2.sample_rate = SAMPLE_RATE; cfg2.channels = 2; cfg2.bits_per_sample = BITS_PER_SAMPLE;
  cfg2.port_no = 1; cfg2.buffer_count = BT_ESP_I2S_BUFFERS; cfg2.buffer_size = 512; cfg2.auto_clear = true;
  ok2 = i2s_z2.begin(cfg2);
  Serial.print(F("I2S: z1=")); Serial.print(ok1); Serial.print(F(" z2=")); Serial.println(ok2);
  Serial.print(F("Heap after I2S: ")); Serial.println(ESP.getFreeHeap());

  xTaskCreatePinnedToCore(audioTask, "audio", AUDIO_TASK_STACK, nullptr, AUDIO_TASK_PRIO, nullptr, AUDIO_TASK_CORE);
  Serial.println(F("Audio task: started"));
  ringSelfTest();

  Serial.print(F("Heap before BT: ")); Serial.println(ESP.getFreeHeap());

  a2dp_sink.set_stream_reader(write_data_stream, false);
  a2dp_sink.set_on_connection_state_changed(bt_state_cb);
  a2dp_sink.set_on_audio_state_changed(audio_state_cb);
  a2dp_sink.set_sample_rate_callback(on_sample_rate);
  a2dp_sink.set_auto_reconnect(true);
  a2dp_sink.start(DEVICE_NAME);
  Serial.println(F("A2DP: started"));
  Serial.print(F("Heap after A2DP: ")); Serial.println(ESP.getFreeHeap());

  bool spp_ok = SerialBT.begin(DEVICE_NAME);
  Serial.print(F("SPP: ")); Serial.println(spp_ok ? F("OK") : F("FAIL"));

  Serial.print(F("Heap final: ")); Serial.println(ESP.getFreeHeap());
  ui_dirty.store(true);
}

void loop() {
  static uint32_t last_house = 0, last_draw = 0;
  static uint32_t prev_loop_ms = 0;
  uint32_t now = millis();

  if (prev_loop_ms) {
    uint32_t p = now - prev_loop_ms;
    if (p > loop_max_ms.load(std::memory_order_relaxed)) loop_max_ms.store(p, std::memory_order_relaxed);
    if (p > 50) logEvent(6, (uint16_t)min(p, 65000ul));
  }
  prev_loop_ms = now;

  handleSerial();

  if (paramsDirty && (now - paramsChangedAt >= NVS_SAVE_DELAY)) { saveAllParams(); paramsDirty = false; }

  if (now - last_house >= 5000) {
    last_house = now;
    uint32_t u0 = und_z[0].load(), u1 = und_z[1].load();
    uint32_t c0 = clip_cnt[0].load(), c1 = clip_cnt[1].load(), c2 = clip_cnt[2].load(), c3 = clip_cnt[3].load();
    uint32_t rd = ring_drops.load();
    if (u0 || u1 || c0 || c1 || c2 || c3 || rd) {
      Serial.print(F("WARN: underrun Z1=")); Serial.print(u0);
      Serial.print(F(" Z2=")); Serial.print(u1);
      Serial.print(F(" ring=")); Serial.print(rd);
      Serial.print(F(" clips=")); Serial.print(c0); Serial.print('/'); Serial.print(c1);
      Serial.print('/'); Serial.print(c2); Serial.print('/'); Serial.println(c3);
      und_z[0].store(0); und_z[1].store(0);
      clip_cnt[0].store(0); clip_cnt[1].store(0); clip_cnt[2].store(0); clip_cnt[3].store(0);
    }
  }

  if (ui_dirty.load() && (now - last_draw >= 200)) {
    ui_dirty.store(false);
    updateGeneralDisplay();
    last_draw = now;
  }

  delay(10);
}
