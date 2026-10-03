package dev.breaker.dictation.history

import java.io.File

/**
 * Where this module and the repository are, found from the test's working
 * directory, for the tests that read the module's own sources, card and build
 * script (and, read-only, a few files elsewhere in the repository).
 *
 * Both roots are found by walking up until a marker file turns up, and both fail
 * loudly when it never does, naming the folder the search started from: a check
 * that cannot find its files must not pass. They are found on first use, so every
 * test that needs one gets that message, not a class-initialisation error.
 */
internal object ModuleFiles {

    private val workingDirectory: File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    /** The module folder: the nearest folder above the working directory with a `build.gradle.kts`. */
    val moduleRoot: File by lazy {
        findUp(workingDirectory, "a build.gradle.kts") { File(it, "build.gradle.kts").isFile }
    }

    /** The repository root: the nearest folder above the module with both `modules.toml` and the version catalog. */
    val repoRoot: File by lazy {
        findUp(moduleRoot, "both modules.toml and gradle/libs.versions.toml") {
            File(it, "modules.toml").isFile && File(it, "gradle/libs.versions.toml").isFile
        }
    }

    /**
     * The nearest folder, [from] itself first and then each parent, for which [isRoot] holds.
     * Fails naming [from] and [looking] when there is none.
     */
    fun findUp(from: File, looking: String, isRoot: (File) -> Boolean): File =
        generateSequence(from) { it.parentFile }.firstOrNull(isRoot)
            ?: error("no folder with $looking at or above $from")

    /** The module's Kotlin sources under `src/main/kotlin`. */
    fun mainSources(): List<File> =
        File(moduleRoot, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    fun repoFile(path: String): File = File(repoRoot, path)
}
