# Отчёт и план: нативный GUI для BiAmp Control

**Дата:** 27.09.2026
**Объект:** `android-app` (Android, Jetpack Compose, `com.kilo.biampcontrol`)
**Устройство прошивки:** `firmware/ESP32_BiAmp/ESP32_BiAmp.ino` v34

> Документ создан по результатам исследования. Изменения в код не вносились.
> Номера строк сверены с текущим состоянием репозитория.

---

## 1. Обзор фреймворков

### 1.1 Нативные

| Фреймворк | Статус на 2026 | Примечание |
|---|---|---|
| **Jetpack Compose + Material 3** | Основной путь | Google объявил Material Android «all-in» на Compose. Material Views 1.14.0 — **последняя** стабильная версия Views-библиотеки |
| Material 3 stable | **1.4.0** (09.09.2026) | текущая рекомендуемая версия |
| Material 3 1.5.0-alpha28 | Альфа | приносит стабилизацию **M3 Expressive** |
| Compose Material 3 Adaptive | 1.1.0-beta1+ | `ListDetailPaneScaffold`, `SupportingPaneScaffold`, `NavigationSuiteScaffold` |
| `material3-adaptive-navigation-suite` | 1.5.0-alpha27 | адаптивная навигация |

**Material 3 Expressive** — не «Material 4», а расширение M3 на основе 46 исследований
с 18 000+ участников: 14 обновлённых компонентов, система motion-пружин, библиотека
из 35 форм, расширенная типографика. Compose 1.5.0 делает эти API стабильными.

### 1.2 Кроссплатформенные

| Фреймворк | Когда выбирать | Зрелость в 2026 |
|---|---|---|
| **Kotlin Multiplatform (KMP)** | Нативный UI + общая бизнес-логика | Стабилен с Kotlin 1.9.20, официальная поддержка Google |
| **Compose Multiplatform (CMP)** | Общий UI на Kotlin | Стабилен iOS/Android/desktop с **1.8.0 (май 2025)**; web — Beta |
| Flutter 3.44 | Один UI, максимум контроля над пикселями, web+desktop | Стабилен, но **не рендерит нативные виджеты** — «немного чужой» вид |
| React Native 0.84 | Команда с React/TS | New Architecture обязазательна с 0.82; Hermes V1 по умолчанию с 0.84 |
| Нативный Swift/Kotlin | Аппаратная интеграция, новейшие API, максимум производительности | Без компромиссов |

**Критично для этого случая:** `react-native-bluetooth-classic` существует, но
**iOS не поддерживает Bluetooth Classic** — SPP там недоступен в принципе.
Стек SPP + ESP32 = Android-only по определению, кроссплатформа здесь бессмысленна.

---

## 2. Источники и примеры кода

### 2.1 Официальная документация

| Тема | Ссылка |
|---|---|
| Material 3 в Compose | `https://developer.android.com/develop/ui/compose/designsystems/material3` |
| Material 3 Expressive: старт | `https://m3.material.io/blog/building-with-m3-expressive` |
| Material Android all-in on Compose | `https://m3.material.io/blog/material-is-compose-first` |
| Release-календарь material3 | `https://developer.android.com/jetpack/androidx/releases/compose-material3` |
| Window size classes | `https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes` |
| Canonical layouts | `https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts` |
| Список-деталь + навигация | `https://developer.android.com/develop/adaptive-apps/guides/list-detail` |
| Адаптивная навигация | `https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation` |
| Codelab по адаптивности | `https://codelabs.developers.google.com/jetpack-compose-adaptability` |
| API-умолчания доступности | `https://developer.android.com/develop/ui/compose/accessibility/api-defaults` |
| Семантика | `https://developer.android.com/develop/ui/compose/accessibility/semantics` |
| Тестирование доступности | `https://developer.android.com/develop/ui/compose/accessibility/testing` |
| Размер цели касания | `https://m3.material.io/components/buttons/guidelines` |
| Codelab по доступности | `https://developer.android.com/codelabs/jetpack-compose-accessibility` |
| KMP vs RN | `https://kotlinlang.org/docs/multiplatform/kotlin-multiplatform-react-native.html` |

### 2.2 Open-source репозитории

**Прямо профильный класс — управление Bluetooth-акустикой на ESP32:**

| Репозиторий | Стек | Почему смотреть |
|---|---|---|
| `sprlightning/BDK-AUDIO-APP` | KMP + Compose | **Ближайший аналог.** ESP32 BT-динамик, DSP/EQ ±12 дБ, выбор кодека, OTA, живой бейдж кодека, BLE-протокол на 3 характеристики |
| `jorgenkraghjakobsen/merus_bt` | C/ESP-IDF | Схема A2DP + SPP для усилителя MA12070P, управление каналами и кроссовером через SPP |
| `adaptableCoder/ESP-Connect` | React Native | SPP-терминал для ESP32, разбор разрешений. **Показывает, почему RN здесь не подходит** |
| `nazmos-sakib/Android-Esp32-BluetoothChat` | Compose + Hilt | Эталон MVVM/Clean Architecture с SPP к ESP32 |
| `MagicBulletPro/bluetooth-control-android` | Views + RecyclerView | Классическая архитектура, полезна для сравнения «до/после» |

**Референсы по UI аудиоустройств:**

| Репозиторий | Что взять |
|---|---|
| `atsushieno/compose-audio-controls` | `ImageStripKnob`, `DiatonicKeyboard` — автор разбирает, почему для аудио-параметров на мобильном нужен **круглый регулятор**, а не слайдер, и как делать тонкую настройку по долгому нажатию |
| `TUSHAR91316/Void-Player` | KMP + CMP, MVVM на `StateFlow`, архитектура `commonMain/androidMain` |
| `POL0i/DuWave` | Compose + Media3, горизонтальный EQ с пресетами, MVVM + Clean Architecture |
| `LoggingNewMemory/Inaho` | Compose, AMOLED-тема, EQ на 7 пресетов, анимированный mini-player |
| `rajendra7169/blazify` | 10-полосный EQ с **живой кривой АЧХ над слайдерами** и преамп для компенсации буста |
| `cvs-health/android-compose-accessibility-techniques` | Готовые рецепты `semantics`/`contentDescription`/`liveRegion` для `Slider` по WCAG 1.3.1, 2.5.3, 2.1.1, 4.1.2 |

### 2.3 Ключевые фрагменты

**Адаптивная навигация вместо хардкода:**

```kotlin
val adaptiveInfo = currentWindowAdaptiveInfo()
val layoutType = with(adaptiveInfo) {
    if (windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND))
        NavigationSuiteType.NavigationRail
    else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
}
NavigationSuiteScaffold(navigationSuiteItems = { /* ... */ }, layoutType = layoutType) {
    content()
}
```

**Доступный слайдер (WCAG):**

```kotlin
CompactSlider(
    value = pos,
    onValueChange = { pos = it },
    modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = "Громкость, левая зона"    // 2.5.3 Label in Name
        stateDescription = "${pos.roundToInt()} процентов" // 4.1.2 Name, Role, Value
    }
)
```

**Автоматическая проверка доступности — только инструментированными тестами.**

Проверки `tryPerformAccessibilityChecks()` выполняются **не** под Robolectric.
Функция берёт валидатор из `PlatformTestContext.composeAccessibilityValidator`,
а платформа Robolectric его всегда возвращает `null` — тест тихо ничего не
проверяет и проходит. Метода `enableAccessibilityChecks()` в API Compose
Test нет, так что включить проверку в unit-тестах нельзя.

Корректный путь — `app/src/androidTest/` на реальном устройстве или эмуляторе:

```kotlin
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sliderHasNoAccessibilityIssues() {
        rule.setContent { LabeledSlider(/* … */) {} }
        rule.onRoot().tryPerformAccessibilityChecks().assertExists()
    }
}
```

Пока такой тест не написан, автоматическая гарантия доступности
**отсутствует**. Семантика, добавленная вручную в интерфейсе
(`contentDescription`, `stateDescription`, `semanticsMerge`), ничем
не проверяется и требует ревью или ручной проверки TalkBack.

---

## 3. Рекомендации UX/UI

### 3.1 Найденные дефекты

| № | Проблема | Где | Серьёзность |
|---|---|---|---|
| 1 | **Цель касания 28 dp** — вдвое ниже минимума 48 dp | `ui/CompactSlider.kt:57` (`modifier.height(28.dp)`) | Высокая |
| 2 | **Ноль `contentDescription` и `semantics`** на всех слайдерах | весь пакет `ui/` | Высокая |
| 3 | **Нет адаптивности** — `NavigationBar` зашит намертво | `MainActivity.kt` | Средняя |
| 4 | **Линейная шкала частот 20…20000 Гц** | `ui/ChannelFilters.kt:72` (`valueRange = 20f..20000f`) | Высокая |
| 5 | **Только тёмная тема, нет Material You** | `ui/theme/Theme.kt` | Средняя |
| 6 | **Линейный ползунок громкости** | `ui/VolumeTab.kt` | Высокая |
| 7 | Нет тактильной отдачи | — | Низкая |
| 8 | Диагностика спрятана в текстовый блок | `ui/ServiceTab.kt` | Низкая |

**Дефект 1 — самый серьёзный.** Google: *«Any on-screen element that someone can click,
touch, or interact with must be large enough for reliable interaction… set the minimum
size to 48dp»*. Material-компоненты выставляют 48 dp **внутренне, но только когда могут
получать действия пользователя** — принудительное `height(28.dp)` это ломает.
Подтверждается эмпирически: при автоматической проверке свайпом по ползунку задержки
значение еле сдвинулось (204 из 220 при попытке дотянуть до конца) — тонкая вертикальная
полоса плохо ловит палец.

**Дефект 4 — не менее серьёзен.** *«Presenting the user to a linear frequency selection
is pretty much always wrong. Equalizers, filters, FFT displays etc. should all be
logarithmic so that each octave has the same graphical size»*. Сейчас 20 Гц и 20 кГц
занимают одинаковое расстояние на ползунке — половина дорожки достаётся диапазону
20–2000 Гц, где для 4-полосного кроссовера ничего не происходит.

**Дефект 6 требует согласованной правки прошивки.** *«Linear volume sliders are poor
because the range from OFF to medium is squeezed together in a very small area…
logarithmic is even worse»*. Рекомендуемая формула: `Amplitude = SliderPosition³ × 3.162278`.
**Но** прошивка делает `vol_z = v / 100.0f` — линейную амплитуду. Значит кубическая
разметка обязана появиться **и в `ESP32_BiAmp.ino`**, иначе интерфейс будет врать.
Правка только в UI — ошибка.

### 3.2 Что взять из источников

**Цели касания — визуально тонко, физически просторно:**

```kotlin
// Вместо modifier.height(28.dp) на самом Slider
Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
    Slider(..., modifier = Modifier.height(28.dp), track = { /* тонкий трек */ })
}
```
Material3 1.4 даёт слот `track`, поэтому дорожку можно оставить 28 dp, а зону нажатия
довести до 48 dp. Плюс **8 dp** зазора между соседними ползунками.

**Тонкая настройка долгим нажатием.** Из `compose-audio-controls`: удержание 1000 мс
переключает чувствительность на 1/4. Критично для `chhp`/`chlp` с диапазоном в три
порядка и для задержки в 220 отсчётов.

**Логарифмическая шкала частот:**

```kotlin
// 20…20000 Гц, равное деление на октаву
val f = 20.0 * 1000.0.pow(t)          // t ∈ [0,1]
val t = log(f / 20.0) / log(1000.0)
```

**Живая кривая АЧХ.** `blazify` рисует частотную характеристику прямо над слайдерами
EQ — резко повышает интуитивность, пользователь видит результат до того, как услышит.

**Индикатор здоровья тракта.** Прошивка уже отдаёт всё нужное: `RingDrops`,
`BadSamples`, `Underrun`, `Clips`, `Starve`, `HeapMin`. Компактная полоса в шапке
вкладки «Сервис» покажет состояние без открытия терминала.

**Material You.** `dynamicDarkColorScheme(context)` на Android 12+ — тема приложения
станет частью системы, отдельная палитра в `Theme.kt` останется запасным вариантом.

---

## 4. План интеграции

### Фаза 0 — Фундамент (1–2 дня, риск: нулевой)

| # | Действие | Файл |
|---|---|---|
| 0.1 | Поднять `compose-bom` до версии с material3 1.4.0 | `app/build.gradle.kts` |
| 0.2 | `dynamicDarkColorScheme` + светлая тема + `isSystemInDarkTheme()` | `ui/theme/Theme.kt` |
| 0.3 | `enableOnBackInvokedCallback="true"` в манифесте | `AndroidManifest.xml` |

**Проверка:** `./gradlew.bat assembleDebug`, запуск на устройстве, сравнение тем
в светлой/тёмной.

### Фаза 1 — Доступность (2–3 дня, риск: низкий)

| # | Действие | Файл |
|---|---|---|
| 1.1 | Обёртка `CompactSlider`: трек 28 dp, зона касания 48 dp, зазор 8 dp | `ui/CompactSlider.kt` |
| 1.2 | `mergeDescendants` + `contentDescription` + `stateDescription` на `LabeledSlider` | `ui/LabeledSlider.kt` |
| 1.3 | То же на `EqRow`, `DelayRow`, `ChFilterRow` | `ui/DspTab.kt`, `ui/ChannelFilters.kt` |
| 1.4 | `contentDescription` на элементы транспорта | `ui/VolumeTab.kt` |
| 1.5 | ~~Подключить `ui-test-junit4-accessibility`, прогнать `tryPerformAccessibilityChecks()`~~ **Не выполнено:** проверки не работают под Robolectric (валидатор всегда `null`), тест проходил, ничего не проверяя. Перенесено в `app/src/androidTest/` как инструментированный тест — см. раздел 2 | — |

**Проверка:** TalkBack на устройстве (сейчас — единственный рабочий способ).
**Ожидаемый эффект:** пункты 1.1–1.4 осмысленно озвучиваются TalkBack.
Автоматической проверки нет: пункт 1.5 не выполнен, см. раздел 2.

### Фаза 2 — Адаптивность (2–3 дня, риск: низкий)

| # | Действие | Файл |
|---|---|---|
| 2.1 | Зависимости `material3.adaptive:adaptive` / `adaptive-layout` / `adaptive-navigation` | `app/build.gradle.kts` |
| 2.2 | `NavigationSuiteScaffold` вместо `Scaffold` + `NavigationBar` | `MainActivity.kt` |
| 2.3 | `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)` | `MainActivity.kt` |
| 2.4 | `ListDetailPaneScaffold` на вкладке «Сервис»: список разделов слева, содержимое справа | `ui/ServiceTab.kt` |
| 2.5 | `BackHandler` + `AnimatedPane` | `ui/ServiceTab.kt` |

**Проверка:** поворот экрана, свободное окно, складка — контент перестраивается,
состояние не теряется.

### Фаза 3 — Аудио-UX (3–4 дня, риск: средний)

| # | Действие | Файл |
|---|---|---|
| 3.1 | Логарифмическая шкала `chhp`/`chlp` (20…20000 Гц) | `ui/ChannelFilters.kt` |
| 3.2 | Тонкая настройка долгим нажатием для `chhp`/`chlp`/`delay` | `ui/CompactSlider.kt` |
| 3.3 | Тактильная отдача `LocalHapticFeedback` на смену канала и деления | `ui/DspTab.kt` |
| 3.4 | Полоса здоровья тракта из `stats` | `ui/ServiceTab.kt` + `BiAmpViewModel.kt` |
| 3.5 | Кривая АЧХ над EQ | новая `ui/EqCurve.kt` |

**Координация с прошивкой.** `chhp`/`chlp` принимают 0…20000 Гц напрямую, менять
протокол не нужно: логарифмическая разметка — чистое преобразование в UI. Кубическая
громкость (**дефект 6**) требует правки `vol_z` в `ESP32_BiAmp.ino` и синхронной смены
в `StatusParser` — делать только после согласования.

### Фаза 4 — Полировка (2–3 дня, риск: низкий)

| # | Действие |
|---|---|
| 4.1 | M3 Expressive: motion-пружины, 14 обновлённых компонентов, расширенная типографика |
| 4.2 | `AnimatedContent` на смену вкладки, `animateFloatAsState` на значения |
| 4.3 | Пульсирующая подсказка на пустой вкладке при `DISCONNECTED` |
| 4.4 | Screenshot-тесты экранов в `app/src/test/` |

### Сводка по фазам

| Фаза | Объём | Риск | Эффект |
|---|---|---|---|
| 0. Фундамент | 1–2 дня | нулевой | Нативная тема, свежий Material |
| 1. Доступность | 2–3 дня | низкий | Управление одной рукой, TalkBack |
| 2. Адаптивность | 2–3 дня | низкий | Планшет, складка, ландшафт |
| 3. Аудио-UX | 3–4 дня | средний | Работа с частотами и тонкая настройка |
| 4. Полировка | 2–3 дня | низкий | Ощущение премиума |
| **Итого** | **10–15 дней** | | |

### Критический путь

Фазы 1 и 2 независимы и могут идти параллельно. **Фаза 3.1 — самая ценная** для
этого домена: линейная шкала частот — единственный дефект, который делает DSP-вкладку
фактически непригодной для точной настройки кроссовера. Остальное — эстетика и охват
устройств.

---

## Рекомендация

Начать с **фазы 1**. Восемь найденных дефектов — два из них (28 dp и логарифмические
частоты) реально мешают пользоваться приложением, а не просто выглядят несовременно.
