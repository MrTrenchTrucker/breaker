// android/modules/stt-ondevice — SttEngine (local path).
//
// Card: android/modules/stt-ondevice/AGENTS.md   Registry: modules.toml [module.android_stt_ondevice]
// Owns: sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.
// Depends on: android, android_core, shared_model_registry
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.breaker.dictation.stt.ondevice"
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
    implementation(project(":shared:modules:model-registry"))

    testImplementation(libs.junit)
}
