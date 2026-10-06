// android/modules/core — Domain models, ports, use cases.
//
// Card: android/modules/core/AGENTS.md   Registry: modules.toml [module.android_core]
// Owns: Domain models, ports, use cases. No Android imports.
// Depends on: android
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

// Pure Kotlin/JVM: this module must not gain Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
    targetCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)  // read 2026-10-04 from https://kotlinlang.org/docs/whatsnew24.html (KGP 2.4.20 JvmTarget.JVM_25)
    }
}

dependencies {
    // The capture buffer is a kotlinx.coroutines Channel; the alias lands with
    // the transport PR's catalog hunk.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
