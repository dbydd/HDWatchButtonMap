plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hdwatch.buttonmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hdwatch.buttonmap"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    buildFeatures {
        compose = true
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
}
