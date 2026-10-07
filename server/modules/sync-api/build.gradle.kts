// server/modules/sync-api — AuthApi, SyncApi, AdminApi.
//
// Card: server/modules/sync-api/AGENTS.md   Registry: modules.toml [module.server_sync_api]
// Owns: Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy.
// Depends on: server, shared_api_contracts
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

// Pure Kotlin/JVM server code: the Ktor HTTP server, the SQLite account store.
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
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
