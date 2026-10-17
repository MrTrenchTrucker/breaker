package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six files that hold the commit logic are free of the Android framework.
 *
 * That is what lets a plain JVM test run the whole service without a device, and
 * nothing else stops an `import android...` or a `Context` parameter from landing
 * in one of them. The scan reads code only: a comment, a string or a longer word
 * that merely contains one of the names is not a reference.
 *
 * It is text. A framework type that is reached without any of these names is not
 * seen here; the module's compile classpath is the stronger guard.
 */
internal class PureFilesScanTest {

    private val pureFiles: Set<String> = setOf(
        "CommitService.kt",
        "CommitTexts.kt",
        "FocusedField.kt",
        "FocusedFieldRegistry.kt",
        "PlatformSeams.kt",
        "PostedMainThread.kt",
    )

    private val rules: Map<String, Regex> = mapOf(
        "framework package" to Regex("""(?<![\p{L}\p{N}_.])androidx?\s*\."""),
        "Context" to Regex("""(?<![\p{L}\p{N}_.])Context(?![\p{L}\p{N}_])"""),
        "Handler" to Regex("""(?<![\p{L}\p{N}_.])Handler(?![\p{L}\p{N}_])"""),
        "Looper" to Regex("""(?<![\p{L}\p{N}_.])Looper(?![\p{L}\p{N}_])"""),
        "adapter import" to Regex("""\bcommit\s*\.\s*adapter\b"""),
    )

    private class Sample(val label: String, val source: String, val rule: String)

    @Test
    fun `the six pure files name no framework type and do not reach into the adapter folder`() {
        val main: Map<String, String> = SourceFiles.mainSources()
        val chosen: MutableMap<String, String> = LinkedHashMap()
        for (name in pureFiles) {
            chosen[name] = main[name] ?: error("commit: pure file $name is missing from the main folder")
        }
        assertEquals(
            "commit: a pure file names the framework, Context, Handler, Looper or the adapter folder",
            emptyList<String>(),
            SourceFiles.offences(chosen, rules),
        )
    }

    @Test
    fun `every file directly in the main folder is one of the six scanned pure files`() {
        val top: Set<String> = SourceFiles.mainSources().keys.filter { !it.contains('/') }.toSet()
        assertEquals("commit: a main file outside the adapter folder is not covered by the pure file scan", pureFiles, top)
    }

    @Test
    fun `samples that name the framework are reported under the rule that sees them`() {
        val samples: List<Sample> = listOf(
            Sample("an import", "import android.util.Log\nobject A", "framework package"),
            Sample("an androidx import", "import androidx.core.app.Foo\nobject A", "framework package"),
            Sample("a qualified name over a line break", "object A { val c: android\n    .content.Foo? = null }", "framework package"),
            Sample("a spaced dot", "object A { val c: android . os . Bundle? = null }", "framework package"),
            Sample("the Context type", "object A { fun f(c: Context) {} }", "Context"),
            Sample("a Handler", "object A { val h = Handler(x) }", "Handler"),
            Sample("a Looper", "object A { val l: Looper? = null }", "Looper"),
            Sample("an import from the adapter folder", "import dev.breaker.dictation.commit.adapter.Foo\nobject A", "adapter import"),
            Sample("a name inside a template hole", "object A { val s = \"x \${Context}\" }", "Context"),
            Sample("code after a character literal that holds a quote", "object A { val q = '\"'; val c: Context? = null }", "Context"),
        )
        for (sample in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("sample.kt" to sample.source), rules)
            assertTrue(
                "commit: the pure file scan missed ${sample.label}, found $found",
                found.any { it.startsWith("sample.kt: ${sample.rule} x") },
            )
        }
    }

    @Test
    fun `samples with the names only in comments, strings or longer words are not reported`() {
        val fine: Map<String, String> = mapOf(
            "a line comment" to "// import android.util.Log\nobject A",
            "a block comment" to "/* Context Handler Looper */ object A",
            "a nested block comment" to "/* a /* Context */ Looper */ object A",
            "a KDoc" to "/**\n * Runs on android.os with a Context.\n */\nobject A",
            "a string" to "object A { const val S = \"android.util.Log Context Looper\" }",
            "a raw string" to "object A { const val S = \"\"\"Handler android.os\"\"\" }",
            "a comment marker inside a string" to "object A { const val S = \"http://x\" }\nobject B",
            "longer words" to "object A { val androidFoo = 1; val myandroid = 2; val ContextFree = 3; val HandlerMainThread = 4; val LooperX = 5 }",
            "a quote in a character literal" to "object A { val q = '\"' }\n// android.os",
        )
        for ((label, source) in fine) {
            assertEquals(
                "commit: the pure file scan flagged $label",
                emptyList<String>(),
                SourceFiles.offences(mapOf("sample.kt" to source), rules),
            )
        }
    }

    @Test
    fun `a pure file that cannot be read as code is refused, not passed`() {
        assertThrows(IllegalStateException::class.java) {
            SourceFiles.strip("object A { val s = \"never ends\n}")
        }
        assertThrows(IllegalStateException::class.java) {
            SourceFiles.strip("object A /* never closed\n")
        }
    }
}
