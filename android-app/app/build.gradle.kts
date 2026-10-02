plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Подпись релиза берётся из свойств Gradle. В CI их передаёт release.yml
// из секретов репозитория через ORG_GRADLE_PROJECT_*; локально их можно
// положить в ~/.gradle/gradle.properties или android-app/local.properties.
// Имена без точек — обязательны: имя переменной окружения не может
// содержать точку, а Gradle переводит ORG_GRADLE_PROJECT_a.b в a_b.
// Если свойств нет, сборка релиза остаётся неподписанной: это осознанно,
// ключ никогда не лежит в коде.
val signStoreFile = providers.gradleProperty("biampStoreFile").orNull
val signStorePass = providers.gradleProperty("biampStorePassword").orNull
val signKeyAlias = providers.gradleProperty("biampKeyAlias").orNull
val signKeyPass = providers.gradleProperty("biampKeyPassword").orNull
val hasSigning = listOf(signStoreFile, signStorePass, signKeyAlias, signKeyPass)
    .all { !it.isNullOrBlank() }

/**
 * Проверки lint, падающие на Kotlin 2 (K2) в связке с lint 31.7.3 из AGP 8.7.3.
 *
 * Каждая из них разбирает тело композабла через Kotlin Analysis API K1,
 * тогда как проект собирается на K2. Падение — `IncompatibleClassChangeError`
 * («Found class KaSimpleVariableAccessCall, but interface was expected»),
 * и оно роняет lintAnalyzeDebug целиком, то есть вместе со ВСЕЙ остальной
 * статикой.
 *
 * Список не выдуман: каждый идентификатор добавлен после того, как lint сам
 * назвал падающий детектор («The crash seems to involve the detector …»).
 * Проверки, которые на этом коде не падают, в списке НЕ стоят и работают
 * штатно — в том числе UnrememberedState, UnrememberedMutableState и
 * AutoboxingStateCreation: именно они ловят потерянное состояние в
 * композаблах, а до правок состояние слайдеров дублировалось трижды.
 */
val K2_CRASHING_LINT_IDS = listOf(
    "AutoboxingStateCreation",
    "RememberInComposition"      // getterOrSuperDeclarationsHaveAnnotation
)

android {
    namespace = "com.kilo.biampcontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kilo.biampcontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Раннер для instrumented-тестов: без него androidTest не запустится.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = signStorePass
                keyAlias = signKeyAlias
                keyPassword = signKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    lint {
        // Детекторы androidx.lifecycle (NullSafeMutableLiveData,
        // FrequentlyChangingValue) разобраны против интерфейсов Kotlin
        // Analysis API K1 и падают так же, как перечисленные выше.
        disable += "NullSafeMutableLiveData"
        disable += "FrequentlyChangingValue"
        disable += K2_CRASHING_LINT_IDS
    }
}

/**
 * JDK для компиляции Kotlin.
 *
 * Раньше ставился только `kotlinOptions.jvmTarget = "17"`: это задавало
 * байткод, но не инструмент компиляции, и Kotlin/JVM-часть собиралась тем
 * же JDK, на котором запущен Gradle. Расхождение выдавало себя странными
 * ошибками в других модулях. `jvmToolchain(17)` задаёт и то, и другое, и
 * `compileOptions` выше остаётся, потому что отвечает за Java-часть.
 */
kotlin { jvmToolchain(17) }

dependencies {
    // BOM 2025.09.00 — последняя версия compose-bom, приносящая Material3 1.4.0 (стабильный)
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Юнит-тесты для чистой логики: парсер status (bt/Protocol.kt), очередь
    // команд с троттлингом (bt/CommandSender.kt) и DSP-математика графика
    // (ui/FilterGraph.kt). Всё проверяется без устройства и без Android-рантайма.
    testImplementation("junit:junit:4.13.2")
    // Виртуальное время для тестов очереди команд: окно троттлинга в 150 мс
    // иначе проверялось бы по скорости машины.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // ── Instrumented-тесты (androidTest) ──────────────────────────
    // Их смысл — проверить то, чего не видит юнит-тест: поведение ViewModel
    // и композаблов на настоящем Android-рантайме с реальными SharedPreferences,
    // с реальными потоками и реальным тактильным вводом. Сопряжённый ESP32
    // для них не нужен: соединение подменяется заглушкой SppClient.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    // Compose-тесты на реальном устройстве: ищут узлы по подписям и жмут их.
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Тестовые зависимости попадают и в отладочный APK — без activity
    // из ui-test-manifest не запускается пустая ComponentActivity.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}