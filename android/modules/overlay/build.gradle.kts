// android/modules/overlay — FloatingTile.
//
// Card: android/modules/overlay/AGENTS.md   Registry: modules.toml [module.android_overlay]
// Owns: Floating tile = CB mic glyph + LED bar meter (WindowManager overlay, F36).
// Depends on: android, android_core, shared_ui_tokens
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.breaker.dictation.overlay"
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
    implementation(project(":shared:modules:ui-tokens"))

    testImplementation(libs.junit)
}
