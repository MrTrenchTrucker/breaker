package dev.breaker.dictation.format

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source-pin for the UTF-8 decode in ServerLlmFormatter.
 *
 * The decode must name UTF-8 explicitly, not fall back on the JVM default
 * charset. The behaviour test that asserts an exact decode cannot kill a
 * revert to the default charset, because the default charset on the JVMs that
 * build and run this module is already UTF-8 — the two decode paths produce
 * the same bytes, so no behaviour test can tell them apart. That makes the
 * revert unfalsifiable by behaviour alone, so the pin reads the source file
 * and requires the explicit `String(reply.body, Charsets.UTF_8)` call: a
 * revert to `String(reply.body)` (the default-charset form) is cut away and
 * this test goes red.
 *
 * The source is read from the module's own tree, the same way the commit
 * module's rule tests read theirs: the working directory climbs to find the
 * folder that has a build script and the main sources.
 */
class ServerLlmFormatterSourcePinTest {

    private val workingDirectory: File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    private val moduleRoot: File by lazy {
        var folder: File? = workingDirectory
        while (folder != null) {
            val hasBuild: Boolean = File(folder, "build.gradle.kts").isFile
            val hasMain: Boolean = File(
                folder, "src/main/kotlin/dev/breaker/dictation/format"
            ).isDirectory
            if (hasBuild && hasMain) return@lazy folder
            folder = folder.parentFile
        }
        error("format: no folder with a build script and main sources at or above $workingDirectory")
    }

    private val source: String by lazy {
        File(moduleRoot, "src/main/kotlin/dev/breaker/dictation/format/ServerLlmFormatter.kt")
            .readText()
    }

    @Test
    fun `the decode names UTF-8 explicitly, not the default charset`() {
        // The exact call the formatter must carry. A revert to the default
        // charset drops this literal, so the cut-first rule makes this test
        // red on M19 (String(reply.body, Charsets.UTF_8) -> String(reply.body)).
        assertTrue(
            "ServerLlmFormatter must decode the reply with UTF-8 explicitly, " +
                "not the JVM default charset: " + source,
            source.contains("String(reply.body, Charsets.UTF_8)"),
        )
    }

    @Test
    fun `the decode is not the bare default-charset form`() {
        // The cut form must not be what the file carries. This is the second
        // reading of the same rule: the pin must carry the explicit charset,
        // and the default-charset form must not be the one in the source.
        assertTrue(
            "ServerLlmFormatter must not decode the reply with the bare default " +
                "charset (String(reply.body)); it carries the explicit UTF-8 form: " + source,
            !source.contains("val text = String(reply.body)\n"),
        )
    }
}
