package dev.breaker.dictation.core.testing

import java.io.File

/**
 * The packages the domain must never depend on, and the two scans that enforce it.
 *
 * `FrameworkFreeTest` reads the sources for imports; `NoFrameworkBytecodeTest` reads
 * the compiled classes for package names. Both take their list from here, so the two
 * gates can never disagree about what is forbidden. `ForbiddenFrameworksTest` plants
 * a probe for every package below and checks that both scans find it.
 *
 * Packages are written in their dotted form. Removing one is a decision about the
 * architecture, and `ForbiddenFrameworksTest` keeps its own written-out list of probes
 * so that the removal shows up there by name.
 */
object ForbiddenFrameworks {
    val packages: List<String> = listOf(
        "android",
        "androidx",
        "dalvik",
        "java.net",
        "javax.net",
        "java.sql",
        "javax.sql",
        "java.nio.file",
        "jakarta",
        "okhttp3",
        "okio",
        "retrofit2",
        "com.squareup",
        "io.ktor",
        "org.springframework",
        "org.json",
        "com.google.gson",
        "kotlinx.serialization",
        "org.jetbrains.exposed",
        "dagger",
        "javax.inject",
        "org.koin",
    )

    private val importPatterns: List<Regex> =
        packages.map { Regex("""^\s*import\s+${Regex.escape(it)}\..*""") }

    private val internalPrefixes: List<String> = packages.map { it.replace('.', '/') + "/" }

    /** The source gate's check: does [line] import something from a forbidden package? */
    fun isForbiddenImport(line: String): Boolean = importPatterns.any { it.containsMatchIn(line) }

    /** The bytecode gate's check: is [internalName], in slash form, inside a forbidden package? */
    fun isForbiddenInternalName(internalName: String): Boolean =
        internalPrefixes.any { internalName.startsWith(it) }

    /** Every forbidden import in the Kotlin sources under [root], as `path: import line`. */
    fun importOffenders(root: File): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().filter { isForbiddenImport(it) }.map { "${file.path}: ${it.trim()}" }
            }
            .toList()

    /** Every forbidden package a compiled class under [root] names, as `file -> name`. */
    fun bytecodeOffenders(root: File): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .flatMap { classFile ->
                internalPackageNamesIn(classFile)
                    .filter { isForbiddenInternalName(it) }
                    .map { "${classFile.name} -> $it" }
            }
            .toList()

    /**
     * The internal package paths named in a class file.
     *
     * A class file stores type references as slash-separated UTF-8 constants, so
     * matching that shape finds real references without a bytecode library.
     */
    fun internalPackageNamesIn(classFile: File): List<String> {
        val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
        return Regex("""[a-z][a-zA-Z0-9_]*(?:/[a-zA-Z0-9_$]+)+""")
            .findAll(text)
            .map { it.value }
            .toList()
    }
}
