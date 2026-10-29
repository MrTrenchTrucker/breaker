package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test class inherits the shared rule helpers and the pinned plain/android lists from [AppLayoutGateRules];
 * every rule, every list entry, every planted-sample test and every assertion message carries over verbatim.
 */
class AppLayoutGateTest : AppLayoutGateRules() {

    @Test
    fun `every main Kotlin file is in exactly one of the two lists and every listed file exists`() {
        val main: Map<String, String> = mainFiles()
        assertTrue("app: no main Kotlin file was found to check", main.isNotEmpty())
        assertTrue("app: the plain list and the android list share a file", (plainFiles intersect androidFiles).isEmpty())
        assertEquals("app: the main files and the two lists differ", emptyList<String>(), layoutProblems(main.keys, plainFiles, androidFiles))
    }

    @Test
    fun `no plain main file imports or names an android type`() {
        assertEquals("app: a plain main file touches an android type", emptyList<String>(), plainProblems(mainFiles(), plainFiles))
    }

    @Test
    fun `every android main file names at least one android type`() {
        assertEquals("app: an android main file names no android type", emptyList<String>(), androidProblems(mainFiles(), androidFiles))
    }

    @Test
    fun `no unit test file imports or names an android type`() {
        val tests: Map<String, String> = testFiles()
        assertTrue("app: no test Kotlin file was found to check", tests.isNotEmpty())
        assertEquals("app: a unit test touches an android type and would fail on a plain JVM", emptyList<String>(), testProblems(tests))
    }

    @Test
    fun `no main or test Kotlin file is longer than 300 lines`() {
        val main: Map<String, String> = mainFiles()
        val tests: Map<String, String> = testFiles()
        assertTrue("app: no Kotlin file was found to measure", main.isNotEmpty() && tests.isNotEmpty())
        assertEquals("app: a Kotlin file is over the line cap", emptyList<String>(), sizeProblems("main", main) + sizeProblems("test", tests))
    }

    @Test
    fun `the layout rule reports an added, removed, renamed, moved or doubly listed file by name`() {
        val plainNames = setOf("A.kt")
        val androidNames = setOf("B.kt")
        val neither = "is in neither list; add it to the plain list or the android list"
        assertEquals("app: a matching tree was reported", emptyList<String>(), layoutProblems(setOf("A.kt", "B.kt"), plainNames, androidNames))
        val added: List<String> = layoutProblems(setOf("A.kt", "B.kt", "C.kt"), plainNames, androidNames)
        assertEquals("app: an added file was not reported", listOf("app: the main file C.kt $neither"), added)
        val removed: List<String> = layoutProblems(setOf("A.kt"), plainNames, androidNames)
        assertEquals("app: a removed file was not reported", listOf("app: the listed file B.kt is not in the main sources"), removed)
        val moved: List<String> = layoutProblems(setOf("wiring/A.kt", "B.kt"), plainNames, androidNames)
        val expectedMove = listOf("app: the main file wiring/A.kt $neither", "app: the listed file A.kt is not in the main sources")
        assertEquals("app: a file moved between folders was not reported", expectedMove, moved)
        val both: List<String> = layoutProblems(setOf("A.kt", "B.kt"), plainNames, setOf("A.kt", "B.kt"))
        assertEquals("app: a file in both lists was not reported", listOf("app: the main file A.kt is in both lists"), both)
    }

    @Test
    fun `a plain file is reported for an import or a qualified use of an android name and not for prose`() {
        val firing: Map<String, String> = mapOf(
            "an import" to edit("an import", goodPlain, "import dev.breaker.dictation.core.Thing", "import android.util.Log"),
            "an androidx import" to edit("an androidx import", goodPlain, "import dev.breaker.dictation.core.Thing", "import androidx.core.app.Foo"),
            "a qualified use in a body" to edit("a qualified use", goodPlain, "= 1", "= android.os.Process.myPid()"),
            "a qualified use as a type" to edit("a type", goodPlain, "fun f(): Int = 1", "val c: android.content.Context? = null"),
            "a use in a template hole" to edit("a hole", goodPlain, "= 1", "= \"x \${android.os.Build.MODEL}\".length"),
            "a use split over lines" to edit("split", goodPlain, "= 1", "= android\n        .os.Process.myPid()"),
        )
        for ((label, source) in firing) {
            val problems: List<String> = plainProblems(mapOf("A.kt" to source), setOf("A.kt"))
            assertTrue("app: the plain file rule did not report $label, found $problems", problems.size == 1 && problems[0].startsWith("app: the plain file A.kt uses android"))
        }
        assertEquals("app: the name of the used android type was not in the message", listOf("app: the plain file A.kt uses android.util.Log, which a JVM test cannot load"), plainProblems(mapOf("A.kt" to firing.getValue("an import")), setOf("A.kt")))
        val quiet: Map<String, String> = mapOf(
            "a line comment" to edit("a line comment", goodPlain, "class A", "// uses android.util.Log in the layer above\nclass A"),
            "a KDoc" to edit("a KDoc", goodPlain, "class A", "/** Never touches android.app.Service or androidx.core. */\nclass A"),
            "a block comment" to edit("a block comment", goodPlain, "class A", "/* import android.util.Log */\nclass A"),
            "a string" to edit("a string", goodPlain, "= 1", "= \"android.util.Log\".length"),
            "a raw string" to edit("a raw string", goodPlain, "= 1", "= \"\"\"import android.os.Bundle\"\"\".length"),
            "a longer name" to edit("a longer name", goodPlain, "= 1", "= androidLike + androidx + Android.x + androids.y"),
            "a name ending in the word" to edit("a name end", goodPlain, "= 1", "= myandroid.value"),
            "a member of another name" to edit("a member", goodPlain, "= 1", "= other.android.value"),
            "a name in backticks" to edit("backticks", goodPlain, "class A", "class T { fun `uses android.util.Log`() {} }\nclass A"),
            "a longer package" to edit("a package", goodPlain, "import dev.breaker.dictation.core.Thing", "import dev.breaker.dictation.android.Thing"),
        )
        for ((label, source) in quiet) {
            assertEquals("app: the plain file rule flagged $label", emptyList<String>(), plainProblems(mapOf("A.kt" to source), setOf("A.kt")))
        }
        assertEquals("app: a missing plain file was not reported", listOf("app: the plain file A.kt is missing"), plainProblems(emptyMap(), setOf("A.kt")))
    }

    @Test
    fun `an android file with no android name is reported, in a comment or in a string too`() {
        val none: Map<String, String> = mapOf(
            "no use at all" to edit("no use", goodAndroid, "import android.content.Context\n", ""),
            "the import removed and the type changed" to edit("type", edit("import", goodAndroid, "import android.content.Context\n", ""), "c: Context", "c: Any"),
            "only a comment" to edit("comment", goodAndroid, "import android.content.Context", "// android.content.Context\nimport dev.breaker.dictation.core.Thing"),
            "only a string" to edit("string", goodAndroid, "import android.content.Context", "import dev.breaker.dictation.core.Thing\nval s = \"android.content.Context\""),
            "only a longer name" to edit("name", goodAndroid, "import android.content.Context", "import dev.breaker.dictation.core.Thing\nval androidLike = 1"),
        )
        for ((label, source) in none) {
            val problems: List<String> = androidProblems(mapOf("B.kt" to source), setOf("B.kt"))
            assertEquals("app: the android file rule did not report $label", listOf("app: the android file B.kt names no android type, so it belongs in the plain list"), problems)
        }
        val named: Map<String, String> = mapOf(
            "an import" to goodAndroid,
            "an androidx import" to edit("androidx", goodAndroid, "import android.content.Context", "import androidx.core.app.Foo"),
            "a qualified use only" to edit("qualified", edit("import", goodAndroid, "import android.content.Context\n", ""), "c: Context", "c: android.content.Context"),
        )
        for ((label, source) in named) {
            assertEquals("app: the android file rule flagged $label", emptyList<String>(), androidProblems(mapOf("B.kt" to source), setOf("B.kt")))
        }
        assertEquals("app: a missing android file was not reported", listOf("app: the android file B.kt is missing"), androidProblems(emptyMap(), setOf("B.kt")))
    }

    @Test
    fun `a unit test file is reported for an android import or use and not for the same text in a string or a comment`() {
        val firing: Map<String, String> = mapOf(
            "an import" to edit("an import", goodTest, "import org.junit.Test", "import android.util.Log\nimport org.junit.Test"),
            "an androidx import" to edit("androidx", goodTest, "import org.junit.Test", "import androidx.test.core.app.Foo\nimport org.junit.Test"),
            "a qualified use" to edit("qualified", goodTest, "@Test fun t() {}", "@Test fun t() { android.util.Log.d(\"a\", \"b\") }"),
        )
        for ((label, source) in firing) {
            val problems: List<String> = testProblems(mapOf("ATest.kt" to source))
            assertTrue("app: the test rule did not report $label, found $problems", problems.size == 1 && problems[0].startsWith("app: the test file ATest.kt uses android"))
        }
        val quiet: Map<String, String> = mapOf(
            "a comment" to edit("comment", goodTest, "class ATest", "// import android.util.Log\nclass ATest"),
            "a KDoc" to edit("KDoc", goodTest, "class ATest", "/** Builds no android.app.Service. */\nclass ATest"),
            "a string" to edit("string", goodTest, "@Test fun t() {}", "@Test fun t() { check(\"import android.os.Bundle\".isNotEmpty()) }"),
            "a raw string" to edit("raw", goodTest, "@Test fun t() {}", "val s = \"\"\"\nimport android.os.Bundle\nimport androidx.core.Foo\n\"\"\""),
            "a name in backticks" to edit("backticks", goodTest, "fun t()", "fun `reads android.util.Log`()"),
            "a longer name" to edit("name", goodTest, "@Test fun t() {}", "@Test fun t() { val androidName = \"a\" }"),
        )
        for ((label, source) in quiet) {
            assertEquals("app: the test rule flagged $label", emptyList<String>(), testProblems(mapOf("ATest.kt" to source)))
        }
    }

    @Test
    fun `a file over 300 lines is reported with its count and over 500 as the hard cap too`() {
        assertEquals("app: an empty file was measured", 0, lineCount(""))
        assertEquals("app: a text without a final newline was miscounted", 3, lineCount("a\nb\nc"))
        assertEquals("app: a text with a final newline was miscounted", 3, lineCount("a\nb\nc\n"))
        val atCap: List<String> = sizeProblems("main", mapOf("A.kt" to linesOfText(300)))
        assertEquals("app: a file of exactly 300 lines was reported", emptyList<String>(), atCap)
        val over: List<String> = sizeProblems("main", mapOf("A.kt" to linesOfText(300), "B.kt" to linesOfText(301)))
        assertEquals("app: a file of 301 lines was not reported", listOf("app: the main file B.kt has 301 lines, over the 300 line cap"), over)
        val soft: List<String> = sizeProblems("test", mapOf("ATest.kt" to linesOfText(500)))
        assertEquals("app: a test file of 500 lines was reported as over the hard cap", listOf("app: the test file ATest.kt has 500 lines, over the 300 line cap"), soft)
        val hard: List<String> = sizeProblems("main", mapOf("C.kt" to linesOfText(501)))
        assertEquals("app: a file of 501 lines was not named as over the hard cap", listOf("app: the main file C.kt has 501 lines, over the 300 line cap and over the 500 line hard cap"), hard)
    }

    @Test
    fun `the sample editor fails when the text to change is not there`() {
        assertEquals("app: the sample editor did not change the text", "a-c", edit("x", "abc", "b", "-"))
        val thrown = assertThrows("app: an edit of text that is not there must fail", IllegalStateException::class.java) { edit("x", "abc", "z", "-") }
        assertTrue("app: the edit failure did not name the sample, said ${thrown.message}", thrown.message?.contains("\"x\"") == true)
    }

    @Test
    fun `the tile host starts the keyboard wiring`() {
        val host: String? = mainFiles()["host/TileHost.kt"]
        assertTrue("app: host/TileHost.kt must start the keyboard wiring", host != null && host.contains("wiring.start()"))
    }
}
