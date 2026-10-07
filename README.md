<div align="center">

# ESP32 Bi-Amp

**Двухполосный би-амп на ESP32 с управлением по Bluetooth**

Прошивка + Android-приложение + клиент для ПК для самостоятельной
сборки усилителя с раздельными низкочастотными и высокочастотными
каналами.

[![Лицензия](https://img.shields.io/badge/лицензия-GPLv3-blue.svg)](LICENSE)
[![Прошивка](https://img.shields.io/badge/прошивка-v35-ff5c00.svg)](firmware/DOCUMENTATION.md)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg)](android-app/)
[![CI](https://img.shields.io/badge/CI-passing-brightgreen.svg)](.github/workflows/ci-hygiene.yml)

</div>

---

## Что это

ESP32 принимает аудиопоток по **Bluetooth Classic A2DP** и раздаёт его
на **два независимых I2S-порта** — левый (НЧ) и правый (ВЧ). Между
каналами настраивается кроссовер, задержка для временнóй развёртки,
поканальные фильтры и перестановка Л/П. Управление идёт по
**Bluetooth SPP** — отдельным каналом, текстовыми командами.

Телефон при этом не переключается на профиль наушников: одно устройство
принимает аудио и принимает команды одновременно.

```
┌──────────┐   A2DP (аудио)    ┌──────────────┐  I2S  ┌──────────┐
│  Телефон │ ─────────────────▶│              │──────▶│  НЧ   Л  │▶ динамики
│          │   SPP (команды)   │   ESP32      │  I2S  │  ВЧ   Л  │▶ динамики
└──────────┘ ◀─────────────────│              │──────▶│  НЧ   П  │▶ динамики
                  статусы       └──────────────┘  I2S  └──────────┘▶ динамики
```

### Возможности

| Область | Возможности |
|---|---|
| Тракт | 2 × I2S, 44.1 кГц, 16 бит; ресемплинг 48 → 44.1 кГц |
| Кроссовер | Частота 200–1000 Гц, Butterworth или Linkwitz-Riley 4-го порядка |
| Сабсоник | Фильтр ВЧ 20–80 Гц с независимым включением |
| Тримы | НЧ и ВЧ, −6…+3 дБ |
| Эквалайзер | Три полки: 120 Гц, 1 кГц, 6 кГц, ±12 дБ |
| Поканальные фильтры | ВЧ и НЧ для каждого из 4 каналов, 0–20000 Гц |
| Задержка | 0–220 сэмплов (до 4.99 мс) на каждый канал — развёртка по времени |
| Перестановка Л/П | `swap:` — замена инверсии фазы (удалена в v35) |
| Дублирование зоны | `dup:` — каналы 2,3 молчат, зона 2 дублирует зону 1 |
| Мьют | Абсолютная установка `mute:z:0\|1` и совместимый переключатель `mute:z` |
| Пресеты | Flat, Voice, Night, Party |
| Транспорт | play / pause / next / prev |
| Тест-сигналы | Все, НЧ, ВЧ, по каналам, антифаза, свип; частота 20–20000 Гц; громкость 0–6 % |
| Хранение | NVS, автосохранение через 2 с; заводской сброс |
| Диагностика | Счётчики кадров, потерь, порчи, андерранов, клиппинга, тайминги |
| Дисплей | OLED SSD1306 128×64, I²C, опционален |
| Приложение | Android 8.0+, Jetpack Compose, Material 3 |
| Клиент ПК | Python 3.10+, CLI и веб-панель, USB-CDC и SPP |

---

## Архитектура

### Тракт

```
A2DP (44.1/48 кГц) → ресемплинг 48→44.1 → кольцевой буфер 4096 кадров
  → DSP (кроссовер, EQ, тримы, фильтры, задержка) → soft clip (tanh)
  → 2 × I2S (зона 1 левая, зона 2 правая), по 2 канала в каждой
```

- **Два ядра.** Аудиозадача на ядре 0 (приоритет выше), `loop()` на
  ядре 1. Кольцевой буфер — линейный массив с маской, копирование
  через физический конец запрещено (см. `AGENTS.md` §4).
- **Самотест кольца** при загрузке: `Ring selftest: PASS frames=…
  bad=0 drops=0`. `bad > 0` — порча данных, публикация запрещена.
- **Протокол** — текстовые строки, терминатор `\n`, максимум 63
  символа. Источник истины — `protocol/status-contract.md`
  (контракт v35.1), один на все три реализации.

### Компоненты

| Компонент | Стек | Роль |
|---|---|---|
| `firmware/ESP32_BiAmp/` | Arduino C++ (ESP32-WROOM-32) | Аудиотракт, DSP, CLI, NVS |
| `android-app/` | Kotlin, Jetpack Compose, Material 3 | Управление по SPP |
| `pc-client/` | Python 3.10+, pyserial, stdlib HTTP | CLI и веб-панель, USB-CDC и SPP |
| `firmware/capture/` | ESP32-S3, ESP-IDF | Сниффер цифрового выхода Master |
| `firmware/tools/capture-analyze.py` | Python, numpy | Анализатор захватов, АЧХ, THD, задержки |
| `firmware/tests/` | host-тесты (gcc) | Математика DSP без платы |

### Распределение по направлениям

Репозиторий разделён на четыре направления по владению путями —
один владелец на файл. Подробности: `docs/BRANCHING.md`.

| Направление | Ветка | Свои пути |
|---|---|---|
| Прошивка ESP32 | `firmware-dev` | `firmware/**` (кроме `capture/` и `tools/capture-analyze.py`), `SECURITY.md`, `ci-firmware.yml` |
| Приложение Android | `android-dev` | `android-app/**`, `ci-android.yml` |
| Веб-панель и клиент ПК | `pc-client-dev` | `pc-client/**`, `ci-pc-client.yml` |
| Проверки и измерения | `verify-dev` | `firmware/capture/**`, `firmware/tools/capture-analyze.py`, `ci-verify.yml` |

Общие файлы (`README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `docs/**`,
`.githooks/**`, `tools/**`) правит любое направление — отдельным
маленьким коммитом.

---

## Содержание

```
esp32-biamp/
├── firmware/                    # Прошивка ESP32
│   ├── ESP32_BiAmp/
│   │   └── ESP32_BiAmp.ino      # Скетч, ~1500 строк
│   ├── capture/                 # Сниффер ESP32-S3 (направление verify)
│   ├── tests/                   # Host-тесты математики DSP
│   ├── tools/                   # Скрипты сборки и прошивки (PowerShell)
│   └── DOCUMENTATION.md         # Полная техническая документация
├── protocol/                    # Контракт протокола v35.1 — источник истины
│   └── status-contract.md       # Общий для всех реализаций
├── android-app/                 # Android-приложение
│   ├── app/src/main/java/       # Kotlin, Jetpack Compose
│   ├── app/src/test/            # Юнит-тесты
│   ├── app/src/androidTest/     # Инструментированные тесты
│   └── docs/DOCUMENTATION.md    # Техническая документация приложения
├── pc-client/                   # Клиент для ПК
│   ├── biamp/                   # protocol, transport, client, cli, webapp
│   └── tests/                   # ~80 тестов, железа не требуют
├── .github/workflows/           # CI: firmware, android, pc-client, verify, hygiene, release
├── .githooks/                   # pre-commit: проверка владения путями
├── tools/check-owners.ps1       # Сверка матрицы владения
├── docs/                        # BRANCHING.md, TESTING.md, MERGE-CHECKLIST.md
├── LICENSE, NOTICE              # GPL-3.0 и лицензии составных частей
└── CONTRIBUTING.md
```

---

## Железо

### Обязательное

| Компонент | Примечание |
|---|---|
| ESP32-WROOM-32 | classic, не S3 и не C3 — нужен Bluetooth Classic |
| Два I2S-усилителя | любые с I2S-входом (PCM5102, TAS5805, MAX98357A) |
| OLED SSD1306 128×64 | опционально, I²C |
| Источник питания | 5 В, стабильный ток ≥ 2 А |

### Распиновка

I2S разделён на два порта — левый и правый каналы:

| Сигнал | Пин | | Сигнал | Пин |
|---|---|---|---|---|
| Зона 1 BCLK | 4 | | Зона 2 BCLK | 26 |
| Зона 1 LRCLK | 15 | | Зона 2 LRCLK | 25 |
| Зона 1 DIN | 2 | | Зона 2 DIN | 27 |
| OLED SDA | 21 | | OLED SCL | 22 |

Адрес OLED — `0x3C`. Задаётся константой `OLED_ADDR` в скетче.

### Схема подключения

```
ESP32                Зона 1 (НЧ/ВЧ, левая)     Зона 2 (НЧ/ВЧ, правая)
  GPIO 4  BCLK ──────▶ I2S IN                   ┌─▶ I2S IN
  GPIO 15 LRCLK ──────▶ I2S LRCLK                │   I2S LRCLK
  GPIO 2  DIN  ──────▶ I2S DIN                   │   I2S DIN
                                                 └─▶ (GPIO 26 / 25 / 27)

  GPIO 21 SDA  ──────▶ OLED SDA
  GPIO 22 SCL  ──────▶ OLED SCL
```

Каждый усилитель получает смешанный сигнал и сам разделяет его на НЧ
и ВЧ через собственный кроссовер. Управление фильтрами идёт через SPP.

---

## Установка

### 1. Прошивка ESP32

Требуется [`arduino-cli`](https://arduino.github.io/arduino-cli/latest/installation/).

```bash
arduino-cli core update-index
arduino-cli core install esp32:esp32
arduino-cli lib install "Adafruit GFX Library" "Adafruit SSD1306"
```

Две библиотеки — `audio-tools` и `ESP32-A2DP` — **отсутствуют в
официальном реестре Arduino** и публикуются только на GitHub, поэтому
ставятся вручную в каталог `libraries` вашего sketchbook:

```bash
git clone --depth 1 https://github.com/pschatzmann/arduino-audio-tools.git \
  "$(arduino-cli config get directories.user)/libraries/audio-tools"
git clone --depth 1 https://github.com/pschatzmann/ESP32-A2DP.git \
  "$(arduino-cli config get directories.user)/libraries/ESP32-A2DP"
```

> Ключ `sketchbook.enable_git_url_install` в `arduino-cli` 1.5 по
> умолчанию выключен, поэтому флаг `lib install --git-url` работает
> не всегда. Вариант с `git clone` надёжнее.

Дальше — PowerShell-скрипты из `firmware/tools/`:

```powershell
# Собрать
pwsh -File .\firmware\tools\build.ps1

# Прошить (укажите свой COM-порт)
pwsh -File .\firmware\tools\flash.ps1 -Port COM5

# Наблюдать вывод и слать команды
pwsh -File .\firmware\tools\monitor.ps1 -Port COM5 -Commands "status" -Seconds 10
```

`pwsh` — это PowerShell 7; подойдёт и встроенный Windows PowerShell 5.1
(`powershell -File ...`), скрипты сохранены с BOM ради этого.

<details>
<summary>Сборка из командной строки arduino-cli напрямую</summary>

```bash
arduino-cli compile \
  --fqbn "esp32:esp32:esp32:PartitionScheme=huge_app,UploadSpeed=921600" \
  firmware/ESP32_BiAmp

arduino-cli upload -p COM5 \
  --fqbn "esp32:esp32:esp32:PartitionScheme=huge_app,UploadSpeed=921600" \
  firmware/ESP32_BiAmp
```
</details>

> **Внимание при первой прошивке.** `PartitionScheme=huge_app` даёт 3 МБ
> под приложение вместо 1.2 МБ. Если плата раньше прошивалась с другой
> схемой разделов, первая прошивка должна идти с полным стиранием флеша:
> `flash.ps1 -Port COM5 -Erase`.

При загрузке в мониторе должно появиться:

```
boot: bi-amp v35 (lr swap instead of phase inversion)
Ring selftest: PASS frames=10752/10752 bad=0 drops=0
A2DP: started
SPP: OK
```

Самотест кольцевого буфера обязателен: `bad=0` означает, что аудиотракт
не повреждает данные. Значение `bad>0` — повреждение памяти при
копировании, см. [раздел 8.5 документации прошивки](firmware/DOCUMENTATION.md).

### 2. Сборка приложения

Требуется JDK 17 и Android SDK (platform 35, build-tools 35.0.0).

Создайте `android-app/local.properties`:

```properties
sdk.dir=C:\\Android\\Sdk
```

Затем:

```bash
cd android-app
./gradlew assembleDebug        # Linux / macOS
.\gradlew.bat assembleDebug   # Windows
```

APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`

Установка на устройство:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

### 3. Клиент для ПК

Требуется Python 3.10 или новее (проверено на CPython 3.10–3.14).

```powershell
cd pc-client
.\setup.ps1          # создаёт .venv, ставит pyserial, прогоняет тесты
.\run.ps1 -Command ports
.\run.ps1 -Command status
```

Веб-панель:

```powershell
.\run.ps1 -Command web -Port COM14     # открыть http://127.0.0.1:8765
```

### 4. Подключение

1. Спарьте телефон с `ESP32 BiAmp Speaker` в системных настройках
   Bluetooth (или спарите Windows для SPP-клиента).
2. Запустите приложение, выдайте разрешения.
3. Нажмите **Подкл.** и выберите устройство.
4. Индикатор в шапке станет `ON`.

---

## Протокол управления

Команда — строка в нижнем регистре, терминатор `\n`, максимум 63
символа. Большинство команд не имеет ответа; параметры проверяются на
устройстве, значение вне диапазона игнорируется молча.

**Источник истины — `protocol/status-contract.md`.** Три
реализации (прошивка, Android, PC-клиент) обязаны совпадать с ним.

| Команда | Диапазон | Действие |
|---|---|---|
| `vol:N` | 0–100 | Громкость обеих зон |
| `v0:N` / `v1:N` | 0–100 | Громкость зоны |
| `bal:N` | −10…10 | Баланс |
| `mute:z:0\|1` | 0–1 | Абсолютная установка мьюта зоны (v35.1) |
| `mute:z` | — | Переключатель мьюта (совместимость) |
| `fc:N` | 200–1000 | Частота кроссовера, Гц |
| `hp:N` | 20–80 | Сабсоник, Гц |
| `sub:0\|1` | — | Сабсоник вкл/выкл |
| `xo:0\|1` | — | Кроссовер вкл/выкл целиком |
| `xotype:N` | 1–2 | 1 = Butterworth, 2 = LR4 |
| `tlf:N` / `thf:N` | −6…3 | Трим НЧ / ВЧ, дБ |
| `eql:N` `eqm:N` `eqh:N` | −12…12 | Полки EQ, дБ |
| `swap:0\|1` | — | Перестановка Л/П (заменила `inv:` в v35) |
| `dup:0\|1` | — | Дублирование зоны 2 зоной 1 (не сохраняется в NVS) |
| `chhp:C:F` | 20–20000 | ВЧ-фильтр канала C, Гц |
| `chlp:C:F` | 20–20000 | НЧ-фильтр канала C, Гц |
| `delayC:N` | 0–220 | Задержка канала C, сэмплы |
| `tvol:N` | **0–6** | Громкость тест-сигнала, % (`TEST_VOL_MAX = 0.06`) |
| `tf:N` | 20–20000 | Частота тест-сигнала, Гц |
| `preset:N` | 0–3 | Flat, Voice, Night, Party |
| `test:*` | — | `all l r woof tweet 1-4 anti sweep off` |
| `play pause next prev` | — | Транспорт |
| `status stats heap help` | — | Диагностика |
| `save reboot:ok factory:ok` | — | Обслуживание (`reboot`/`factory` по SPP требуют `:ok`) |

> **Удалено в v35:** команда `inv:` (инверсия фазы). Отправляющий её
> клиент молча теряет команду — устройство не отвечает ошибкой.

Ответ на `status` — **13 строк**, порядок фиксирован, конец блока —
строка `Delay:`:

```
V0=40% V1=40% bal=0.00
Fc=400Hz hp=45Hz sub=ON
XO: Butter ON
TLF=0.00dB THF=-1.00dB
EQ: L=0.00 M=0.00 H=0.00
Mute: 0/0
SWP: 0
DUP: 0
BT: ON | SPP: OFF
Src: 44.1 kHz
Test: 0 TVol=4%
CHF: 0/0 0/0 0/0 0/0
Delay: 0/0/0/0
```

Полное описание — [раздел 4.4 документации приложения](android-app/docs/DOCUMENTATION.md)
и [контракт v35.1](protocol/status-contract.md).

### Диагностика треска

Треск в динамиках почти всегда означает потерю или порчу кадров. Команда
`stats` покажет, где именно:

```
Frames: 123456
Blocks: 1000 AF: 123456 Idle: 5000
Underrun: Z1=0 Z2=0
RingDrops: 0 SelfTestErr: 0
Clips: 0/0/0/0
Starve: n=0 max=0ms
DSP: max=2304us WR: max=8373us
Loop: max=1ms NVS: max=0ms
HeapMin: 15860
```

| Симптом | Значение |
|---|---|
| `RingDrops` растёт | Задача вывода не успевает — кольцо переполняется |
| `SelfTestErr` ненулевое | Ошибки стартового самотеста кольца — повреждение памяти |
| `Underrun` растёт | Не хватает данных к моменту DMA |
| `Clips` ненулевые | Перегруз по мощности — убавьте громкость |
| `Starve` ненулевой | Провалы между пакетами A2DP длиннее 80 мс |

Норма: `RingDrops: 0`, `SelfTestErr: 0`, `Underrun Z1=0 Z2=0`.
`Underrun` и `Clips` сбрасываются каждые 5 с housekeeping-циклом.

---

## Параллельная работа

Проект разрабатывается четырьмя направлениями параллельно. Полные
правила — `docs/BRANCHING.md`, чек-листы слияния —
`docs/MERGE-CHECKLIST.md`, порядок проверок — `docs/TESTING.md`.

### Быстрый старт

```bash
git config core.hooksPath .githooks     # однократно: включить хук владения

# Дерево на направление — правки не мешают переключению веток
git worktree add ../firmwaredev-work firmware-dev
git worktree add ../androiddev-work  android-dev
git worktree add ../webdev-work      pc-client-dev
git worktree add ../verifydev-work   verify-dev
```

### Рабочий цикл

```bash
git checkout firmware-dev
git switch -c firmware/volume-ramp      # короткая ветка под задачу
# ... правки только своих путей ...
git add firmware/
git commit -m "Исправить переполнение кольцевого буфера"
git checkout firmware-dev && git merge --ff-only firmware/volume-ramp
git checkout main && git merge --ff-only firmware-dev
```

### Железо — узкое место

Замеры АЧХ, задержки и клиппинга требуют единственного ESP32, усилителей
и колонок. **Два прогона одновременно невозможны.** Порядок всегда такой:

1. замерить эталон на текущей прошивке;
2. прошить новую версию;
3. перемерить и сравнить с эталоном.

Громкость при замерах — не выше 6 % (`AGENTS.md` §4).

---

## Документация

| Документ | Содержание |
|---|---|
| [firmware/DOCUMENTATION.md](firmware/DOCUMENTATION.md) | Архитектура тракта, все команды, бюджет времени, история версий, диагностика |
| [protocol/status-contract.md](protocol/status-contract.md) | Контракт протокола v35.1 — источник истины для всех реализаций |
| [android-app/docs/DOCUMENTATION.md](android-app/docs/DOCUMENTATION.md) | Классы, протокол, сценарии, troubleshooting |
| [pc-client/README.md](pc-client/README.md) | CLI, веб-панель, защита панели, профили |
| [docs/BRANCHING.md](docs/BRANCHING.md) | Стратегия ветвления, владение путями, синхронизация направлений |
| [docs/TESTING.md](docs/TESTING.md) | Команды проверки перед коммитом по направлениям |
| [docs/MERGE-CHECKLIST.md](docs/MERGE-CHECKLIST.md) | Чек-листы безопасного слияния |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Как участвовать в проекте |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Правила поведения |
| [SECURITY.md](SECURITY.md) | Как сообщить об уязвимости |

---

## Сбор из VS Code

Откройте папку `firmware` — в палитре команд появятся задачи:

- **Сборка прошивки** — `Ctrl+Shift+B`
- **Прошить (спросить порт)**
- **Мониторинг (спросить порт)**

Для `android-app` откройте папку `android-app` и запустите сборку
терминалом — Android Gradle Plugin подхватывает настройки сам.

---

## Ограничения

- **Только Android.** Bluetooth Classic не поддерживается iOS.
- **Один клиент SPP.** Второй телефон не подключится.
- **ESP32 classic.** S3, C3 и C6 используют Bluetooth Low Energy —
  прошивка на них не заработает.
- **Нет OTA-обновления.** Прошивка обновляется только по USB.
- **Стоковое давление на динамики.** Тест-сигнал `test:sweep` и режим
  `test:anti` на высокой громкости способны повредить динамики. Заводская
  громкость теста — 6 %, потолок `tvol:` — 6 %.

---

## Лицензия

GPL-3.0-or-later. См. [LICENSE](LICENSE) и [NOTICE](NOTICE).

Прошивка использует сторонние библиотеки под собственными лицензиями:

| Библиотека | Источник | Лицензия |
|---|---|---|
| [arduino-audio-tools](https://github.com/pschatzmann/arduino-audio-tools) | GitHub, не в реестре Arduino | GPL-3.0 |
| [ESP32-A2DP](https://github.com/pschatzmann/ESP32-A2DP) | GitHub, не в реестре Arduino | Apache-2.0 |
| [Adafruit GFX Library](https://github.com/adafruit/Adafruit-GFX-Library) | реестр Arduino | BSD-3-Clause |
| [Adafruit SSD1306](https://github.com/adafruit/Adafruit_SSD1306) | реестр Arduino | BSD-3-Clause |
| Arduino ESP32 core (`BluetoothSerial`, `Preferences`, `Wire`) | реестр Arduino | LGPL-2.1 |

---

## Участие

Приветствуются баги, идеи и pull request. Прочитайте
[CONTRIBUTING.md](CONTRIBUTING.md) и [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Открытые вопросы, которые всегда в работе — оформлены как задачи:

| Задача | Тема |
|---|---|
| [#1](https://github.com/ZDarow/esp32-biamp/issues/1) | Фаза 0: Фундамент — Material 3 1.4.0, нативная тема |
| [#2](https://github.com/ZDarow/esp32-biamp/issues/2) | Фаза 1: Доступность — цель касания 48 dp, семантика TalkBack |
| [#3](https://github.com/ZDarow/esp32-biamp/issues/3) | Фаза 2: Адаптивность — планшет, складка, ландшафт |
| [#4](https://github.com/ZDarow/esp32-biamp/issues/4) | Фаза 3: Аудио-UX — логарифмические частоты, АЧХ |
| [#5](https://github.com/ZDarow/esp32-biamp/issues/5) | Фаза 4: Полировка — M3 Expressive, анимации |
| [#6](https://github.com/ZDarow/esp32-biamp/issues/6) | Кубическая шкала громкости — **требует правки прошивки** |

Полный отчёт с обоснованием, ссылками на документацию и оценками сроков:
[`android-app/UI-PLAN.md`](android-app/UI-PLAN.md).

> Кубическая громкость не может быть сделана только в интерфейсе:
> прошивка применяет линейное преобразование `vol_z = v / 100.0f`.
> Без её правки интерфейс будет врать.
