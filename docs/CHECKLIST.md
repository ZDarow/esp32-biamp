# Чек-лист перед коммитом

Проверяется автоматически через `.githooks/pre-commit`. Ручная проверка —
по каждому пункту.

## Сборка

- [ ] `firmware/ESP32_BiAmp/ESP32_BiAmp.ino` компилируется
      (`pwsh -File .\firmware\tools\build.ps1`)
- [ ] `android-app` собирается (`.\gradlew.bat assembleDebug`)
- [ ] `pc-client` проходит `ruff check`, `mypy` (если трогал Python)

## Тесты

- [ ] Юнит-тесты Android проходят (`.\gradlew.bat testDebugUnitTest`)
- [ ] Host-тесты DSP проходят (если трогал DSP)
- [ ] `LimitsContractTest` проходит — константы `Limits` соответствуют
      `firmware/protocol/status-contract.md`
- [ ] Нет пропущенных тестов, которые должны бы работать

## Протокол

- [ ] Изменён формат команды? → `StatusParser` и
      `android-app/docs/DOCUMENTATION.md` §4.4 обновлены в том же коммите.
- [ ] Изменён лимит значения? → `safeCmdVal()` в прошивке и константа в UI
      изменены вместе.
- [ ] Новая команда? → она есть в прошивке (иначе будет молча проигнорирована).
- [ ] Изменён `status`? → `firmware/protocol/status-contract.md` и
      `android-app/docs/DOCUMENTATION.md` обновлены.

## Код

- [ ] Нет `TODO`, `FIXME`, `print()`, заглушек.
- [ ] Нет абсолютных путей в коде, скриптах, workflow.
- [ ] Нет дублирующейся логики (парсер, лимиты, константы — в одном месте).
- [ ] `ruff check .` и `ruff format --check .` (или эквивалент) — без предупреждений.
- [ ] `mypy --strict` (для Python) — без ошибок.

## Документация

- [ ] `firmware/DOCUMENTATION.md` обновлена при изменении прошивки.
- [ ] `android-app/AGENTS.md` обновлён при изменении Android-сборки.
- [ ] `docs/CHANGELOG.md` — при релизе.
- [ ] Нет устаревших версий в документации (v34 → v35, и т.п.).

## Git

- [ ] Один коммит — одно логическое изменение.
- [ ] Коммит на русском, повелительное наклонение.
- [ ] Ветка — `kebab-case`.
- [ ] `.gitignore` не содержит лишнего, но содержит всё необходимое.
- [ ] Нет закоммиченных `.env`, `*.p12`, `*.jks`, `local.properties`.

## Устройство (только при проверке на железе)

- [ ] `Ring selftest: PASS`, `bad=0`
- [ ] `RingDrops: 0`, `BadSamples: 0`, `Underrun Z1=0 Z2=0`, `Clips 0/0/0/0`
- [ ] Громкость не выше 6%, тест-сигнал не включён выше 6%.