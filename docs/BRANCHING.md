# Разделение направлений и владение путями

Документ описывает, как прошивка и приложения развиваются параллельно, не
конфликтуя. Схема целиком локальная: ветки живут в этом репозитории, пуш не
требуется и не выполняется.

## Зачем это нужно

Раньше всё лежало в одной ветке `main` и в одном файле `.github/workflows/ci.yml`.
Из этого следовало три практических проблемы:

- правка прошивки и правка приложения в одном рабочем дереве мешали друг другу
  ещё до коммита — незакоммиченные изменения блокировали переключение ветки;
- `ci.yml` содержал три независимые задачи, поэтому любая правка прошивки и
  любая правка приложения меняли один и тот же файл;
- `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md` менялись с обеих
  сторон и служили гарантированным источником конфликтов при слиянии.

Разнесение по долгоживущим веткам проблему не решает: файлы остаются общими,
и конфликт просто переезжает из рабочего дерева в момент слияния. Поэтому
разделение построено на владении путями — одном владельце на файл.

## Три направления

| Направление | Ветка | Свои пути |
|---|---|---|
| Прошивка ESP32 | `firmware-dev` | `firmware/**`, `SECURITY.md`, `.github/workflows/ci-firmware.yml` |
| Приложение Android | `android-dev` | `android-app/**`, `.github/workflows/ci-android.yml` |
| Клиент для ПК | `pc-client-dev` | `pc-client/**`, `.github/workflows/ci-pc-client.yml` |

## Общие файлы

Их вправе править любое направление, но они — единственный источник
конфликтов, поэтому правьте их отдельным маленьким коммитом:

`README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE`,
`.gitignore`, `.gitattributes`, `.editorconfig`, `docs/**`, `.githooks/**`,
`.github/workflows/ci-hygiene.yml`, `.github/workflows/release.yml`.

Матрица объявлена в трёх местах и должна меняться во всех трёх сразу:

- `.githooks/pre-commit` — работает локально, без сети;
- `.github/CODEOWNERS` — заработает на GitHub после первого пуша;
- этот файл — объясняет причину.

## Активация локальной проверки

Один раз в каждом клоне:

```bash
git config core.hooksPath .githooks
```

После этого `git commit` из ветки `firmware/*` откажется принимать файлы
приложения. Обход — `git commit --no-verify` или `SKIP_PATH_OWNER=1 git commit`.
Пользуйтесь обходом осознанно: он ровно возвращает конфликт, который схема
задумана снять.

## Рабочий цикл

```bash
git checkout firmware-dev
git switch -c firmware/volume-ramp      # короткая ветка под задачу
# ... правки ...
git add firmware/                       # только свои пути
git commit -m "..."
git checkout firmware-dev && git merge --ff-only firmware/volume-ramp
git checkout main && git merge --no-ff firmware-dev
```

## Синхронизация направлений

Направления не должны расходиться надолго, иначе `main` придётся сливать в
столкновении:

```bash
git checkout firmware-dev
git rebase main          # подтянуть чужие изменения, конфликт = чужой файл
git checkout main && git merge --no-ff firmware-dev
```

Ожидаемо, что `git rebase main` в ветке прошивки даст конфликт в
`README.md`, если приложение меняло его же. Это единственное место, где
разрешение вручную — и именно поэтому общие файлы стоит править отдельным
коммитом.

## Чего схема не решает

Файлы разведены физически, а связь между ними — смысловая. Протокол
описан трижды: в `firmware/ESP32_BiAmp/ESP32_BiAmp.ino`, в
`android-app/app/src/main/java/com/kilo/biampcontrol/bt/Protocol.kt` и в
`pc-client/biamp/protocol.py`. Новую команду в протоколе нужно заводить во
всех трёх местах — контроль владения путями этого не проверит. Проверяют
тесты: `Protocol.kt` — `StatusParserTest`, `protocol.py` — `test_protocol.py`.