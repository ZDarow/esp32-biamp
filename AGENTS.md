# AGENTS.md — esp32-biamp

Инструкции для агентов кодирования, работающих в этом репозитории.

Прочитайте **целиком** перед первой правкой. Здесь собраны факты, которые
невозможно вывести из кода: почему так устроено и что сломается, если
поступить иначе.

---

## 1. Что это

Монорепозиторий двух жёстко связанных компонентов:

```
firmware/ESP32_BiAmp/    прошивка ESP32 (Arduino C++), версия v29
android-app/             приложение управления (Kotlin, Jetpack Compose)
```

Они соединены **односторонним SPP-протоколом**. Это определяет почти
все правила ниже.

| Файл | Роль |
|---|---|
| `firmware/ESP32_BiAmp/ESP32_BiAmp.ino` | **источник истины по протоколу** |
| `firmware/DOCUMENTATION.md` | документация прошивки |
| `android-app/docs/DOCUMENTATION.md` | документация приложения, раздел 4.4 — формат `status` |
| `android-app/AGENTS.md` | детальные инструкции **только** по Android-части |
| `android-app/UI-PLAN.md` | план развития интерфейса, фазы 0–4 |
| `android-app/SPECIFICATION.md` | ТЗ приложения |
| `CONTRIBUTING.md` | правила для живых участников |

> При расхождении с `android-app/AGENTS.md` — этот файл главнее по
> структуре и сборке, тот — подробнее по протоколу и UI.

---

## 2. Железное правило: прошивка — источник истины

**Приложение подстраивается под прошивку, никогда наоборот.**

Прежде чем изменить формат команды, парсер или лимит значения,
откройте `ESP32_BiAmp.ino` и сверьтесь. Команда, которой нет в
прошивке, будет **молча проигнорирована устройством** — без ошибки,
без ответа, без лога.

Это уже случалось: кнопка отправляла `meta`, которой в прошивке нет.
Пользователь нажимал и ничего не происходило.

### Диапазоны обязаны совпадать с `safeCmdVal()`

Лимиты в UI не должны выходить за пределы, которые проверяет прошивка.
Расхождение даёт ползунок, показывающий значение, которого на
устройстве нет. Текущий пример: `MAX_DELAY_SAMPLES = 220` при
`DELAY_BUF_SIZE = 256`. Меняете потолок — правьте **оба** места.

### Что менять в паре

| Изменение | Обязательно синхронизировать с этим |
|---|---|
| Команда в UI | разбор ответа в `StatusParser`, `firmware/DOCUMENTATION.md` |
| Лимит значения | `safeCmdVal()` в прошивке + константа в UI |
| Формат строки `status` | `StatusParser` + `android-app/docs/DOCUMENTATION.md` §4.4 |
| Закон громкости | `vol_z` в прошивке **и** разбор в UI (см. задачу #6) |

---

## 3. Сборка

### Прошивка

```powershell
pwsh -File .\firmware\tools\build.ps1
```

`common.ps1` ищет `arduino-cli` в `$env:ARDUINO_CLI`, затем в типовых
каталогах, затем в `PATH`. Скрипты **не** содержат абсолютных путей.

Требуемые библиотеки:

```bash
# Из реестра Arduino
arduino-cli lib install "Adafruit GFX Library" "Adafruit SSD1306"

# Только с GitHub — в реестре Arduino их НЕТ
git clone --depth 1 https://github.com/pschatzmann/arduino-audio-tools.git \
  "$(arduino-cli config get directories.user)/libraries/audio-tools"
git clone --depth 1 https://github.com/pschatzmann/ESP32-A2DP.git \
  "$(arduino-cli config get directories.user)/libraries/ESP32-A2DP"
```

> ⚠️ Это самая частая причина «не собирается у меня». На машине
> разработчика библиотеки уже стоят, и проект собирается. На чистой —
> падает с `BluetoothA2DPSink.h: No such file or directory`.
> Флаг `arduino-cli lib install --git-url` в версии 1.5 **выключен
> по умолчанию** — используйте `git clone`.

### Приложение

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

Требуется JDK 17 (Temurin), Android SDK platform 35, build-tools 35.0.0.
Путь SDK задаётся в `android-app/local.properties` — файл **не**
коммитится.

APK: `android-app\app\build\outputs\apk\debug\app-debug.apk`

### Обязательно перед коммитом

```powershell
pwsh -File .\firmware\tools\build.ps1
cd android-app; .\gradlew.bat assembleDebug
```

Оба компонента обязаны собираться. Это делает и CI.

---

## 4. Проверка на устройстве

Сборка проходит — ещё не значит, что работает. Протокол проверяется
только на живом ESP32.

```powershell
pwsh -File .\firmware\tools\flash.ps1 -Port COM5
pwsh -File .\firmware\tools\monitor.ps1 -Port COM5 -Seconds 30
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Обязательные счётчики

Прошивка отдаёт `stats`. После **любого** изменения тракта:

```
Ring selftest: PASS frames=10752/10752 bad=0 drops=0
```

и в `stats`:

```
RingDrops: 0
BadSamples: 0
Underrun Z1=0 Z2=0
Clips 0/0/0/0
```

`bad > 0` — порча данных. **Такую сборку публиковать нельзя.**

Треск при нулевых `RingDrops` и `Underrun` означает переполнение
кольцевого буфера: он линейный массив, а не кольцо на указателях, и
любое копирование через физический конец портит соседние переменные.

Подробности истории v26–v29 — `firmware/DOCUMENTATION.md`.

---

## 5. Запреты

- ❌ **Менять протокол** без обновления документации в том же коммите
- ❌ **BLE вместо SPP.** iOS не поддерживает Bluetooth Classic, стек
  SPP + ESP32 = Android-only по определению
- ❌ **Команды, которых нет в прошивке**
- ❌ **Отправка команд без троттлинга** — 1 отправка / 150 мс
- ❌ **Коммитить** `local.properties`, логи, `*.pcapng`, build-кэши.
  В Bluetooth-захватах — адреса и данные реальных подключений
- ❌ **Добавлять зависимости** кроме AndroidX / Compose BOM / Coroutines
- ❌ **Абсолютные пути** в коде, скриптах и workflow
- ❌ **Коммитить без компиляции** обоих компонентов

---

## 6. Стиль

| | Отступ | Длина строки |
|---|---|---|
| Kotlin | 4 пробела | 120 |
| Скетч C++ | 2 пробела | 100 |

- Комментарии и документация — **на русском**
- Имена переменных и функций — английский (`camelCase` / `snake_case`)
- Коммиты — **на русском**, в повелительном наклонении:
  `Исправить переполнение кольцевого буфера при переносе`
- Один коммит — одно логическое изменение
- Ветки — `kebab-case` на английском: `fix/delay-limit`

Правила закреплены в `.editorconfig` и `.gitattributes`. CI проверяет
переводы строк: в исходниках не должно быть CRLF.

---

## 7. Задачи

План развития интерфейса — `android-app/UI-PLAN.md`, задачи в GitHub
[#1–#6](https://github.com/ZDarow/esp32-biamp/issues).

Перед началом работы проверьте, не заведена ли уже задача.

| # | Фаза | Суть |
|---|---|---|
| 1 | Фаза 0 | Material 3 1.4.0, нативная тема |
| 2 | Фаза 1 | Доступность: цель касания 48 dp, семантика TalkBack |
| 3 | Фаза 2 | Адаптивность: планшет, складка, ландшафт |
| 4 | Фаза 3 | Аудио-UX: логарифмические частоты, АЧХ |
| 5 | Фаза 4 | Полировка: M3 Expressive, анимации |
| 6 | — | Кубическая громкость — **требует правки прошивки** |

---

## 8. Частые ошибки

| Симптом | Причина |
|---|---|
| `BluetoothA2DPSink.h: No such file or directory` | не установлена `ESP32-A2DP` с GitHub |
| `Library 'AudioTools@latest' not found` | такого имени в реестре нет, есть `audio-tools`, и тоже только на GitHub |
| `./gradlew: Permission denied` | потерян бит исполнения: `git update-index --chmod=+x android-app/gradlew` |
| `configuration 'debugRuntimeClasspath' not found` | конфигурации нет в корневом проекте, уберите этот шаг |
| Треск, но `RingDrops: 0` | переполнение кольцевого буфера через физический конец |
| Ползунок показывает значение, которого нет на устройстве | лимит UI разошёлся с `safeCmdVal()` |
| Кнопка ничего не делает | команда отсутствует в прошивке |
| CI: «Не найден скомпилированный образ» | забыт `--build-path`: arduino-cli чистит временный каталог |
| `remote: Invalid username or token` | в глобальном git-конфиге есть<br>`url.https://x-access-token:@github.com/.insteadof`<br>— удалите правило, токен не при чём |

---

## 9. Проверка перед завершением

```powershell
pwsh -File .\firmware\tools\build.ps1
cd android-app
.\gradlew.bat assembleDebug
```

- [ ] Обе сборки проходят
- [ ] На устройстве счётчики `RingDrops`/`BadSamples`/`Underrun`/`Clips` = 0
- [ ] `Ring selftest: PASS`, `bad=0`
- [ ] Протокол изменился? → документация обновлена в том же коммите
- [ ] Нет `TODO`, заглушек, отладочного `print()`
- [ ] Коммит на русском, одно логическое изменение
