# ESP32 BiAmp Local v2 + 0.96" OLED + EC11 — Документация

Версия прошивки: 35.2-local + encoder (NVS v24)
Плата: ESP32-WROOM-32
Дата: 2026-10-29

## 1. Назначение
Модификация под связку 0.96\" OLED SSD1306 + EC11 с кнопками SW/KEY. Локальная, не для мерджа в основную ветку.

## 2. Схема подключения

### 2.1 I2S (усилители)
| Порт | BCK | LCK/WS | DIN | Назначение |
|---|---|---|---|---|
| Z1 | GPIO4 | GPIO15 | GPIO2 | Левый тракт |
| Z2 | GPIO13 | GPIO14 | GPIO12 | Правый тракт |

### 2.2 I2C (OLED + комбо-модуль)
| Пин | Подключение | Примечание |
|---|---|---|
| SDA | GPIO23 | Общая шина |
| SCL | GPIO22 | Общая шина |
| VCC | 3.3V | Питание |
| GND | GND | Общая земля |
Адрес SSD1306: 0x3C

### 2.3 EC11 + кнопки (модуль 0.96\" OLED+EC11)
| Пин модуля | ESP32 | Назначение |
|---|---|---|
| CLK | GPIO21 | Квадратурный выход A (TR_A) |
| DT  | GPIO19 | Квадратурный выход B (TR_B) |
| SW  | GPIO18 | Нажатие энкодера (PUSH) |
| KEY | GPIO5  | Отдельная кнопка на плате |
| VCC | 3.3V  | Питание |
| GND | GND   | Общая земля |

OLED на этом же модуле висит в общую I2C-шину (GPIO21/22).

## 3. Библиотеки
Требуются:
- Adafruit GFX Library
- Adafruit SSD1306
- BluetoothA2DPSink
- BluetoothSerial
- ESP32Encoder (madhephaestus/ESP32Encoder)
- AudioTools (pschatzmann/arduino-audio-tools)
- ESP32-A2DP (pschatzmann/ESP32-A2DP)
- Preferences (встроена в esp32 core)

## 4. Управление с энкодера
| Действие | Результат |
|---|---|
| Поворот | Регулировка общей громкости vol: (0-100%). При повороте на OLED показывается VOL XX% 1.5с |
| Короткий клик SW | Play/Pause |
| Длинный клик SW (>800мс) | Mute/Unmute обеих зон |
| Короткий клик KEY | Не используется |
| Длинный клик KEY (>500мс) | Включение/выключение сабвуфера (sub_on) |

OLED: после 1.5с бездействия показывает общий экран (BT, PLAY/PAUSE, VOL, XO, sub).

## 5. Кроссовер
- xo_type 1 -> BW12 (Butterworth 12дБ/окт), Q 0.7071
- xo_type 2 -> LR24 (Linkwitz-Riley 24дБ/окт), Q 0.7071
- tlf/thf диапазон -9..+6 дБ
- sub_on/sub_hz настраиваются отдельно

## 5.1 Лимитер
- Команды: limon:0/1, limthr:0.1-1.0, limratio:1-20, limatt:0.001-1.0, limrel:0.0001-1.0
- Параметры сохраняются в NVS (PBlob v24)

## 5.2 PEQ (параметрический эквалайзер)
- Команда: peq:CH:F:Q:DB (CH=0-3, F=20-20000 Гц, Q=0.1-10, DB=-12..+12)
- Применяется к секции 5 (L-полоса) с типом 6 (peaking EQ)

## 6. Файл прошивки для Arduino IDE
Файл: ESP32_BiAmp_Local_v2_Encoder_Arduino.ino
Поместить в папку ESP32_BiAmp_Local_v2_Encoder_Arduino вместе с biamp_globals.h (и остальными файлами при наличии).

Board: ESP32 Dev Module (или WROOM-32)
Upload Speed: 921600
Flash Frequency: 80MHz
Partition Scheme: Huge APP (3MB No OTA/1MB SPIFFS)

## 7. Диагностика
В консоли (115200): status, stats, heap, click, evlog
Критично: RingDrops 0, BadSamples 0, Underrun 0, Clips 0
