package dev.breaker.dictation.core

import dev.breaker.dictation.core.testing.ForbiddenFrameworks
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core must stay pure Kotlin.
 *
 * This is the module's load-bearing architectural rule, so it is a test rather
 * than a review note: if a file under `src/main` ever imports an Android class,
 * a networking library, a persistence library or a DI framework, the build goes
 * red and the message names the file and the import.
 *
 * The rule is what keeps the domain testable with plain JUnit, and what keeps an
 * adapter from leaking a platform type into the middle of the app.
 *
 * The list of forbidden packages is [ForbiddenFrameworks.packages], shared with the
 * bytecode gate in `NoFrameworkBytecodeTest`, and `ForbiddenFrameworksTest` proves
 * that both gates flag every package on it.
 */
class FrameworkFreeTest {
    @Test
    fun `no source under src main imports a platform or framework package`() {
        val sources = File("src/main")
        assertTrue("expected core sources at ${sources.absolutePath}", sources.isDirectory)

        val offenders = ForbiddenFrameworks.importOffenders(sources)

        assertTrue(
            "core must stay pure Kotlin — remove these imports:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no source under src main names the platform file system or process at all`() {
        val sources = File("src/main")
        assertTrue("expected core sources at ${sources.absolutePath}", sources.isDirectory)
        val kotlinFiles = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(
            "found no Kotlin sources under ${sources.absolutePath}; a scan of nothing " +
                "must not pass",
            kotlinFiles.isNotEmpty(),
        )
        val forbidden = listOf(
            Regex("""java\.io\."""),
            Regex("""Runtime\.getRuntime"""),
            Regex("""System\.(getenv|getProperty|load|exit)"""),
            Regex("""ProcessBuilder"""),
        )
        val offenders = mutableListOf<String>()
        kotlinFiles.forEach { file ->
            file.forEachLine { line ->
                forbidden.forEach { pattern ->
                    if (pattern.containsMatchIn(line)) {
                        offenders += "${file.path}: ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "core must not do I/O or reach for the environment — remove these:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
