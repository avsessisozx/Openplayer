plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.openplayer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.0-beta8"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Herkese açık TEST imzası: her derleme aynı anahtarla imzalanır, böylece sürümler birbirinin
    // üstüne güncellenebilir. Bu anahtar gizli değildir; mağaza yayını için kendi anahtarınızı kullanın.
    signingConfigs {
        create("openplayer") {
            storeFile = file("openplayer-test.jks")
            storePassword = "openplayer"
            keyAlias = "openplayer"
            keyPassword = "openplayer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("openplayer")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("openplayer")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/versions/**", "META-INF/*.kotlin_module",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/io.netty.versions.properties"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.10.1-0.13.0")
    // Yedek oynatma motoru: ExoPlayer açamayan biçimler için (LGPL-2.1).
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
