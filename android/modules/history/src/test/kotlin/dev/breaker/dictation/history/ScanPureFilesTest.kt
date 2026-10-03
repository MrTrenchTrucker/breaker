package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `HistorySql.kt` and `RetentionBoundary.kt` are free of Android.
 *
 * Their KDocs say so, and it is why a plain JVM test can run the SQL on a desktop SQLite and the boundary
 * without a device. Nothing stopped an `import android...` (or a fully written `android.util.Log`) landing in
 * either. This reads their comment-stripped, literal-masked code ([AdapterStatements.maskedCode]) and refuses
 * the word `android` followed by a dot, which is an import or a qualified name, and the word `Context`.
 *
 * It is text: a dependency on Android through a type that is not named `android.` or `Context` is not seen,
 * and the compiler's own classpath for the test task is the stronger guard.
 */
class ScanPureFilesTest {

    private val pureFiles = listOf("HistorySql.kt", "RetentionBoundary.kt")

    @Test
    fun `HistorySql and RetentionBoundary hold no reference to android`() {
        for (file in pureFiles) {
            assertEquals("Android in $file", emptyList<String>(), androidUses(read(file)))
        }
    }

    @Test
    fun `an import or a qualified name from android is found, and a comment, a string or a longer word is not`() {
        val found = mapOf(
            "an import" to "import android.util.Log\nobject A",
            "an import after the package" to "package p\n\nimport android.content.Context\n",
            "a qualified call" to "object A { fun f() = android.util.Log.d(\"t\", \"m\") }",
            "a qualified name over a line break" to "object A { val c: android\n    .content.Context? = null }",
            "a spaced dot" to "object A { val c: android . os . Bundle? = null }",
            "a wildcard import" to "import android.*\n",
            "the Context type" to "object A { fun f(c: Context) {} }",
        )
        for ((label, text) in found) {
            assertEquals("missed $label", false, androidUses(text).isEmpty())
        }
        val fine = mapOf(
            "a line comment" to "// import android.util.Log\nobject A",
            "a block comment" to "/* import android.util.Log */\nobject A",
            "a KDoc" to "/**\n * Runs on android.database.sqlite.\n */\nobject A",
            "a string" to "object A { const val S = \"android.util.Log\" }",
            "a raw string" to "object A { const val S = \"\"\"select 'android.os'\"\"\" }",
            "a longer word" to "object A { val androidx = 1; val myandroid = 2; val androids = 3 }",
            "a name that only starts with Context" to "object A { val ContextFree = 1 }",
        )
        for ((label, text) in fine) {
            assertEquals("flagged $label", emptyList<String>(), androidUses(text))
        }
    }

    @Test
    fun `a pure file that cannot be read as code is refused, not passed`() {
        assertThrows(AdapterShapeError::class.java) { androidUses("object A { val s = \"never ends\n}") }
    }

    private fun read(name: String): String {
        val files = ModuleFiles.mainSources().filter { it.name == name }
        check(files.size == 1) { "expected exactly one $name under src/main/kotlin, found ${files.size}" }
        return files.single().readText()
    }

    /** Each place the code of [source] names `android.` or `Context`, as the code around it. */
    private fun androidUses(source: String): List<String> {
        val code = AdapterStatements.maskedCode(source)
        return Regex("""(?<![\p{L}\p{N}_.])(android\s*\.|Context(?![\p{L}\p{N}_]))""").findAll(code).map {
            val around = code.substring(maxOf(0, it.range.first - 20), minOf(code.length, it.range.last + 20))
            around.replace(Regex("\\s+"), " ")
        }.toList()
    }
}
