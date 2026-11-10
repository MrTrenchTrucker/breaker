// Breaker — root build.
//
// Plugin versions live in one place: gradle/libs.versions.toml. They are
// declared here (without applying them) so every module resolves the same
// toolchain, and a version bump touches exactly one file.
//
// Toolchain: Gradle 9.8.0 (distribution SHA-256 pinned in
// gradle/wrapper/gradle-wrapper.properties) · AGP 9.4.1 · Kotlin 2.4.20 ·
// JDK 25 · compileSdk 36 · targetSdk 36 · minSdk 30.
//
// This project holds no code. Each module under it owns its own sources and
// its own card (AGENTS.md) in the same folder.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Every module applies the shared test-input helper (data-driven, see
// gradle/test-reads-repo-files.gradle.kts). Entries set by each module are
// repo-root-relative paths resolved against the root project directory.
subprojects {
    apply(from = rootProject.file("gradle/test-reads-repo-files.gradle.kts"))
}
