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

// Where the sherpa-onnx release file comes from. The address and the file name pattern are
// written once, in the pin file of the stt-ondevice module (the same file holds the version and
// the SHA-256 that the module build checks), and read here as plain text. A missing file or a
// missing key stops the build with a message that names it.
val sherpaPinFile = File(settingsDir, "android/modules/stt-ondevice/sherpa-onnx-aar.properties")
if (!sherpaPinFile.isFile) {
    throw GradleException("missing ${sherpaPinFile.path}: it holds the address of the sherpa-onnx release file")
}
val sherpaPin = java.util.Properties().also { pin -> sherpaPinFile.inputStream().use { pin.load(it) } }
val sherpaPinValue = { key: String ->
    sherpaPin.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("${sherpaPinFile.name}: the key '$key' is missing or empty")
}
val sherpaRepositoryUrl = sherpaPinValue("repositoryUrl")
val sherpaArtifactPattern = sherpaPinValue("artifactPattern")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The sherpa-onnx release file. This repository serves that one module and nothing else,
        // and no other repository may serve it. The group is a local name, not a real Maven group.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpaOnnxRelease"
                    url = java.net.URI.create(sherpaRepositoryUrl)
                    patternLayout { artifact(sherpaArtifactPattern) }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("external.github.k2-fsa", "sherpa-onnx") }
        }
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
include(":android:modules:commit:accessibility")
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
