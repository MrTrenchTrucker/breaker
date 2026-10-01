package dev.breaker.dictation.core

import dev.breaker.dictation.core.testing.ForbiddenFrameworks
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framework-free rule, checked on the compiled bytecode rather than only on
 * the source text.
 *
 * `FrameworkFreeTest` reads the Kotlin sources, which is the right first gate but
 * can be fooled: a fully-qualified reference written without an import line, or a
 * reference smuggled in through a constant. This gate reads the constant pool of
 * every class the module actually shipped, so what it reports is what a real
 * adapter would link against at runtime.
 *
 * It is deliberately dependency-free: scanning a class file for the internal
 * package paths it names needs nothing but `readBytes()`, because a class file
 * stores every type it references as a UTF-8 constant. Taking a bytecode library
 * to check that the module has no libraries would defeat the point.
 *
 * The class files live under `build/classes/kotlin/main`, where the compile task
 * writes them. If that layout ever changes the test fails loudly rather than
 * passing vacuously.
 *
 * The list of forbidden packages is [ForbiddenFrameworks.packages], shared with the
 * source gate in `FrameworkFreeTest`, and `ForbiddenFrameworksTest` proves that both
 * gates flag every package on it.
 */
class NoFrameworkBytecodeTest {
    private val compiledClasses = File("build/classes/kotlin/main")

    @Test
    fun `no compiled class in the domain references a platform or framework package`() {
        assertTrue(
            "core's compiled classes were not found at ${compiledClasses.absolutePath}; " +
                "this gate must not pass vacuously — check the build layout",
            compiledClasses.isDirectory,
        )

        val classFiles = compiledClasses.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .toList()
        assertTrue(
            "expected compiled classes under ${compiledClasses.absolutePath}, found none",
            classFiles.isNotEmpty(),
        )

        val offenders = ForbiddenFrameworks.bytecodeOffenders(compiledClasses)

        assertTrue(
            "core must stay framework-free — these compiled classes reference a " +
                "platform or framework package:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scan can actually see a forbidden package when one is present`() {
        // A negative test for the gate itself. If the scanner were broken — say it
        // matched nothing — the test above would pass forever and prove nothing.
        // This scans a real class that does name a forbidden package and confirms
        // the scan finds it.
        val probeClass = Class.forName("dev.breaker.dictation.core.ForbiddenPackageProbe")
        val scanned = ForbiddenFrameworks.internalPackageNamesIn(File(classFilePathOf(probeClass)))
        assertTrue(
            "the bytecode scan found no package names at all, so it cannot be " +
                "trusted; expected it to spot a forbidden reference in " +
                probeClass.name,
            scanned.any { isForbidden(it) },
        )
    }

    @Test
    fun `the check the gate uses lets ordinary references through`() {
        val ordinary = listOf(
            "kotlin/collections/CollectionsKt",
            "java/lang/String",
            "java/util/List",
            "dev/breaker/dictation/core/model/AppSettings",
        )

        val flagged = ordinary.filter { isForbidden(it) }

        assertTrue("these ordinary references were flagged as forbidden: $flagged", flagged.isEmpty())
    }

    /** The one check the gate and its self-test both go through. */
    private fun isForbidden(internalName: String): Boolean =
        ForbiddenFrameworks.isForbiddenInternalName(internalName)

    /**
     * Where the JVM loaded [type] from. Resolved through the classloader rather
     * than kotlin-reflect, because this module deliberately has no dependencies.
     */
    private fun classFilePathOf(type: Class<*>): String {
        val resource = "${type.name.replace('.', '/')}.class"
        val url = type.classLoader.getResource(resource)
            ?: throw AssertionError("cannot locate the compiled class file for ${type.name}")
        return File(url.toURI()).path
    }
}

/**
 * A helper that names a platform type, used to prove the bytecode gate has
 * teeth. It lives in the test source set, so it is never part of the shipped
 * domain and the gate above — which only walks `build/classes/kotlin/main` —
 * does not see it.
 *
 * Public on purpose: a private, never-called method can be dropped by the
 * compiler, which would leave the gate's own self-test with nothing to find.
 */
object ForbiddenPackageProbe {
    @Suppress("unused")
    fun probe(): java.net.URL = java.net.URL("https://example.invalid")
}
