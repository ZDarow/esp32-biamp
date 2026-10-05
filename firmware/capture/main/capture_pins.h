// Разводка сниффера на ESP32-S3-DevKitC-1.
//
// Сниффер подключается ПАРАЛЛЕЛЬНО к выходу Master и ничего в разрыв не
// вставляет: вход подчинённого режима имеет высокое сопротивление, нагрузку
// на источник не меняет. Земли обязательны — иначе уровни 3.3 В едут по
// воздуху.
//
//   Master (ESP32 WROOM-32)          ESP32-S3-DevKitC-1
//   GPIO2   I²S0 DOUT (зона Z1)  ───▶  CAP_Z1_DATA  (S3 GPIO17)
//   GPIO15  I²S0 LRCK (зона Z1)  ───▶  CAP_Z1_LRCK  (S3 GPIO18)
//   GPIO4   I²S0 BCLK (зона Z1)  ───▶  CAP_Z1_BCK   (S3 GPIO16)
//   GPIO26  I²S1 DOUT (зона Z2)  ───▶  CAP_Z2_DATA  (S3 GPIO41)
//   GPIO27  I²S1 LRCK (зона Z2)  ───▶  CAP_Z2_LRCK  (S3 GPIO40)
//   GPIO25  I²S1 BCLK (зона Z2)  ───▶  CAP_Z2_BCK   (S3 GPIO42)
//   GND                          ───▶  GND
//
// Выводы выбраны так, чтобы не задеть служебные:
//   19/20 — нативный USB, 26–32 — флеш и PSRAM модуля, 43/44 — UART0,
//   45/46 — выводы начальной загрузки, 0 — кнопка загрузки,
//   33–37 — заняты на модулях с окталовым PSRAM.
//
// Номера в правой колонке таблицы выше — это выводы S3. Номер GPIO15 у Master
// (I²S0 LRCK) — это вывод Master, а не S3; при разводке их нельзя путать.
//
// Если разводка S3-платы другая, правятся только эти шесть строк.
//
// Каналы в файле: 0 — Z1 левый (НЧ-полоса), 1 — Z1 правый (ВЧ-полоса),
// 2 — Z2 левый, 3 — Z2 правый. Именно этот порядок ждёт ПК-скрипт.
#pragma once

#include "driver/gpio.h"

#define CAP_Z1_BCK  GPIO_NUM_16
#define CAP_Z1_LRCK GPIO_NUM_18
#define CAP_Z1_DATA GPIO_NUM_17
#define CAP_Z2_BCK  GPIO_NUM_42
#define CAP_Z2_LRCK GPIO_NUM_40
#define CAP_Z2_DATA GPIO_NUM_41