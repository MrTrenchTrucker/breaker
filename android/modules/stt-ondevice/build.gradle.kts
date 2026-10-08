import java.security.MessageDigest
import java.util.Properties

// android/modules/stt-ondevice — SttEngine (local path).
//
// Card: android/modules/stt-ondevice/AGENTS.md   Registry: modules.toml [module.android_stt_ondevice]
// Owns: sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.
// Depends on: android, android_core, shared_model_registry
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.breaker.dictation.stt.ondevice"
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

// The sherpa-onnx release file (an AAR) is used here to COMPILE the one file that talks to the
// speech engine, and for nothing else. This module does not ship it: the app adds the same
// release file at run time, which brings the classes and the native libraries, and the app
// chooses the ABIs. Do not change compileOnly to implementation without the app's agreement.
// verifySherpaAar stops the build when the downloaded file is not the one pinned in
// sherpa-onnx-aar.properties; it runs before every compile, lint, test and bundle task.
// settings.gradle.kts holds the repository that serves the file and no other repository may.

// The group is a local name, not a real Maven group; settings.gradle.kts writes the same text.
val sherpaGroup = "external.github.k2-fsa"
val sherpaVersion = libs.versions.sherpa.onnx.get()
val sherpaCoordinate = "$sherpaGroup:sherpa-onnx:$sherpaVersion@aar"

val sherpaAar by configurations.creating {
    description = "The pinned sherpa-onnx release file, resolved only so verifySherpaAar can hash it."
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

abstract class VerifySherpaAar : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val aar: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val pin: RegularFileProperty

    @get:Input
    abstract val catalogVersion: Property<String>

    init {
        // Hash the file on every build; never skip.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val pinFile = pin.get().asFile
        val props = Properties().also { p -> pinFile.inputStream().use { p.load(it) } }
        fun need(key: String): String =
            props.getProperty(key)
                ?: throw GradleException("${pinFile.name}: the key '$key' is missing; refusing to build with an unpinned sherpa-onnx file")
        val expected = need("sha256")
        val version = need("version")
        if (!Regex("[0-9a-f]{64}").matches(expected)) {
            throw GradleException("${pinFile.name}: sha256 must be exactly 64 lowercase hex digits (got ${expected.length} characters)")
        }
        if (version != catalogVersion.get()) {
            throw GradleException("${pinFile.name} pins version $version but gradle/libs.versions.toml says ${catalogVersion.get()}")
        }
        val files = aar.files
        if (files.size != 1) {
            throw GradleException("expected exactly one resolved sherpa-onnx file, got ${files.size}: ${files.map { it.name }}")
        }
        val file = files.single()
        if (!file.isFile) {
            throw GradleException("the resolved sherpa-onnx file is not a regular file: ${file.name}")
        }
        if (file.name != "sherpa-onnx-$version.aar") {
            throw GradleException("resolved ${file.name} but ${pinFile.name} pins version $version")
        }
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        fun short(d: String) = d.take(12) + ".." + d.takeLast(12)
        if (actual != expected) {
            throw GradleException(
                "sherpa-onnx AAR SHA-256 mismatch for ${file.name}: expected ${short(expected)}, actual ${short(actual)} " +
                    "(full expected $expected, actual $actual). The file in the Gradle cache is not the pinned release; build stopped."
            )
        }
        logger.lifecycle("sherpa-onnx AAR verified: ${file.name} sha256 ${short(actual)}")
    }
}

val verifySherpaAar = tasks.register<VerifySherpaAar>("verifySherpaAar") {
    group = "verification"
    description = "Fails the build unless the resolved sherpa-onnx AAR matches sherpa-onnx-aar.properties."
    aar.from(sherpaAar)
    pin.set(layout.projectDirectory.file("sherpa-onnx-aar.properties"))
    catalogVersion.set(sherpaVersion)
}

// Every variant's pre-build step hangs off preBuild, so this orders the check before all of them.
tasks.named("preBuild") { dependsOn(verifySherpaAar) }
// By name as well, so a task that does not hang off preBuild is still ordered after the check.
val afterSherpaCheck = Regex("(compile|lint|test|bundle|extract|check|assemble|build).*")
tasks.configureEach {
    if (name != "verifySherpaAar" && afterSherpaCheck.matches(name)) dependsOn(verifySherpaAar)
}

dependencies {
    add(sherpaAar.name, sherpaCoordinate)
    compileOnly(sherpaCoordinate)
}

dependencies {
    implementation(project(":android:modules:core"))
    implementation(project(":shared:modules:model-registry"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
