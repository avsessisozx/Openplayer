// Standalone x86_64 probe: does the mpv AAR load and render on the CI emulator?
// Not part of the app; only .github/workflows/mpv-probe.yml builds it.
plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "org.openplayer.mpvprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.openplayer.mpvprobe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "x86_64" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    // The AAR uses kotlinx.coroutines but does not declare it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
