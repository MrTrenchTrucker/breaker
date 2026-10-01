// android/modules/audio — microphone capture, resampling, noise suppression,
// ring buffer, WAV encode. Public: MicCapture, Pcm16WavEncoder, MicSource,
// MicSourceException, NoiseSuppressor, PassThroughNoiseSuppressor,
// AdaptiveGateSuppressor, RecordingIndicator.
//
// Card: android/modules/audio/AGENTS.md   Registry: modules.toml [module.android_audio]
// Owns: Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM).
// Depends on: android, android_core
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.breaker.dictation.audio"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":android:modules:core"))

    testImplementation(libs.junit)
}
