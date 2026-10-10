# AGENTS.md — BiAmp Control (Android, Compose)

См. полный ТЗ: `SPECIFICATION.md`. Ниже — только репозиторно-специфичные факты, которых нет в коде.

## Среда (Windows + VSCode, без Android Studio)
- **JDK 17** (Temurin). AGP 8.x требует именно 17.
- **AGP 8.7.3**, **Kotlin 2.0.21**, **Gradle 8.11.1** (версии — в `build.gradle.kts` и `gradle/wrapper/gradle-wrapper.properties`).
- **Compose BOM 2025.09.00** приносит **Material3 1.3.2**, не 1.4.0. Материал 1.4.0
  лежит в BOM 2026.09.00, но требует смены AGP и Kotlin: lint из AGP 8.7.3 разобран
  против Kotlin Analysis API K1 и роняет сбор на K2. Список падающих детекторов —
  `K2_CRASHING_LINT_IDS` в `app/build.gradle.kts`.
- Android SDK: только `cmdline-tools`. Путь: `C:\Android\Sdk\cmdline-tools\latest\bin` — внутренняя папка `latest` **обязательна**.
- Установка пакетов: `.\sdkmanager.bat "platform-tools" "platforms;android-35" "build-tools;35.0.0"`.
- `ANDROID_HOME=C:\Android\Sdk`, в `PATH` добавить `%ANDROID_HOME%\platform-tools`.
- В корне `local.properties`: `sdk.dir=C:\\Android\\Sdk`.
- Расширения VSCode: `Extension Pack for Java`, `Kotlin` (mathiasfrohlich), `Gradle for Java`, `XML`.
- Wrapper генерируется вручную: `gradle wrapper --gradle-version 8.9` (создаст `gradlew.bat`).

## Сборка и запуск
- Сборка: `.\gradlew.bat assembleDebug` → APK: `app\build\outputs\apk\debug\app-debug.apk`.
- `namespace = "com.kilo.biampcontrol"`, `applicationId = "com.kilo.biampcontrol"`.
- Установка на Redmi (LineageOS, Android 15): `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- Рекомендуется **wireless ADB**: `adb pair <ip>:<port>` → `adb connect <ip>:<port>`. Драйверы для LineageOS обычно не нужны; если `adb devices` пуст — `sdkmanager.bat "extras;google;usb_driver"`.
- `adb logcat` — основной инструмент отладки (в VSCode нет Android-лог-фильтра).

## Архитектура (один модуль `app`)
```
Compose UI (StateFlow)
        ↕
BiAmpViewModel ← status-парсер ← LineReader (Flow<String>, корутина)
        ↓
CommandSender (очередь + троттлинг) → SppManager (RFCOMM)
```
- Одна Activity, три вкладки via Compose `NavigationBar`: «Громкость», «DSP», «Сервис».
- `ConnState`: `DISCONNECTED → CONNECTING → CONNECTED → RECONNECTING`.
- Сокет — только на `Dispatchers.IO`.

## Протокол (источник истины — прошивка `firmware/ESP32_BiAmp/ESP32_BiAmp.ino`, версия v35)
- SPP/RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`, устройство `ESP32 BiAmp Speaker`.
- Команды — строки нижнего регистра, терминатор `\n` (принимает и `\r`), макс. 63 символа + терминатор.
- Большинство команд без ответа. С ответом: `status`, `stats`, `heap`, `help`, `evlog` (только USB), `save`, `reboot`, `factory`, `preset:N`, `test:*`, `tvol:N`.
- **Команды `meta` в прошивке НЕТ.** Раньше кнопка «Meta» отправляла её и молча получала отказ; заменена на `help`.
- `status` — **11 строк**, парсить регэкспрами, устойчиво к пробелам; нераспознанные строки пропускать без падения. Формат — см. `docs\DOCUMENTATION.md`, раздел 4.4.
- Строка `Delay: n/n/n/n` замыкает блок `status`; по ней `StatusParser.isBlockEnd()` определяет конец блока.
- **Лимиты обязаны совпадать с `safeCmdVal()` в прошивке.** В частности `MAX_DELAY_SAMPLES = 220` при `DELAY_BUF_SIZE = 256`: в UI стоит тот же потолок, иначе ползунок покажет применённое значение, которого на устройстве нет.

## Ключевые правила разработки
- **Троттлинг ползунков:** не чаще 1 отправки / 150 мс; при отпускании — финальное значение всегда. В `pending` держится по одному значению на префикс: новое вытесняет старое даже при потерянном ответе — иначе опрос встал бы навсегда.
- При обрыве сокета — очистить очередь, показать снекбар «Соединение потеряно».
- Ответ > 1 с — не блокировать отправку, опрос статуса пропустить.
- Мусор в ответе — в лог «Сервис», не падать.
- Автореконнект: 5 попыток (2/4/8/15/30 с), затем ручной режим.
- Без соединения все органы управления заблокированы, кроме кнопки подключения.
- После `CONNECTED` — отправить `status`, заполнить ползунки **без обратной отправки** (флаг `isSyncing`). `requestStatusSync()` — единственная точка, ставящая флаг; снимает его разбор ответа либо `syncWatchdog()` по таймауту 2 с.
- Автоопрос `status` раз в 3 с, но **не чаще ответа**: при `isSyncing == true` итерация опроса пропускается, иначе в буфер попадут строки двух разных запросов.
- Буфер разбора строк очищается по строке `Delay:` и при подключении — иначе UI покажет строки прежней сессии.
- `save`, `reboot`, `factory` — только через поддерживающий диалог.

## Запреты
- ❌ Не менять протокол и прошивку.
- ❌ Только классический BT (SPP). **BLE не использовать.**
- ❌ Никаких зависимостей, кроме AndroidX / Compose BOM / Coroutines.
- ❌ Не слать команды без троттлинга.
- ❌ Не требовать подтверждений от устройства на каждую команду.
- ❌ Не требовать для сборки Android Studio — только Gradle CLI + VSCode.

## Тестирование
- Целевое устройство — реальный Redmi Note 9 Pro (miatoll), Android 15, API 35. Эмулятор не требуется.
- Проверка критериев приёмки: ТЗ п.10 (10 пунктов, от сборки до тёмной темы).

## Проверка перед коммитом
- `.\gradlew.bat assembleDebug` — сборка без ошибок.
- `adb logcat` — проверка логов при подключении/отключении.