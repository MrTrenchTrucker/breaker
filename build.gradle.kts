// Breaker — root build.
//
// Plugin versions live in one place: gradle/libs.versions.toml. They are
// declared here (without applying them) so every module resolves the same
// toolchain, and a version bump touches exactly one file.
//
// Toolchain: Gradle 8.13 (distribution SHA-256 pinned in
// gradle/wrapper/gradle-wrapper.properties) · AGP 8.13.0 · Kotlin 2.0.21 ·
// JDK 17 · compileSdk 35 · targetSdk 35 · minSdk 30.
//
// This project holds no code. Each module under it owns its own sources and
// its own card (AGENTS.md) in the same folder.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
