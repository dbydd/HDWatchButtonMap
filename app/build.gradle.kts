plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hdwatch.buttonmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hdwatch.buttonmap"
        // Wear OS 3.0+ (Galaxy Watch 4/5/6/7/Ultra, Pixel Watch 1/2/3,
        // TicWatch Pro 5, OnePlus Watch 2/3, Xiaomi Watch 2, Oppo Watch, etc.)
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        // Debug builds are interpreted on Wear (JIT only) and frame times
        // suffer; a debug-signed release build is what we deploy to the watch.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-material:1.6.2")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20240303")
}
