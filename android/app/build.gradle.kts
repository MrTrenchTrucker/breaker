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

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
    implementation(project(":android:modules:stt-server"))
    implementation(project(":shared:modules:ui-tokens"))
    implementation(project(":shared:modules:model-registry"))
    implementation(project(":android:modules:commit"))
    implementation(project(":android:modules:gesture"))
    implementation(project(":android:modules:crypto"))
    implementation(project(":android:modules:auth-client"))
    implementation(project(":android:modules:http"))
    implementation(project(":android:modules:sync"))
    implementation(project(":android:modules:phrases"))
    implementation(project(":android:modules:updater"))
    implementation(project(":shared:modules:api-contracts"))
    runtimeOnly(project(":android:modules:commit:accessibility"))
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(sherpaCoordinate)

    testImplementation(libs.junit)
}

// Test-read repo files declared as Gradle inputs (helper: gradle/test-reads-repo-files.gradle.kts,
// applied from the root build; paths are relative to the repository root).
extra["repoFileInputs"] = listOf(
    "settings.gradle.kts",
    "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/modules/commit/accessibility/build.gradle.kts",
    "android/modules/commit/accessibility/src/main/AndroidManifest.xml",
    "android/modules/stt-server/src/main/kotlin/dev/breaker/dictation/stt/server/FailureSentences.kt",
    "docs/brand/breaker-icon.svg",
    "docs/brand/breaker-mic-tile.svg",
    "docs/brand/breaker-logo-dark.svg",
    "docs/brand/breaker-logo-light.svg",
)
extra["repoFolderInputs"] = listOf(
    "android/modules",
)

// The owner's update key file is never committed. Its path comes from a Gradle property, or from an
// environment variable when the property is not set; a blank value counts as not set. With no file named,
// a release build passes, copies no key and logs one warning. A named file that is missing, is a symbolic
// link or is not a regular file, is empty, or has a line that is not a canonical base64 key of 32 bytes
// fails the build with the sentence below.
val updateKeysProperty = "breakerUpdateKeysFile"
val updateKeysVariable = "BREAKER_UPDATE_KEYS_FILE"
val updateKeysSentence = "the release build needs the owner's update key file: set the Gradle property $updateKeysProperty or the environment variable $updateKeysVariable to a file with one base64 public key on a line"
val updateKeysUnsetWarning = "no update key file is set, so this release build pins no update key and the app will refuse every update"
val updateKeysRequiredSentence = "the release artifact was requested by name but no update key file is set: set the Gradle property $updateKeysProperty or the environment variable $updateKeysVariable to a file with one base64 public key on a line, or build through the lifecycle task instead"
val updatePropertyValue: String? = providers.gradleProperty(updateKeysProperty).orNull?.trim()?.takeIf { it.isNotEmpty() }
val updateVariableValue: String? = providers.environmentVariable(updateKeysVariable).orNull?.trim()?.takeIf { it.isNotEmpty() }
val updateKeysSource: File? = (updatePropertyValue ?: updateVariableValue)?.let { File(it) }

// Whether the release artifact was asked for BY NAME (assembleRelease or bundleRelease as the last path
// segment of a requested task), rather than reached through the build lifecycle. Read once here, at
// configuration time, so the check below closes over a plain Boolean and nothing in it reads the project.
val releaseArtifactRequested: Boolean = gradle.startParameter.taskNames.any { requested ->
    val lastSegment: String = requested.substringAfterLast(':')
    lastSegment == "assembleRelease" || lastSegment == "bundleRelease"
}

val checkUpdateKeys by tasks.registering {
    doLast {
        val source: File? = updateKeysSource
        if (source == null) {
            if (releaseArtifactRequested) {
                throw GradleException(updateKeysRequiredSentence)
            } else {
                logger.warn(updateKeysUnsetWarning)
            }
        } else {
            val text: String? = if (Files.isSymbolicLink(source.toPath()) || !source.isFile) null else source.readText(Charsets.UTF_8)
            val lines: List<String> = (text?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }) ?: emptyList()
            val good: Boolean = lines.isNotEmpty() && lines.all { line ->
                val bytes: ByteArray? = runCatching { Base64.getDecoder().decode(line) }.getOrNull()
                bytes != null && bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == line
            }
            if (text == null || !good) throw GradleException(updateKeysSentence)
        }
    }
}

// The copy task owns the release asset folder. It empties that folder on every run, then copies the named key file
// only when that file is a regular file and not a symbolic link. Its output folder is declared, so the variant API
// hands it to every release consumer of the assets, and each consumer runs the copy after the check above.
abstract class CopyUpdateKeysTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val keyFilePath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // The key content is not an input the build can compare, so the task always runs.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun copyKey() {
        val folder: File = outputDir.get().asFile
        folder.mkdirs()
        folder.listFiles()?.forEach { child -> child.deleteRecursively() }
        val path: String? = keyFilePath.orNull
        if (path != null) {
            val source: File = File(path)
            if (Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                source.copyTo(File(folder, "update_keys.txt"), overwrite = true)
            }
        }
    }
}

// The key path is set only when a file is named, so an unset build has no path to copy.
val copyUpdateKeys by tasks.registering(CopyUpdateKeysTask::class) {
    updateKeysSource?.let { file -> keyFilePath.set(file.absolutePath) }
    dependsOn(checkUpdateKeys)
}

// The release variant gets the copy folder through the variant API, so the folder carries the copy task as its producer.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyUpdateKeys, CopyUpdateKeysTask::outputDir)
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(checkUpdateKeys) }
