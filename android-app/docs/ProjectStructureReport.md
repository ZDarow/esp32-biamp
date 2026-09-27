# Отчёт: Best Practices и Архитектурные Паттерны для Структурирования Проекта

**Дата:** 2026-09-24  
**Контекст:** Android-проект `android-app` (Kotlin + Jetpack Compose, Gradle 8.9, AGP 8.7.3)

---

## 1. Рекомендуемая Иерархия Файлов и Папок

### 1.1 Корневая структура (многомодульный Gradle-проект)

```
android-app/
├── build.gradle.kts                    # Корневой build — только pluginManagement
├── settings.gradle.kts                 # Включение модулей, dependencyResolutionManagement
├── gradle/
│   ├── libs.versions.toml              # Version Catalog (централизованные зависимости)
│   └── └── ...
├── build-logic/                        # Convention plugins (опционально, для масштабирования)
│   ├── settings.gradle.kts
│   └── convention/
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           ├── AndroidApplicationConventionPlugin.kt
│           └── AndroidLibraryConventionPlugin.kt
├── app/                                # Application module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/kilo/biampcontrol/
│       │   ├── BiAmpViewModel.kt       # Shared ViewModel
│       │   ├── MainActivity.kt         # Single Activity
│       │   ├── bt/                     # Bluetooth-слой
│       │   │   ├── Protocol.kt         # Парсинг status, DeviceState data class
│       │   │   ├── SppManager.kt       # RFCOMM соединение
│       │   │   ├── CommandSender.kt    # Очередь + троттлинг
│       │   │   └── ConnState.kt        # Состояние подключения
│       │   └── ui/                     # Compose UI
│       │       ├── DspTab.kt           # Вкладка DSP
│       │       ├── VolumeTab.kt        # Вкладка Громкость
│       │       ├── ServiceTab.kt       # Вкладка Сервис
│       │       ├── LabeledSlider.kt    # Переиспользуемый компонент
│       │       ├── CompactSlider.kt    # Переиспользуемый компонент
│       │       ├── ChannelFilters.kt   # Компонент фильтров
│       │       └── ...
│       └── res/
├── core/                               # Shared library module
│   ├── build.gradle.kts
│   └── src/main/java/com/kilo/biampcontrol/core/
│       ├── domain/                     # Чистая логика (без Android-зависимостей)
│       │   ├── model/                  # Data-классы (DeviceState и т.д.)
│       │   └── usecase/                # Use Cases / Interactors
│       ├── data/                       # Repository-реализации
│       │   ├── repository/
│       │   └── datasource/             # Bluetooth, SharedPreferences, и т.д.
│       └── di/                         # Dependency Injection модули
├── feature/                            # Feature modules (опционально, при росте)
│   ├── build.gradle.kts
│   └── src/main/
│       └── ...
├── docs/                               # Документация
│   ├── SPECIFICATION.md
│   ├── Architecture.md
│   └── CHANGELOG.md
├── scripts/                            # Скрипты сборки, деплоя
│   ├── sdkmanager.bat
│   └── deploy.sh
├── .github/                            # CI/CD
│   └── workflows/
│       └── android-ci-cd.yml
├── .gitignore
├── .editorconfig
├── Makefile
├── README.md
└── LICENSE
```

### 1.2 Обоснование выбора структуры

| Принцип | Реализация | Почему |
|---------|-----------|--------|
| **Feature-based > Layer-based** | `ui/`, `bt/`, `core/` как feature-директории | Быстрее навигация, PR меньше, модульность |
| **src/ layout** | `app/src/main/java/...` | Стандарт Android, предотвращает случайные импорты |
| **Monorepo с модулями** | `app/`, `core/`, `feature/` как Gradle модули | Параллельная сборка, cache, изоляция |
| **Single Activity** | `MainActivity.kt` с NavigationBar | Compose Navigation, один Entry Point |
| **Clean Architecture** | `core/domain/` → `core/data/` → `app/` | Зависимости только внутрь, тестируемость |
| **Version Catalog** | `gradle/libs.versions.toml` | Единая точка управления версиями |

### 1.3 Текущая структура vs Целевая

**Текущая (монолитный `app`):**
```
app/src/main/java/com/kilo/biampcontrol/
├── BiAmpViewModel.kt
├── MainActivity.kt
├── bt/
│   ├── Protocol.kt
│   ├── SppManager.kt
│   ├── CommandSender.kt
│   └── ConnState.kt
└── ui/
    ├── DspTab.kt
    ├── VolumeTab.kt
    ├── ServiceTab.kt
    ├── LabeledSlider.kt
    ├── CompactSlider.kt
    ├── ChannelFilters.kt
    └── ...
```

**Целевая (при росте — вынос `core/`):**
```
app/                          # UI + Entry Point (зависит от core)
core/                         # domain + data (без Android-фреймворков)
  ├── domain/                 # DeviceState, Use Cases
  └── data/                   # BluetoothRepository, StatusParser
```

---

## 2. Обоснование Выбора Паттерна

### 2.1 MVVM + Feature-First (рекомендация Flutter-команды Google)

**Источник:** [dev.to/techwithsam — Clean Architecture in Flutter 2026](https://dev.to/techwithsam/clean-architecture-in-flutter-2026-practical-implementation-guide-1dfb), [Android Developers — Modularization Patterns](https://developer.android.com/topic/modularization/patterns)

- **ViewModel** (`BiAmpViewModel`) — состояние + бизнес-логика
- **StateFlow** — реактивное состояние для UI
- **Feature-First** — группировка по экранам/фичам (`DspTab`, `VolumeTab`, `ServiceTab`)
- **Clean Architecture** — `core/domain/` изолирован от `app/`

**Почему не MVC:** ViewModel отделяет состояние от UI, тестируемость без Android-фреймворка.

**Почему не MVI:** Для текущего масштаба (один Activity, 3 вкладки) MVVM проще и достаточен.

### 2.2 Dependency Inversion (SOLID — D)

- `BiAmpViewModel` зависит от `CommandSender` (интерфейс), а не от `SppManager` (конкретная реализация)
- `Protocol.kt` парсит `status` через регэкспы, не завися от формата прошивки
- `DeviceState` — data class, чистый Kotlin, без Android-зависимостей

### 2.3 Single Responsibility (SOLID — S)

- `SppManager` — только RFCOMM соединение
- `CommandSender` — только очередь + троттлинг
- `StatusParser` — только парсинг строки `status`
- `BiAmpViewModel` — только управление состоянием

### 2.4 Open/Closed (SOLID — O)

- `Protocol.kt` — расширяемый через добавление новых регэкспов
- `LabeledSlider` / `CompactSlider` — переиспользуемые composable без модификации

### 2.5 Interface Segregation (SOLID — I)

- `CommandSender` — минимальный интерфейс: `send(command, throttle)`
- `BiAmpViewModel` — публичный API: `setFc()`, `setHp()`, `setTlf()` и т.д.

### 2.6 Dependency Inversion (SOLID — D)

- `core/domain/` не зависит от `app/` или `androidx`
- `DeviceState` — чистый data class
- Use Cases принимают репозитории через конструктор

---

## 3. Примеры Конфигов

### 3.1 `gradle/libs.versions.toml` — Version Catalog

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
gradle = "8.9"
composeBom = "2025.12.00"
coroutines = "1.8.1"
lifecycle = "2.8.7"
navigation = "2.8.4"

[libraries]
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-animation = { module = "androidx.compose.animation:animation" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

### 3.2 `settings.gradle.kts`

```kotlin
pluginManagement {
    includeBuild("build-logic")  // Convention plugins
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("gradle/libs.versions.toml")) }
    }
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BiAmpControl"
include(":app")
include(":core")  // При модульности
```

### 3.3 `build.gradle.kts` (корневой)

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

### 3.4 `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kilo.biampcontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kilo.biampcontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.findVersion("composeCompiler")?.get() ?: "1.5.8" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core"))  // При модульности
}
```

### 3.5 `Makefile`

```makefile
.PHONY: help build clean install test lint run-asm

help:
	@echo "Available targets:"
	@echo "  build   - Assemble debug APK"
	@echo "  install - Install APK on connected device"
	@echo "  test    - Run unit tests"
	@echo "  lint    - Run ktlint"
	@echo "  clean   - Clean build outputs"
	@echo "  adb     - Show connected devices"

build:
	./gradlew.bat assembleDebug

install: build
	adb install -r app/build/outputs/apk/debug/app-debug.apk
	adb shell pm grant com.kilo.biampcontrol android.permission.BLUETOOTH_CONNECT
	adb shell pm grant com.kilo.biampcontrol android.permission.BLUETOOTH_SCAN
	adb shell pm grant com.kilo.biampcontrol android.permission.POST_NOTIFICATIONS
	adb shell pm grant com.kilo.biampcontrol android.permission.BLUETOOTH_ADVERTISE

test:
	./gradlew.bat testDebugUnitTest

lint:
	./gradlew.bat ktlintCheck

clean:
	./gradlew.bat clean

adb:
	adb devices
```

### 3.6 `.gitignore`

```gitignore
# Android Studio
.idea/
*.iml
.cxx/
.externalNativeBuild/
.captures/

# Gradle
.gradle/
build/
!gradle/libs.versions.toml

# Local configuration
local.properties
*.jks
*.keystore

# Output
*.apk
*.aab
output-metadata.json

# OS
*.log
.DS_Store
Thumbs.db

# CI
.github/workflows/android-ci-cd.yml  # Если содержит секреты
!github/workflows/android-ci-cd.yml

# Debug
**/build/reports/
**/build/tmp/
```

### 3.7 `.editorconfig`

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.kt]
indent_size = 4
indent_style = space

[*.{gradle,kts}]
indent_size = 4
indent_style = space
```

---

## 4. Чек-лист Готовности к Масштабированию и CI/CD

### 4.1 Структура и Модуляризация

- [ ] `gradle/libs.versions.toml` создан и содержит все зависимости
- [ ] `settings.gradle.kts` настроен с `dependencyResolutionManagement`
- [ ] Корневой `build.gradle.kts` содержит только `pluginManagement`
- [ ] `app/` — единственный application module
- [ ] `core/` — library module с domain/data слоями (при росте)
- [ ] `build-logic/` — convention plugins (при модульности > 5 модулей)
- [ ] Все модули используют Kotlin DSL (`build.gradle.kts`)

### 4.2 Качество Кода

- [ ] `ktlint` настроен в `build.gradle.kts`
- [ ] `detekt` настроен для статического анализа
- [ ] `kotlin-compiler` strict mode включён
- [ ] `@Composable` функции имеют ключи для всех `remember`/`LaunchedEffect`
- [ ] Все `StateFlow` коллекции используют `distinctUntilChanged()` или эквивалент
- [ ] `ProGuard` rules настроены для release-сборки

### 4.3 Тестирование

- [ ] Unit tests в `app/src/test/java/...`
- [ ] Instrumentation tests в `app/src/androidTest/java/...`
- [ ] `test-` module для shared test code (при модульности)
- [ ] `@Preview` composable для всех UI-компонентов
- [ ] `BiAmpViewModel` тестируется без Android-фреймворка (MockK/Mockito)
- [ ] `Protocol.kt` тестируется с фикстурами status-строк

### 4.4 CI/CD

- [ ] `.github/workflows/android-ci-cd.yml` создан
- [ ] `actions/setup-java@v4` с Temurin JDK 17
- [ ] `gradle/actions/setup-gradle@v5` для кэширования
- [ ] `cache: 'gradle'` включён
- [ ] `./gradlew assembleDebug` — сборка на каждый push
- [ ] `./gradlew testDebugUnitTest` — тесты на каждый push
- [ ] `./gradlew ktlintCheck` — линтинг
- [ ] `./gradlew lint` — Android lint
- [ ] Signing key в GitHub Secrets (`SIGNING_KEY`)
- [ ] Deploy в Firebase/Play Store через `r0adkll/upload-google-play`

### 4.5 Документация

- [ ] `README.md` с инструкцией по сборке и установке
- [ ] `SPECIFICATION.md` — спецификация
- [ ] `docs/Architecture.md` — описание архитектуры
- [ ] `docs/CHANGELOG.md` — история изменений
- [ ] `AGENTS.md` — репозиторно-специфичные инструкции для AI-агентов

### 4.6 Безопасность

- [ ] `local.properties` в `.gitignore`
- [ ] `*.jks` / `*.keystore` в `.gitignore`
- [ ] `google-services.json` в `.gitignore`
- [ ] Секреты (ключи, токены) только через `local.properties` или GitHub Secrets
- [ ] `BuildConfig.DEBUG` не используется для критической логики

### 4.7 Производительность

- [ ] `minifyEnabled true` для release
- [ ] `shrinkResources true` для release
- [ ] `composeOptions` с `kotlinCompilerExtensionVersion`
- [ ] `ProGuard` rules для Compose (`-keepattributes RuntimeVisibleAnnotations`)
- [ ] `adb logcat` фильтры для отладки Bluetooth

### 4.8 Деплой

- [ ] `adb install -r` работает на целевом устройстве (Redmi, LineageOS)
- [ ] Все разрешения (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS`) выдаются
- [ ] `wireless ADB` настроен (`adb pair` / `adb connect`)
- [ ] `sdkmanager.bat` доступен для установки пакетов
- [ ] `local.properties` содержит `sdk.dir=C:\\Android\\Sdk`

---

## Ссылки и Источники

1. [Android Developers — Modularization Patterns](https://developer.android.com/topic/modularization/patterns)
2. [Android Developers — Gradle Tips](https://developer.android.com/build/gradle-tips)
3. [Gradle Best Practices — Structuring Builds](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html)
4. [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
5. [Kotlin Documentation — Gradle Best Practices](https://kotlinlang.org/docs/gradle-best-practices.html)
6. [GitHub — github/gitignore (Android.gitignore)](https://github.com/github/gitignore/blob/main/Android.gitignore)
7. [Brandon's Clean Architecture Guide — Directory Structure](https://brandonjf.github.io/brandon-clean-architecture/directory-structure)
8. [EranBoudjnah/CleanArchitectureForAndroid (837★)](https://github.com/EranBoudjnah/CleanArchitectureForAndroid)
9. [ngallazzi/cleanarchitectureblueprints (104★)](https://github.com/ngallazzi/cleanarchitectureblueprints)
10. [MatthewKerns/software-development-best-practices-guide](https://github.com/MatthewKerns/software-development-best-practices-guide)
11. [Android Developers — Compose Architectural Layering](https://developer.android.com/develop/ui/compose/layering)
12. [KotlinConf 2026 — Gradle Talks](https://blog.gradle.org/gradle-at-kotlinconf-2026)
13. [Medium — Complete Guide: CI/CD for Android Apps with GitHub Actions](https://medium.com/@satyamkrjha85/complete-guide-setting-up-ci-cd-for-android-apps-with-github-actions-firebase-play-store-5b942208473a)
14. [KMP CI Tutorial — Kotlin Documentation](https://kotlinlang.org/docs/multiplatform/kmp-ci-tutorial.html)
