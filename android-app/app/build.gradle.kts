plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Robolectric: unit-тесты работают с реальным (эмулированным) Android-манифестом,
    // иначе Compose UI test не находит ComponentActivity ("Unable to resolve activity")
    testOptions { unitTests { isIncludeAndroidResources = true } }
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
    // Автоматические проверки доступности (tryPerformAccessibilityChecks) — Фаза 1 UI-PLAN
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Robolectric 4.14 + SDK 35 несовместимы (Build.FINGERPRINT == null), фиксирован sdk=34
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}