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
        sourceCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
        targetCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)  // read 2026-10-04 from https://kotlinlang.org/docs/whatsnew24.html (KGP 2.4.20 JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(project(":android:modules:core"))
    implementation(project(":shared:modules:model-registry"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
