plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "spike"; compileSdk = 36
    defaultConfig {
        applicationId = "org.openplayer.spike"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = true } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
