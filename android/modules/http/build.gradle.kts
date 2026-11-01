// android/modules/http - the one HTTP client for calls to the Local Server and the update host.
//
// Card: android/modules/http/AGENTS.md   Registry: modules.toml [module.android_http]
// Owns: GET and POST with request headers and a fixed-length body, no redirects, no cache, https for any host and plain http only for the configured server, connect and read timeouts, a reply-size cap, no logging of a header or a body.
// Depends on: android, android_core
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.breaker.dictation.http"
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
}
