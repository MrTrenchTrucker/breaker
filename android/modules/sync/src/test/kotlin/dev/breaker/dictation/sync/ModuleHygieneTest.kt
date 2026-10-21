package dev.breaker.dictation.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the module's main sources as text and checks three rules: no thread primitive, no
 * network, no file over 300 lines.
 *
 * The module's concurrency is one `Mutex.tryLock`, and its only way to reach a server is the
 * uploader the app passes in. Each check refuses, rather than passes, when it finds no main
 * source, and each has a control: the same scan run on a sample that breaks the rule must fire.
 * Comments and KDoc are not scanned, so a sentence that names a forbidden word is not a hit.
 */
class ModuleHygieneTest {

    private val threadTokens = listOf(
        "Thread", "synchronized", "Thread.sleep", "delay(", "runBlocking", "CountDownLatch",
        "java.util.concurrent", "@Synchronized",
    )
    private val networkTokens = listOf("java.net", "okhttp", "HttpURLConnection", "URL(", "Socket")
    private val lineCap = 300

    /** The text of every main source file, by path. The gradle test directory is the module folder. */
    private fun mainSources(): Map<String, String> {
        val root = File("src/main/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("android_sync: found no main source file under ${root.absolutePath}; the scan cannot run", files.isNotEmpty())
        val texts = files.associate { it.path to it.readText() }
        assertTrue(
            "android_sync: no file under ${root.absolutePath} declares QueuedSyncService, so the scan read the wrong folder: ${texts.keys}",
            texts.values.any { it.contains("class QueuedSyncService") },
        )
        return texts
    }

    /** The source with block comments, KDoc and line comments blanked out. */
    private fun code(source: String): String =
        source.replace(Regex("(?s)/[*].*?[*]/"), " ").replace(Regex("//[^\\n]*"), " ")

    /** The tokens of [tokens] that occur in the code of [source], in the order listed. */
    private fun hits(source: String, tokens: List<String>): List<String> = code(source).let { text -> tokens.filter { text.contains(it) } }

    /** The number of lines of [text]; a final line break does not start a new line. */
    private fun lineCount(text: String): Int {
        val parts = text.split('\n')
        return if (parts.last().isEmpty()) parts.size - 1 else parts.size
    }

    /** A failure here means main code uses a thread primitive, so the module is no longer single-threaded and deterministic. */
    @Test
    fun `no main source uses a thread primitive`() {
        val bad = "import java.util.concurrent.CountDownLatch\n" +
            "fun f() { Thread.sleep(10); runBlocking { delay(5) }; synchronized(lock) { } }\n" +
            "@Synchronized fun g() {}\n"
        assertEquals(
            "android_sync: control: the scan must fire on every thread token in a bad sample",
            threadTokens.toSet(),
            hits(bad, threadTokens).toSet(),
        )
        val quiet = "// Thread.sleep and synchronized are not used here\n/* runBlocking, delay( */\nval x = 1\n"
        assertEquals("android_sync: control: tokens inside comments must not count", emptyList<String>(), hits(quiet, threadTokens))

        val found = mainSources().mapValues { hits(it.value, threadTokens) }.filterValues { it.isNotEmpty() }
        assertEquals("android_sync: main sources must use no thread primitive, found by file", emptyMap<String, List<String>>(), found)
    }

    /** A failure here means main code reaches the network itself instead of going through the uploader. */
    @Test
    fun `no main source reaches the network`() {
        val bad = "import java.net.Socket\nval c = okhttp3.OkHttpClient()\nval u = URL(\"x\")\nval h: HttpURLConnection? = null\n"
        assertEquals(
            "android_sync: control: the scan must fire on every network token in a bad sample",
            networkTokens.toSet(),
            hits(bad, networkTokens).toSet(),
        )
        val quiet = "// java.net and okhttp are not used here\nval x = 1\n"
        assertEquals("android_sync: control: tokens inside comments must not count", emptyList<String>(), hits(quiet, networkTokens))

        val found = mainSources().mapValues { hits(it.value, networkTokens) }.filterValues { it.isNotEmpty() }
        assertEquals("android_sync: main sources must not reach the network, found by file", emptyMap<String, List<String>>(), found)
    }

    /** A failure here means a main source has grown past the line cap and must be split by responsibility. */
    @Test
    fun `no main source is longer than 300 lines`() {
        assertEquals("android_sync: control: 300 lines must count as 300", 300, lineCount("x\n".repeat(300)))
        assertEquals("android_sync: control: 301 lines must count as 301", 301, lineCount("x\n".repeat(301)))
        assertTrue("android_sync: control: 301 lines must be over the cap", lineCount("x\n".repeat(301)) > lineCap)

        val long = mainSources().mapValues { lineCount(it.value) }.filterValues { it > lineCap }
        assertEquals("android_sync: main sources longer than $lineCap lines, by file", emptyMap<String, Int>(), long)
    }
}
