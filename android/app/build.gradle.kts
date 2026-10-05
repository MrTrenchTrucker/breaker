// android/app — App entry, DI container.
//
// Card: android/app/AGENTS.md   Registry: modules.toml [module.android_app]
// Owns: Android entry point, dependency injection wiring, Gradle build.
// Depends on: android, android_core, android_ui
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.breaker.dictation"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.breaker.dictation"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // arm64-v8a is the primary target device architecture.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":android:modules:core"))
    implementation(project(":android:ui"))

    testImplementation(libs.junit)
}
