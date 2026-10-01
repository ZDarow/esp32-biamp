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

// Идентификаторы проверок пакета androidx.compose.runtime.lint.
//
// Все они разбирают тело композабла, а детекторы собраны против Kotlin
// Analysis API K1, тогда как проект собирается на K2 (Kotlin 2.0.21).
// На этой связке они падают с IncompatibleClassChangeError, и падение
// целиком роняет lintAnalyzeDebug. Список взят не «на глаз», а вытащен
// из lint.jar compose runtime 1.9.1 (по одному Detector-классу на проверку).
val COMPOSE_RUNTIME_LINT_IDS = setOf(
    "AutoboxingStateCreation",
    "AutoboxingStateValueProperty",
    "ComposableLambdaParameterNaming",
    "ComposableLambdaParameterPosition",
    "ComposableNaming",
    "CompositionLocalNaming",
    "CoroutineCreationDuringComposition",
    "FlowOperatorInvokedInComposition",
    "FrequentlyChangedStateReadInComposition",
    "MutableCollectionMutableState",
    "OpaqueUnitKey",
    "ProduceStateDoesNotAssignValue",
    "RememberInComposition",
    "RememberReturnType",
    "StateFlowValueCalledInComposition",
    "UnrememberedMutableState",
    "UnrememberedState",
    "UnrememberedStateCreation"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        // Детекторы androidx.lifecycle и androidx.compose слинкованы против
        // интерфейсов Kotlin Analysis API K1, а проект собирается на K2
        // (Kotlin 2.0.21). На этой связке они падают с IncompatibleClassChangeError
        // («Found class KaCallableMemberCall, but interface was expected»),
        // и падение целиком роняет lintAnalyzeDebug и lintVitalAnalyzeRelease.
        // Воспроизводится и на чистом HEAD без локальных правок, то есть это
        // баг детекторов и версии lint (31.7.3 в составе AGP 8.7.3),
        // а не дефект кода. Отключаем ровно эти проверки — остальной
        // статический анализ продолжает работать.
        disable += "NullSafeMutableLiveData"
        disable += "FrequentlyChangingValue"

        // Тот же класс детекторов из androidx.compose.runtime.lint: они
        // разбирают тело композабла и падают на K2 по очереди, один за другим.
        // Первый упавший — RememberInCompositionDetector
        // («Found class KaSimpleVariableAccessCall, but interface was expected»).
        // Список закрывает весь пакет compose.runtime.lint, иначе после каждого
        // отключения падал бы следующий детектор по цепочке.
        disable += COMPOSE_RUNTIME_LINT_IDS
    }
}

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
}