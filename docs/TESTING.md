# Проверка перед коммитом

Документ собирает команды, которые нужно выполнить перед тем, как считать
изменение готовым. Каждая секция соответствует направлению — см.
`docs/BRANCHING.md`.

Общее правило: проверка проходит, если команда завершилась с кодом 0. Красный
lint или красные тесты означают, что выгрузка артефактов небезопасна.

## Прошивка ESP32

Сборка без устройства — то же, что делает CI:

```powershell
pwsh -File .\firmware\tools\build.ps1
```

`pwsh` в командах ниже — это PowerShell 7. Подойдёт и встроенный Windows
PowerShell 5.1: скрипты сохранены в UTF-8 с BOM именно поэтому — без BOM
оболочка 5.1 читает кириллицу как мусор и не может разобрать файл.

Эквивалент вручную (те же параметры, что и в `Invoke-Compile`):

```powershell
arduino-cli compile `
  --fqbn "esp32:esp32:esp32:PartitionScheme=huge_app,UploadSpeed=921600" `
  --build-path .\.build `
  --warnings default `
  .\firmware\ESP32_BiAmp
```

Локальный скрипт компилирует с `--warnings default`, а CI — с `--warnings all`
(`ci-firmware.yml`). Расхождение намеренное: в CI вывод нужен полностью, чтобы
замечание о предупреждении не потерялось в логе, локально достаточно дефолта.

Прошивка и загрузка последовательного лога:

```powershell
pwsh -File .\firmware\tools\flash.ps1 -Port COM14
pwsh -File .\firmware\tools\monitor.ps1 -Port COM14 -Seconds 10
```

Порт по умолчанию берётся из переменной окружения `BT_ESP_PORT`, её также
можно задать флагом `-Port`.

`flash.ps1 -Erase` стирает всю флеш-память, включая раздел параметров.
Используйте его только когда нужно сбросить настройки, — вместе с ними
исчезнут все значения DSP.

## Приложение Android

Требуется JDK 17 и `ANDROID_HOME` в переменных окружения.

```powershell
cd .\android-app
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Быстрее одной командой (те же задачи, общий прогон):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Отчёт о lint после прогона: `app/build/reports/lint-results-debug.html`,
результаты тестов — `app/build/test-results/testDebugUnitTest/`.

Установка на устройство:

```powershell
adb install -r .\android-app\app\build\outputs\apk\debug\app-debug.apk
```

## Клиент для ПК

Тесты используют стандартный `unittest`, pytest не нужен:

```powershell
cd .\pc-client
pip install -r requirements.txt
python -m unittest discover -s tests -t . -v
```

## Контроль владения путями

```powershell
git config core.hooksPath .githooks   # однократно в клоне
git commit -m "..."                    # хук отклонит чужие пути
```

## Проверка на устройстве

Нужна физическая проверка: сборка и тесты не ловят всё. Подробные критерии
здоровья системы — `firmware/DOCUMENTATION.md` § 6.5, порядок загрузки —
§ 7, неисправности и FAQ — § 7.1–7.4.

### Что должно быть в логе при старте

```
boot: bi-amp v34 (dsp reset, fade out, click probe)
NVS: blob loaded (v22)
Ring selftest: PASS
A2DP: started
SPP: OK
```

Обязательные счётчики после 30 с внутреннего теста — все нули, кроме `Idle`:

```
RingDrops: 0
BadSamples: 0
Underrun: Z1=0 Z2=0
Clips: 0/0/0/0
```

`BadSamples` ненулевое означает порчу кадров в кольце, `RingDrops` — потери,
`Underrun` — недокорм I2S. Ни одно из этих значений не должно ненулевым.

### Диагностические команды

```powershell
pwsh -File .\firmware\tools\monitor.ps1 -Port COM14 -Commands "status,stats" -Seconds 5
```

Блок `status` — 11 строк, замыкается строкой `Delay: n/n/n/n`. Если блок не
замкнулся, парсер приложения ждёт следующего опроса и не обновит экран.

### Проверка границ потока

Щелчок на старте и остановке измеряется отдельно — обычные счётчики его не
видят. Требуется включённая музыка: при `Frames: 0` обе величины нулевые
всегда, и замер ничего не доказывает.

```
click
```

Контрольный режим измерителя собирается с отключённым сбросом состояния DSP
(`CLICK_PROBE_SELFTEST`) и обязан дать ненулевой результат на заведомо
неисправленном варианте. Методика — `firmware/DOCUMENTATION.md` § 8.6.

## Что не проверяется автоматически

- `androidTest` в проекте нет: проверок транспорта, ViewModel и Compose на
  устройстве не существует, покрытие ограничено юнит-тестами.
- Поведение A2DP с конкретным телефоном: составленный формат, задержки и
  разрывы потока различаются между устройствами.
- Поведение при обрыве NVS-раздела.