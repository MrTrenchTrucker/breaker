package dev.breaker.dictation.phrases

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the module's main sources as text and checks the rules that keep the phrases
 * module on the core ports alone: no Android import, no import of another module's
 * package, and no thread primitive, executor, timer, coroutine, clock read, sleep or
 * synchronized block.
 *
 * The rule logic is one function over source text. Each rule has a RED sample that it
 * must flag and a CLEAN sample it must not flag, and both run before the real files are
 * read. Comments are not scanned; string literals are.
 */
class ModuleHygieneTest {

    private val expectedMainFiles = listOf("PhraseMatcher.kt", "PhraseDetector.kt")

    /** Each rule's name, and the pattern that flags it in code with comments removed. */
    private val rules: List<Pair<String, Regex>> = listOf(
        "android import" to Regex("""(?m)^\s*import\s+androidx?\."""),
        "module import" to Regex("""dev\.breaker\.dictation\.(?!core\.|phrases\b)"""),
        "thread" to Regex("""\bThread\b"""),
        "executor" to Regex("""\bExecutor\b"""),
        "handler" to Regex("""\bHandler\b"""),
        "timer" to Regex("""\bTimer\b"""),
        "coroutine" to Regex("""(?i)coroutine"""),
        "wall clock" to Regex("""System\.currentTimeMillis|System\.nanoTime"""),
        "clock" to Regex("""\bClock\b"""),
        "sleep" to Regex("""\bsleep\b"""),
        "synchronized" to Regex("""\bsynchronized\b"""),
    )

    /** The names of the rules that [source] breaks, in rule order. */
    private fun hygieneViolations(source: String): List<String> {
        val code = codeOnly(source)
        return rules.filter { it.second.containsMatchIn(code) }.map { it.first }
    }

    /**
     * [source] with line comments and block comments replaced by spaces (line breaks kept),
     * and string literals kept as they are. Block comments do not nest in this copy.
     */
    private fun codeOnly(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val newline = source.indexOf('\n', i)
                    i = if (newline < 0) source.length else newline
                }
                source.startsWith("/*", i) -> {
                    val close = source.indexOf("*/", i + 2)
                    val end = if (close < 0) source.length else close + 2
                    for (k in i until end) out.append(if (source[k] == '\n') '\n' else ' ')
                    i = end
                }
                source[i] == '"' -> {
                    val start = i
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i = minOf(i + 1, source.length)
                    out.append(source, start, i)
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The module folder: the nearest folder at or above the working directory that holds a build file and the main package. */
    private fun moduleRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val found = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, MAIN_PACKAGE_PATH).isDirectory }
        return found ?: error("android_phrases: no module folder with $MAIN_PACKAGE_PATH at or above $start")
    }

    /** The text of every main Kotlin source of the module, by file name. Fails when the folder holds none. */
    private fun mainTexts(): Map<String, String> {
        val root = File(moduleRoot(), MAIN_PACKAGE_PATH)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("android_phrases: found no main Kotlin source under $root", files.isNotEmpty())
        return files.associate { it.name to it.readText() }
    }

    @Test
    fun `the main folder holds the two expected sources`() {
        val names = mainTexts().keys
        assertTrue("android_phrases: expected $expectedMainFiles under the main folder, found $names", names.containsAll(expectedMainFiles))
    }

    @Test
    fun `each rule flags its own RED sample and only that rule`() {
        val samples = mapOf(
            "android import" to "import android.util.Log\nclass A\n",
            "module import" to "import dev.breaker.dictation.overlay.Tile\nclass A\n",
            "thread" to "val t = Thread()\n",
            "executor" to "val e: Executor = pool\n",
            "handler" to "val h = Handler()\n",
            "timer" to "val t = Timer()\n",
            "coroutine" to "val s = CoroutineScope(job)\n",
            "wall clock" to "val now = System.currentTimeMillis()\n",
            "clock" to "val c = Clock.systemUTC()\n",
            "sleep" to "fun wait() = sleep(5)\n",
            "synchronized" to "synchronized(lock) { }\n",
        )
        assertEquals("every rule has a RED sample", rules.map { it.first }.toSet(), samples.keys)
        for ((rule, sample) in samples) {
            assertEquals("the RED sample for [$rule] must be flagged by that rule alone", listOf(rule), hygieneViolations(sample))
        }
    }

    @Test
    fun `a CLEAN sample with rule words only in comments is not flagged`() {
        val clean = "// import android.util.Log, Thread, Handler and sleep are only words here\n" +
            "/* System.currentTimeMillis, Clock, synchronized and dev.breaker.dictation.overlay */\n" +
            "/** KDoc: Timer, Executor, CoroutineScope, Thread.sleep */\n" +
            "import dev.breaker.dictation.core.model.WordUpdate\n" +
            "import dev.breaker.dictation.phrases.PhraseMatcher\n" +
            "val x = 1\n"
        assertEquals("comments are not scanned", emptyList<String>(), hygieneViolations(clean))
    }

    @Test
    fun `a string literal is scanned, so a rule word in a string is flagged`() {
        assertEquals("string literals are kept and scanned", listOf("thread"), hygieneViolations("val s = \"Thread\"\n"))
    }

    @Test
    fun `the real main sources pass every rule`() {
        val texts = mainTexts()
        for ((name, text) in texts) {
            assertEquals("android_phrases: $name breaks a hygiene rule", emptyList<String>(), hygieneViolations(text))
        }
    }

    private companion object {
        const val MAIN_PACKAGE_PATH = "src/main/kotlin/dev/breaker/dictation/phrases"
    }
}
