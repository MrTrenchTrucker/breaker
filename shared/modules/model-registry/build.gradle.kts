// shared/modules/model-registry — models.yaml + the generated Kotlin registry.
//
// Card: shared/modules/model-registry/AGENTS.md   Registry: modules.toml [module.shared_model_registry]
// Owns: Model sizes, immutable release-asset URLs, upstream checksum.txt, licenses, hosted flag.
// Depends on: shared
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

// The committed Kotlin (src/main/kotlin) is generated from models.yaml by
// tools/gen_model_registry.py (ADR-016); the build compiles it like any other
// module source. The models.yaml itself is build input, never shipped code.
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

// The unit test reads models.yaml from the module root, so the path is passed
// as a test property (the format-prompts pattern) instead of being discovered
// from the class location.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("breaker.moduleRoot", rootProject.projectDir.resolve("shared/modules/model-registry").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
