// android/app - App entry, DI container.
//
// Card: android/app/AGENTS.md   Registry: modules.toml [module.android_app]
// Owns: Android entry point, dependency injection wiring, Gradle build.
// Depends on: android, android_core, android_ui, android_settings, android_history,
// android_audio, android_format, android_transport, android_overlay, android_stt_ondevice,
// shared_ui_tokens, shared_model_registry
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

        // Debug placeholders; the release numbers are set when a release is cut.
        versionCode = 1
        versionName = "0.1.0-debug"

        // arm64-v8a is the primary target device architecture.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
        targetCompatibility = JavaVersion.VERSION_25  // read 2026-10-04 from https://docs.gradle.org/9.8.0/userguide/compatibility.html (Gradle 9.8.0 JavaVersion.VERSION_25)
    }

    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)  // read 2026-10-04 from https://kotlinlang.org/docs/whatsnew24.html (KGP 2.4.20 JvmTarget.JVM_25)
    }
}

// The speech engine library is packaged here: the stt-ondevice module only compiles against it
// (compileOnly), so the classes and the native libraries come from this runtime dependency. It is the
// same pinned release file the module checks; settings.gradle.kts holds the one repository that serves it.
// The ABI filter above is the only architecture the build ships.
val sherpaCoordinate = "external.github.k2-fsa:sherpa-onnx:" + libs.versions.sherpa.onnx.get() + "@aar"

// The hash check of that file lives in the stt-ondevice module; every build step that packages or
// tests this app runs after it.
val verifySherpaAarPath = ":android:modules:stt-ondevice:verifySherpaAar"
tasks.named("preBuild") { dependsOn(verifySherpaAarPath) }
tasks.configureEach {
    if (Regex("(compile|lint|test|bundle|extract|merge|package|check|assemble|build).*").matches(name)) {
        dependsOn(verifySherpaAarPath)
    }
}

dependencies {
    implementation(project(":android:modules:core"))
    implementation(project(":android:modules:settings"))
    implementation(project(":android:modules:history"))
    implementation(project(":android:modules:audio"))
    implementation(project(":android:modules:format"))
    implementation(project(":android:modules:transport"))
    implementation(project(":android:ui"))
    implementation(project(":android:modules:overlay"))
    implementation(project(":android:modules:stt-ondevice"))
    implementation(project(":shared:modules:ui-tokens"))
    implementation(project(":shared:modules:model-registry"))
    implementation(project(":android:modules:commit"))
    runtimeOnly(project(":android:modules:commit:accessibility"))
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(sherpaCoordinate)

    testImplementation(libs.junit)
}
