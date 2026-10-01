// Breaker — root Gradle settings.
//
// Every project below is a module registered in modules.toml. The Gradle
// project path mirrors the module path exactly, so `path` in modules.toml
// becomes ":<path with / replaced by :>".
//
// Three paths are pure containers with children of their own — android,
// server and shared (plus the `modules` level beneath each of them). Gradle
// creates those intermediate projects implicitly; they carry no build logic.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "breaker"

// ── phone client ────────────────────────────────────────────────────────────
include(":agent-skills")
include(":android")
include(":android:app")
include(":android:ui")
include(":android:modules:audio")
include(":android:modules:auth-client")
include(":android:modules:commit")
include(":android:modules:core")
include(":android:modules:crypto")
include(":android:modules:format")
include(":android:modules:gesture")
include(":android:modules:history")
include(":android:modules:overlay")
include(":android:modules:phrases")
include(":android:modules:settings")
include(":android:modules:stt-ondevice")
include(":android:modules:stt-server")
include(":android:modules:sync")
include(":android:modules:training-client")
include(":android:modules:transport")
include(":android:modules:updater")

// ── local server ────────────────────────────────────────────────────────────
include(":server")
include(":server:modules:deploy")
include(":server:modules:sync-api")
include(":server:modules:training")
include(":server:modules:web-fe")
include(":server:modules:whisper-server")

// ── shared contracts ────────────────────────────────────────────────────────
include(":shared")
include(":shared:modules:api-contracts")
include(":shared:modules:format-prompts")
include(":shared:modules:model-registry")
include(":shared:modules:ui-tokens")
