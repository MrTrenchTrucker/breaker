// android/modules/history — HistoryStore.
//
// Card: android/modules/history/AGENTS.md   Registry: modules.toml [module.android_history]
// Owns: SQLite transcription history, 3-month TTL cleanup, tombstones.
// Depends on: android, android_core
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.breaker.dictation.history"
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
    // Test-only: runs HistorySql on the desktop SQLite; never shipped.
    testImplementation(libs.sqlite.jdbc)
}

// Test-read repo files declared as Gradle inputs (helper: gradle/test-reads-repo-files.gradle.kts,
// applied from the root build; paths are relative to the repository root).
extra["repoFileInputs"] = listOf(
    "android/app/build.gradle.kts",
    "gradle/libs.versions.toml",
    "build.gradle.kts",
    "android/ui/build.gradle.kts",
    "android/modules/core/build.gradle.kts",
    "android/modules/history/build.gradle.kts",
    "android/modules/history/AGENTS.md",
    "modules.toml",
    "decisions/ADR-010-retention.md",
    "server/modules/sync-api/AGENTS.md",
)
