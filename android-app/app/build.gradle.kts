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
}