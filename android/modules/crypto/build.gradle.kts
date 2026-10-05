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
}

android {
    namespace = "dev.breaker.dictation.crypto"
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

    testImplementation(libs.junit)
}
