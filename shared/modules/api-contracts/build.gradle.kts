// shared/modules/api-contracts — OpenAPI spec + Kotlin contract types.
//
// Card: shared/modules/api-contracts/AGENTS.md   Registry: modules.toml [module.shared_api_contracts]
// Owns: OpenAPI spec: auth, sync, jobs, updates, admin, training.
// Depends on: shared
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
