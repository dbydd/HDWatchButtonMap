// AGP 9 has built-in Kotlin (no org.jetbrains.kotlin.android plugin anywhere).
// The buildscript classpath pins the Kotlin Gradle Plugin that built-in Kotlin uses,
// so the Compose compiler plugin version can match it exactly.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
