package dev.breaker.dictation.overlay

import java.io.File

/**
 * Where this module is, found from the test's working directory, for the tests that
 * read the module's own sources and manifest as text.
 *
 * The module folder is found by walking up until a marker file turns up, and the search
 * fails loudly when it never does, naming the folder it started from: a check that
 * cannot find its files must not pass. It is found on first use, so a test that needs
 * it gets that message, not a class-initialisation error. Nothing here writes a file
 * or keeps state that depends on which test ran first.
 */
internal object ModuleFiles {

    private val workingDirectory: File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    /** The module folder: the nearest folder above the working directory with a `build.gradle.kts` and this module's sources. */
    val moduleRoot: File by lazy {
        val found = findUp(workingDirectory, "a build.gradle.kts") { File(it, "build.gradle.kts").isFile }
        check(File(found, MAIN_PACKAGE_PATH).isDirectory) {
            "android_overlay: $found has a build.gradle.kts but no $MAIN_PACKAGE_PATH, so it is not the overlay module"
        }
        found
    }

    /**
     * The nearest folder, [from] itself first and then each parent, for which [isRoot] holds.
     * Fails naming [from] and [looking] when there is none.
     */
    fun findUp(from: File, looking: String, isRoot: (File) -> Boolean): File =
        generateSequence(from) { it.parentFile }.firstOrNull(isRoot)
            ?: error("android_overlay: no folder with $looking at or above $from")

    /** The text of every main Kotlin source of the module, by file name. Fails when the folder holds none. */
    fun mainTexts(): Map<String, String> = textsUnder("src/main/kotlin")

    /** The text of every test Kotlin source of the module, by file name. Fails when the folder holds none. */
    fun testTexts(): Map<String, String> = textsUnder("src/test/kotlin")

    /** The module manifest as text, or null when the file is not there. */
    fun manifestText(): String? =
        File(moduleRoot, "src/main/AndroidManifest.xml").takeIf { it.isFile }?.readText()

    private fun textsUnder(relative: String): Map<String, String> {
        val root = File(moduleRoot, relative)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(files.isNotEmpty()) { "android_overlay: found no Kotlin source under $root; the scan cannot run" }
        check(files.map { it.name }.distinct().size == files.size) {
            "android_overlay: two sources under $root share a file name: ${files.map { it.name }.sorted()}"
        }
        return files.associate { it.name to it.readText() }
    }

    private const val MAIN_PACKAGE_PATH = "src/main/kotlin/dev/breaker/dictation/overlay"
}
