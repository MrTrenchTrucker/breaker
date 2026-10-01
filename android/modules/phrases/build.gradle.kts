// android/modules/phrases — PhraseDetector.
//
// Card: android/modules/phrases/AGENTS.md   Registry: modules.toml [module.android_phrases]
// Owns: Voice phrases: 'Breaker Breaker' wake + 'And I'm Gone' send detection, reporting where the send phrase began.
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
    namespace = "dev.breaker.dictation.phrases"
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
