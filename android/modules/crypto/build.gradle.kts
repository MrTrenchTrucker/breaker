// android/modules/crypto — CryptoService.
//
// Card: android/modules/crypto/AGENTS.md   Registry: modules.toml [module.android_crypto]
// Owns: Per-user DEK unwrap (Argon2id + AES-256-GCM), encrypt/decrypt transcriptions.
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
    namespace = "dev.breaker.dictation.crypto"
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
