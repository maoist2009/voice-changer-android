plugins {
    id("com.android.application")
}

// Capture lab: a throw-away target app whose only job is to record through
// every capture API Android offers, one path per button, so an interception
// module can be tested against a known call shape instead of a third-party
// app whose internals have to be reverse engineered first.
//
// Deliberately dependency-free: no Compose, no AndroidX, no native build
// integration. The .so in src/main/jniLibs is produced with the NDK's
// arm64 host toolchain (see jniLibs/README.md); Gradle only packages it.
android {
    namespace = "org.voice.changer.lab"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.voice.changer.lab"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
