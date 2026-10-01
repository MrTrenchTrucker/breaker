// shared/modules/format-prompts — prompts/* + the prompt contract as types.
//
// Card: shared/modules/format-prompts/AGENTS.md   Registry: modules.toml [module.shared_format_prompts]
// Owns: Strict non-destructive formatting prompts for the server LLM.
// Depends on: shared
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.
//
// The prompt text is the data of record (`prompts/format-v1.md`, also shipped
// on the classpath so a consumer cannot read a different copy); the types below
// are the contract the text is held to. The formatter itself is
// android/format and the LLM is the server's — neither lives here.

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

// The prompt file is loaded from the classpath, so the copy under prompts/ is
// the single copy: no task may generate a second one.
sourceSets {
    main {
        resources.srcDir(rootProject.projectDir.resolve("shared/modules/format-prompts/prompts"))
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("breaker.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
