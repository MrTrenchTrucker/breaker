package dev.breaker.dictation.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the module's main sources as text and checks the rules that keep the module pure: no
 * thread primitive, no network token, no file or clock read, and no file over 300 lines. One
 * rule covers main and test sources: the key phrase must not appear.
 *
 * Each rule has a control: a bad sample must fire, and a quiet sample (tokens only in comments)
 * must not. The scan also refuses to pass when it finds no main source or no declared marker.
 */
internal class ModuleHygieneTest {

    private val marker = "enum class ScheduleDecision"
    private val threadTokens = listOf(
        "Thread", "synchronized", "Thread.sleep", "delay(", "runBlocking", "CountDownLatch",
        "java.util.concurrent", "@Synchronized",
    )
    private val networkTokens = listOf("java.net", "okhttp", "HttpURLConnection", "URL(", "Socket")
    private val fileTokens = listOf(
        "File(", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile",
        "java.nio.file",
    )
    private val clockTokens = listOf("currentTimeMillis", "nanoTime", "Instant.now", "LocalDateTime.now")
    private val fileAndClockTokens = fileTokens + clockTokens
    private val networkAllowed = setOf("UpdateHttpOpener.kt")
    private val fileAllowed = setOf("ApkStaging.kt", "LedgerStore.kt")
    private val frameworkTokens = listOf("android.")
    private val frameworkAllowed: Set<String> = emptySet()
    private val lineCap = 300
    private val keyPhrase = "PRIVATE" + " KEY"

    /** The text of every .kt file under [root], by path; empty when the folder does not exist. */
    private fun readSources(root: File): Map<String, String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.path to it.readText() }

    /** Fails when no text in [texts] declares the marker, so the scan cannot have read the right folder. */
    private fun requireMarker(texts: Map<String, String>) {
        assertTrue(
            "android_updater: no source declares the marker class, so the scan read the wrong folder: ${texts.keys}",
            texts.values.any { it.contains(marker) },
        )
    }

    /** The main sources of the module as text; fails when there are none. */
    private fun mainSources(root: File = File("src/main/kotlin")): Map<String, String> {
        val texts = readSources(root)
        assertTrue(
            "android_updater: found no main source file under ${root.absolutePath}; the scan cannot run",
            texts.isNotEmpty(),
        )
        requireMarker(texts)
        return texts
    }

    /** The test sources of the module as text. */
    private fun testSources(): Map<String, String> = readSources(File("src/test/kotlin"))

    /** The source with block comments and line comments blanked out. */
    private fun code(source: String): String =
        source.replace(Regex("(?s)/[*].*?[*]/"), " ").replace(Regex("//[^\\n]*"), " ")

    /** The tokens of [tokens] that occur in the code of [source], in the order listed. */
    private fun hits(source: String, tokens: List<String>): List<String> {
        val text = code(source)
        return tokens.filter { text.contains(it) }
    }

    /** The number of lines of [text]; a final line break does not start a new line. */
    private fun lineCount(text: String): Int {
        val parts = text.split('\n')
        return if (parts.last().isEmpty()) parts.size - 1 else parts.size
    }

    @Test
    fun `no main source uses a thread primitive`() {
        val bad = "import java.util.concurrent.CountDownLatch\n" +
            "fun f() { Thread.sleep(10); runBlocking { delay(5) }; synchronized(lock) { } }\n" +
            "@Synchronized fun g() {}\n"
        assertEquals(
            "android_updater: control: the scan must fire on every thread token in a bad sample",
            threadTokens.toSet(),
            hits(bad, threadTokens).toSet(),
        )
        val quiet = "// Thread.sleep and synchronized are not used here\n/* runBlocking, delay( */\nval x = 1\n"
        assertEquals(
            "android_updater: control: tokens inside comments must not count",
            emptyList<String>(),
            hits(quiet, threadTokens),
        )

        val found = mainSources().mapValues { hits(it.value, threadTokens) }.filterValues { it.isNotEmpty() }
        assertEquals(
            "android_updater: main sources must use no thread primitive, found by file",
            emptyMap<String, List<String>>(),
            found,
        )
    }

    @Test
    fun `only the HTTP opener reaches the network`() {
        val bad = "import java.net.Socket\nval c = okhttp3.OkHttpClient()\n" +
            "val u = URL(\"x\")\nval h: HttpURLConnection? = null\n"
        assertEquals(
            "android_updater: control: the scan must fire on every network token in a bad sample",
            networkTokens.toSet(),
            hits(bad, networkTokens).toSet(),
        )
        val quiet = "// java.net and okhttp are not used here\nval x = 1\n"
        assertEquals(
            "android_updater: control: tokens inside comments must not count",
            emptyList<String>(),
            hits(quiet, networkTokens),
        )

        val found = mainSources().filterKeys { File(it).name !in networkAllowed }
            .mapValues { hits(it.value, networkTokens) }.filterValues { it.isNotEmpty() }
        assertEquals(
            "android_updater: main sources other than the HTTP opener must not reach the network, found by file",
            emptyMap<String, List<String>>(),
            found,
        )
    }

    @Test
    fun `file io is confined to the staging file and the clock is never read`() {
        val bad = "val f = File(\"x\")\n" +
            "val a = java.io.FileInputStream(\"x\")\n" +
            "val b = java.io.FileOutputStream(\"x\")\n" +
            "val c = java.io.FileReader(\"x\")\n" +
            "val d = java.io.FileWriter(\"x\")\n" +
            "val e = java.io.RandomAccessFile(\"x\", \"r\")\n" +
            "val g = java.nio.file.Paths.get(\"x\")\n" +
            "val h = System.currentTimeMillis()\n" +
            "val i = System.nanoTime()\n" +
            "val j = java.time.Instant.now()\n" +
            "val k = java.time.LocalDateTime.now()\n"
        assertEquals(
            "android_updater: control: the scan must fire on every file and clock token in a bad sample",
            fileAndClockTokens.toSet(),
            hits(bad, fileAndClockTokens).toSet(),
        )
        val bareImports = "import java.io.InputStream\nimport java.io.IOException\nimport java.util.Base64\nval x = 1\n"
        assertEquals(
            "android_updater: control: a bare java.io stream or exception import must not count",
            emptyList<String>(),
            hits(bareImports, fileAndClockTokens),
        )
        val quiet = "// File( and nanoTime are not used here\n/* Instant.now */\nval x = 1\n"
        assertEquals(
            "android_updater: control: tokens inside comments must not count",
            emptyList<String>(),
            hits(quiet, fileAndClockTokens),
        )

        val found = mainSources().filterKeys { File(it).name !in fileAllowed }
            .mapValues { hits(it.value, fileTokens) }.filterValues { it.isNotEmpty() }
        assertEquals(
            "android_updater: file tokens found outside the allow-list, by file",
            emptyMap<String, List<String>>(),
            found,
        )
        val clockFound = mainSources().mapValues { hits(it.value, clockTokens) }.filterValues { it.isNotEmpty() }
        assertEquals(
            "android_updater: main sources must not read the clock, found by file",
            emptyMap<String, List<String>>(),
            clockFound,
        )
    }

    @Test
    fun `no main source is longer than 300 lines`() {
        assertEquals("android_updater: control: 300 lines must count as 300", 300, lineCount("x\n".repeat(300)))
        assertEquals("android_updater: control: 301 lines must count as 301", 301, lineCount("x\n".repeat(301)))
        assertTrue(
            "android_updater: control: 301 lines must be over the cap",
            lineCount("x\n".repeat(301)) > lineCap,
        )

        val long = mainSources().mapValues { lineCount(it.value) }.filterValues { it > lineCap }
        assertEquals(
            "android_updater: main sources longer than $lineCap lines, by file",
            emptyMap<String, Int>(),
            long,
        )
    }

    @Test
    fun `no source file in main or test spells the key phrase`() {
        val sample = "-----BEGIN " + keyPhrase + "-----\n"
        assertTrue(
            "android_updater: control: the phrase check must fire on a bad sample",
            sample.contains(keyPhrase),
        )
        assertTrue(
            "android_updater: found no test source, so the phrase scan cannot run",
            testSources().isNotEmpty(),
        )

        val found = (mainSources() + testSources()).filterValues { it.contains(keyPhrase) }.keys
        assertEquals(
            "android_updater: no source may spell the key phrase, found by file",
            emptySet<String>(),
            found,
        )
    }

    @Test
    fun `the scan refuses a folder with no main source`() {
        val thrown = runCatching { mainSources(File("no-such-source-folder")) }.exceptionOrNull()
        assertNotNull("android_updater: control: a folder with no main source must be refused", thrown)
        assertTrue(
            "android_updater: control: the refusal must say the scan found no main source",
            thrown!!.message!!.contains("found no main source"),
        )
    }

    @Test
    fun `the scan refuses sources that do not declare the marker`() {
        val thrown = runCatching { requireMarker(mapOf("Other.kt" to "class Other")) }.exceptionOrNull()
        assertNotNull("android_updater: control: sources without the marker must be refused", thrown)
        assertTrue(
            "android_updater: control: the refusal must say no source declares the marker",
            thrown!!.message!!.contains("declares the marker"),
        )
    }

    @Test
    fun `no main source outside the framework allow-list names an android class`() {
        val bad = "import android.util.Log\nval x = 1\n"
        assertEquals(
            "android_updater: control: the scan must fire on an android name in a bad sample",
            frameworkTokens.toSet(),
            hits(bad, frameworkTokens).toSet(),
        )
        val quiet = "// android.util.Log is not used here\nval x = 1\n"
        assertEquals(
            "android_updater: control: an android name inside a comment must not count",
            emptyList<String>(),
            hits(quiet, frameworkTokens),
        )
        val found = mainSources().filterKeys { File(it).name !in frameworkAllowed }
            .mapValues { hits(it.value, frameworkTokens) }.filterValues { it.isNotEmpty() }
        assertEquals(
            "android_updater: main sources must not name an android class outside the allow-list, found by file",
            emptyMap<String, List<String>>(),
            found,
        )
    }

    @Test
    fun `every allow-listed file name exists in the main sources`() {
        val names = mainSources().keys.map { File(it).name }.toSet()
        for (listed in networkAllowed + fileAllowed + frameworkAllowed) {
            assertTrue("android_updater: allow-listed file $listed is not in the main sources", listed in names)
        }
    }

    @Test
    fun `an allow-list does not excuse a file it does not name`() {
        val samples = mapOf("ManifestHttpSource.kt" to "import java.net.URL\n", "UpdateHttpOpener.kt" to "import java.net.URL\n")
        val flagged = samples.filterKeys { it !in networkAllowed }.filterValues { hits(it, networkTokens).isNotEmpty() }.keys
        assertEquals("android_updater: control: only the file that is not listed must be flagged", setOf("ManifestHttpSource.kt"), flagged)
        assertEquals(
            "android_updater: control: a comment-only network token must not count",
            emptyList<String>(),
            hits("// java.net is not used here\nval x = 1\n", networkTokens),
        )
    }
}
